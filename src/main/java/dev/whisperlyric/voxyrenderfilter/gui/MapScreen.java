package dev.whisperlyric.voxyrenderfilter.gui;

import dev.whisperlyric.voxyrenderfilter.config.VrfConfig;
import dev.whisperlyric.voxyrenderfilter.config.VrfConfig.ScanMode;
import dev.whisperlyric.voxyrenderfilter.filter.RectFilter;
import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import dev.whisperlyric.voxyrenderfilter.index.CacheCoverageIndex;
import dev.whisperlyric.voxyrenderfilter.map.LodMapRenderer;
import dev.whisperlyric.voxyrenderfilter.map.MapCache;
import dev.whisperlyric.voxyrenderfilter.purge.CachePurgeService;
import dev.whisperlyric.voxyrenderfilter.purge.RenderNodeRefresh;
import dev.whisperlyric.voxyrenderfilter.tracker.ActiveTopLevelTracker;
import dev.whisperlyric.voxyrenderfilter.util.VoxyAccess;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cache map (minimap style, like FTB Chunks / Xaero):
 * - light blue: LOD data cached on disk for this world (aggregated per TLN column, 512x512 blocks)
 * - green: columns in the current render ring that have usable LOD data on disk
 * Drag to select, right-click a selection for a context menu (block / allow-only / invert / purge),
 * right-click empty space to clear, scroll to zoom (centered on the cursor), WASD/arrows to pan.
 * Selection snaps to region files (32x32 chunks) at low zoom and to lvl0 sections (2x2 chunks)
 * once sections are visible. Ctrl toggles multi-select; Shift forces a square selection.
 * Overlapping selections are unioned into pairwise-disjoint rectangles after each drag.
 */
public class MapScreen extends Screen {

    private static final int MIN_ZOOM = 2;
    /** Max zoom: px per TLN column (512 blocks); 4096 = 128px per chunk. */
    private static final int MAX_ZOOM = 4096;
    /** Pan distance per WASD/arrow keypress as a screen fraction; 1/16 of the former half-screen step (~1/32 screen). */
    private static final double PAN_FRACTION = 0.05;
    /** Above this zoom (>= 2px per chunk) selection snaps to lvl0 sections (2x2 chunks). */
    private static final int CHUNK_SELECT_ZOOM = 128;
    /** Above this zoom (>= 4px per chunk) draw a chunk grid inside selections. */
    private static final int CHUNK_GRID_ZOOM = 256;
    /** Max chunk grid lines per axis per selection, keeps drawing cheap on huge selections. */
    private static final int CHUNK_GRID_MAX_LINES = 256;
    private static final int GRID_COLOR = 0x66FFFFFF;
    private static final int TEXT_MAX_SELECTIONS = 6;
    private static final String OVERLAY_LABEL_ON = "voxyrenderfilter.map.button.overlay.on";
    private static final String OVERLAY_LABEL_OFF = "voxyrenderfilter.map.button.overlay.off";
    private static final String SELECT_LABEL_ON = "voxyrenderfilter.map.button.select.on";
    private static final String SELECT_LABEL_OFF = "voxyrenderfilter.map.button.select.off";
    /** Index of the overlay toggle in the button column (last). */
    private static final int OVERLAY_BUTTON_INDEX = 5;
    /** Index of the selection-mode toggle (appended after the overlay button). */
    private static final int SELECT_BUTTON_INDEX = 6;
    /** Index of the scan-mode toggle (after the selection toggle). */
    private static final int SCAN_BUTTON_INDEX = 7;
    /** Index of the dimension-cycle button (last). */
    private static final int DIM_BUTTON_INDEX = 8;
    private static final String SCAN_LABEL_AUTO = "voxyrenderfilter.map.button.scan.auto";
    private static final String SCAN_LABEL_MANUAL = "voxyrenderfilter.map.button.scan.manual";
    private static final String DIM_LABEL = "voxyrenderfilter.map.button.dimension";
    /**
     * View distance input bounds, mirroring voxy's own config slider value (10..1024) exactly:
     * same number, same unit, voxy stays the source of truth.
     */
    private static final int VIEW_DISTANCE_MIN = 10;
    private static final int VIEW_DISTANCE_MAX = 1024;
    private static final int VIEW_DISTANCE_BOX_W = 64;
    private static final String ALPHA_LABEL = "voxyrenderfilter.map.backgroundalpha";
    private static final int ALPHA_BOX_W = 64;
    /** Background color behind the map (dark); alpha comes from the adjustable {@link #backgroundAlpha} field. */
    private static final int BACKGROUND_RGB = 0x15151D;
    private static final int CACHED_COLOR = 0x50A0B8C8;
    private static final int ACTIVE_COLOR = 0x802F9E4F;
    private static final int ACTIVE_BORDER_COLOR = 0x801B5E2F;
    private static final int BLOCKED_COLOR = 0x60FF8C00;
    /** Faint orange over the whole blocked rect, also visible on uncached terrain. */
    private static final int BLOCKED_RECT_COLOR = 0x30FF8C00;

    private record ButtonZone(int x, int y, int w, int h, String label, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    private record MenuEntry(String label, Runnable action) {
    }

    /** Selection: chunkRect in chunk coords; sectionUnit = snapped to lvl0 sections at high zoom. */
    private record SelRect(RectFilter chunkRect, boolean sectionUnit) {
    }

    private final Minecraft mc;

    /** View center in TLN column units (fractional = continuous 512-block coords). */
    private double centerTlnX;
    private double centerTlnZ;
    private int zoom = 8;

    private final List<SelRect> selections = new ArrayList<>();
    private boolean multiSelect;
    /** When false, left-drag pans the map instead of drawing a selection box; existing selections stay visible. */
    private boolean selectionMode = true;
    private boolean dragging;
    private boolean panning;
    private boolean selSectionUnit;
    private int selAnchorX;
    private int selAnchorZ;
    private boolean rightDragging;
    private boolean rightSelSectionUnit;
    private int rightAnchorX;
    private int rightAnchorZ;
    private int rightCurX;
    private int rightCurZ;

    private volatile int purgeState;      // 0 idle, 1 running, 2 finished
    private volatile String purgeResult = "";

    private boolean menuOpen;
    private int menuX;
    private int menuY;
    private int menuW;
    private int menuH;
    private final List<MenuEntry> menuItems = new ArrayList<>();
    /** Last frame's hovered menu item index, -1 = none (set while rendering, used on click). */
    private int hoveredMenu = -1;

    private final CacheCoverageIndex cacheIndex = new CacheCoverageIndex(null);
    private final List<ButtonZone> buttons = new ArrayList<>();
    /** Green columns drawn last frame (disk-backed only). */
    private int visibleActiveCount;
    /** Whether the color overlays (cached / active / blocked) are drawn, toggled by the top-right button. */
    private boolean showOverlays = true;
    /** Precise voxy render distance input (same value/unit as voxy's config slider, 10..1024); mirrors voxy's value when not focused. */
    private EditBox viewDistanceBox;
    /** Map screen background alpha (0-255); 255 = fully opaque (default), adjustable via the input box. */
    private int backgroundAlpha = 255;
    private EditBox backgroundAlphaBox;
    /** False once init() has run for a real map open; window resizes skip the one-shot work. */
    private boolean firstInit = true;

    public MapScreen() {
        super(Component.translatable("voxyrenderfilter.map.title"));
        this.mc = Minecraft.getInstance();
    }

    @Override
    protected void init() {
        this.centerOnPlayer();
        VrfConfig.INSTANCE.load();
        this.backgroundAlpha = VrfConfig.INSTANCE.backgroundAlpha;
        if (VrfConfig.INSTANCE.scanMode == ScanMode.AUTO) {
            // Render-first: show the cache immediately, then re-validate it once in the background
            LodMapRenderer.INSTANCE.requestValidation();
        }
        // Coverage loads when the map opens (event-driven, not proactive)
        this.cacheIndex.requestScan(VoxyAccess.getCurrentEngine(), false);
        this.buttons.clear();
        // Button width fits the widest label, right-aligned column
        String[] labels = {
                "voxyrenderfilter.map.button.clearfilter",
                "voxyrenderfilter.map.button.purge",
                "voxyrenderfilter.map.button.clear",
                "voxyrenderfilter.map.button.rescan",
                "voxyrenderfilter.map.button.center",
                this.overlayLabel(),
                this.selectLabel(),
                this.scanLabel(),
                this.dimLabel()
        };
        List<Runnable> actions = List.of(
                this::clearFilter,
                this::purgeSelection,
                this::clearSelection,
                this::rescanSelection,
                this::centerOnPlayer,
                this::toggleOverlays,
                this::toggleSelectionMode,
                this::toggleScanMode,
                this::cycleDimension);
        int maxW = 0;
        for (String label : labels) {
            maxW = Math.max(maxW, this.mc.font.width(Component.translatable(label)));
        }
        // The toggle labels change; reserve the wider of the two states so the buttons do not resize
        maxW = Math.max(maxW, this.mc.font.width(Component.translatable(OVERLAY_LABEL_ON)));
        maxW = Math.max(maxW, this.mc.font.width(Component.translatable(OVERLAY_LABEL_OFF)));
        maxW = Math.max(maxW, this.mc.font.width(Component.translatable(SELECT_LABEL_ON)));
        maxW = Math.max(maxW, this.mc.font.width(Component.translatable(SELECT_LABEL_OFF)));
        maxW = Math.max(maxW, this.mc.font.width(Component.translatable(SCAN_LABEL_AUTO)));
        maxW = Math.max(maxW, this.mc.font.width(Component.translatable(SCAN_LABEL_MANUAL)));
        maxW = Math.max(maxW, this.mc.font.width(this.dimLabelComponent()));
        int w = maxW + 14;
        int x = this.width - w - 4;
        int y = 4;
        for (int i = 0; i < labels.length; i++) {
            this.buttons.add(new ButtonZone(x, y, w, 18, labels[i], actions.get(i)));
            y += 22;
        }
        // Precise voxy render distance input (TLN columns), below the button column, right-aligned
        this.viewDistanceBox = new EditBox(this.mc.font,
                this.width - VIEW_DISTANCE_BOX_W - 4, y + 8, VIEW_DISTANCE_BOX_W, 18,
                Component.translatable("voxyrenderfilter.map.viewdistance"));
        this.viewDistanceBox.setMaxLength(4);
        this.viewDistanceBox.setFocused(false);
        // Background transparency input (0-255), below the view distance box, right-aligned
        this.backgroundAlphaBox = new EditBox(this.mc.font,
                this.width - ALPHA_BOX_W - 4, this.viewDistanceBox.getY() + 18 + 8, ALPHA_BOX_W, 18,
                Component.translatable(ALPHA_LABEL));
        this.backgroundAlphaBox.setMaxLength(3);
        this.backgroundAlphaBox.setFocused(false);
    }

    /**
     * Rescans only the region-file selections (chunk-level selections are ignored), asynchronously:
     * their cached images are deleted and the columns regenerate from the current disk state.
     * In AUTO mode this is a no-op: the background scan already keeps everything in sync.
     */
    private void rescanSelection() {
        if (VrfConfig.INSTANCE.scanMode == ScanMode.AUTO) {
            return;
        }
        LongOpenHashSet columns = new LongOpenHashSet();
        for (SelRect sel : this.selections) {
            if (sel.sectionUnit()) {
                continue; // chunk-level selection: ignored
            }
            RectFilter r = sel.chunkRect();
            for (int tx = r.minX() >> 5; tx <= r.maxX() >> 5; tx++) {
                for (int tz = r.minZ() >> 5; tz <= r.maxZ() >> 5; tz++) {
                    columns.add(ActiveTopLevelTracker.pack(tx, tz));
                }
            }
        }
        if (!columns.isEmpty()) {
            LodMapRenderer.INSTANCE.rescanColumns(columns);
        }
    }

    private void centerOnPlayer() {
        LocalPlayer player = this.mc.player;
        if (player != null) {
            // Fractional coords keep the player marker exactly centered
            this.centerTlnX = player.getX() / 512.0;
            this.centerTlnZ = player.getZ() / 512.0;
        }
    }

    private void clearSelection() {
        this.selections.clear();
    }

    /** Translation key of the current overlay toggle state. */
    private String overlayLabel() {
        return this.showOverlays ? OVERLAY_LABEL_ON : OVERLAY_LABEL_OFF;
    }

    /** Toggles the color overlays (cached / active / blocked) and refreshes the button label. */
    private void toggleOverlays() {
        this.showOverlays = !this.showOverlays;
        if (OVERLAY_BUTTON_INDEX < this.buttons.size()) {
            ButtonZone b = this.buttons.get(OVERLAY_BUTTON_INDEX);
            this.buttons.set(OVERLAY_BUTTON_INDEX,
                    new ButtonZone(b.x(), b.y(), b.w(), b.h(), this.overlayLabel(), this::toggleOverlays));
        }
    }

    /** Translation key of the current selection-mode toggle state. */
    private String selectLabel() {
        return this.selectionMode ? SELECT_LABEL_ON : SELECT_LABEL_OFF;
    }

    /** Toggles selection mode (off = left-drag pans the map, selections kept) and refreshes the button label. */
    private void toggleSelectionMode() {
        this.selectionMode = !this.selectionMode;
        this.updateButtonLabel(SELECT_BUTTON_INDEX, this.selectLabel(), this::toggleSelectionMode);
    }

    /** Translation key of the current scan-mode state. */
    private String scanLabel() {
        return VrfConfig.INSTANCE.scanMode == ScanMode.AUTO ? SCAN_LABEL_AUTO : SCAN_LABEL_MANUAL;
    }

    /** Toggles manual/auto scanning (persisted) and refreshes the button label. */
    private void toggleScanMode() {
        VrfConfig.INSTANCE.scanMode = VrfConfig.INSTANCE.scanMode == ScanMode.AUTO ? ScanMode.MANUAL : ScanMode.AUTO;
        VrfConfig.INSTANCE.save();
        this.updateButtonLabel(SCAN_BUTTON_INDEX, this.scanLabel(), this::toggleScanMode);
        if (VrfConfig.INSTANCE.scanMode == ScanMode.AUTO) {
            // Kick off the background refresh and a one-shot validation immediately
            LodMapRenderer.INSTANCE.refreshNearPlayer();
            LodMapRenderer.INSTANCE.requestValidation();
        }
    }

    /** "维度:<dimensionId>" component for the currently viewed dimension. */
    private Component dimLabelComponent() {
        String dim = MapCache.INSTANCE.getDimension();
        return Component.translatable(DIM_LABEL, dim != null ? dim : "?");
    }

    /** Translation key of the dimension button (dynamic text is the component with the dimension id). */
    private String dimLabel() {
        return DIM_LABEL;
    }

    /** Cycles the map through all dimensions that already have cached images. */
    private void cycleDimension() {
        java.util.List<String> dims = MapCache.INSTANCE.listDimensions();
        if (dims.isEmpty()) {
            return;
        }
        String current = MapCache.INSTANCE.getDimension();
        int idx = dims.indexOf(current);
        String next = dims.get((idx + 1) % dims.size());
        LodMapRenderer.INSTANCE.viewDimension(next);
        // Coverage only reflects the player's live dimension: refresh it when the map is back on it
        if (java.util.Objects.equals(next, this.currentDimId())) {
            this.cacheIndex.requestScan(VoxyAccess.getCurrentEngine(), false);
        }
        this.updateButtonLabel(DIM_BUTTON_INDEX, this.dimLabel(), this::cycleDimension);
    }

    /** Replaces a dynamic button's label in place (keeps position/size/action). */
    private void updateButtonLabel(int index, String label, Runnable action) {
        if (index < this.buttons.size()) {
            ButtonZone b = this.buttons.get(index);
            this.buttons.set(index, new ButtonZone(b.x(), b.y(), b.w(), b.h(), label, action));
        }
    }

    /** Current voxy render distance (the same number voxy's config slider shows, 10..1024), or -1 when voxy config is unavailable. */
    private int currentViewDistance() {
        VoxyConfig config = VoxyConfig.CONFIG;
        if (config == null) {
            return -1;
        }
        // Mirror voxy's config slider exactly: value = round(sectionRenderDistance * 16)
        return Math.round(config.sectionRenderDistance * 16);
    }

    /** Mirrors voxy's actual value into the input box unless the player is editing it. */
    private void syncViewDistanceBox() {
        if (this.viewDistanceBox == null || this.viewDistanceBox.isFocused()) {
            return;
        }
        int cur = this.currentViewDistance();
        if (cur >= 0) {
            this.viewDistanceBox.setValue(String.valueOf(cur));
        }
    }

    /** Applies the typed value (10..1024, same as voxy's slider) through voxy's own conversion and persists it; invalid input is ignored (the box re-syncs next frame). */
    private void applyViewDistance() {
        if (this.viewDistanceBox == null) {
            return;
        }
        this.viewDistanceBox.setFocused(false);
        int value;
        try {
            value = Integer.parseInt(this.viewDistanceBox.getValue().trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (value < VIEW_DISTANCE_MIN || value > VIEW_DISTANCE_MAX) {
            return;
        }
        // Same mapping as voxy's config slider: sectionRenderDistance = value / 16
        float sectionRenderDistance = value / 16.0f;
        VoxyConfig config = VoxyConfig.CONFIG;
        if (config == null) {
            return;
        }
        config.sectionRenderDistance = sectionRenderDistance;
        config.save();
        VoxyRenderSystem renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem != null) {
            renderSystem.setRenderDistance(sectionRenderDistance);
        }
        // View distance change is a validation trigger: re-check the freshly resized ring
        LodMapRenderer.INSTANCE.requestValidation();
    }

    /** Draws the view distance input box with a label; hidden when voxy config is unavailable. */
    private void drawViewDistanceInput(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (this.viewDistanceBox == null || this.currentViewDistance() < 0) {
            return;
        }
        this.syncViewDistanceBox();
        Component label = Component.translatable("voxyrenderfilter.map.viewdistance");
        int labelW = this.mc.font.width(label);
        extractor.text(this.mc.font, label, this.viewDistanceBox.getX() - labelW - 6,
                this.viewDistanceBox.getY() + (18 - 8) / 2, 0xFFAAAAAA, true);
        this.viewDistanceBox.extractWidgetRenderState(extractor, mouseX, mouseY, partialTick);
    }

    /** Applies the typed value to the map background alpha; invalid input is ignored (the box re-syncs next frame). */
    private void applyBackgroundAlpha() {
        if (this.backgroundAlphaBox == null) {
            return;
        }
        this.backgroundAlphaBox.setFocused(false);
        int value;
        try {
            value = Integer.parseInt(this.backgroundAlphaBox.getValue().trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (value < 0 || value > 255) {
            return;
        }
        this.backgroundAlpha = value;
        VrfConfig.INSTANCE.backgroundAlpha = value;
        VrfConfig.INSTANCE.save();
    }

    /** Draws the background transparency input box with a label; reflects the current alpha when not focused. */
    private void drawBackgroundAlphaInput(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (this.backgroundAlphaBox == null) {
            return;
        }
        if (!this.backgroundAlphaBox.isFocused()) {
            this.backgroundAlphaBox.setValue(String.valueOf(this.backgroundAlpha));
        }
        Component label = Component.translatable(ALPHA_LABEL);
        int labelW = this.mc.font.width(label);
        extractor.text(this.mc.font, label, this.backgroundAlphaBox.getX() - labelW - 6,
                this.backgroundAlphaBox.getY() + (18 - 8) / 2, 0xFFAAAAAA, true);
        this.backgroundAlphaBox.extractWidgetRenderState(extractor, mouseX, mouseY, partialTick);
    }

    /** Focuses the box when the click lands on it; unfocuses it otherwise. Returns true when the click was on the box. */
    private boolean handleBoxClick(EditBox box, int mx, int my, MouseButtonEvent event) {
        if (box == null) {
            return false;
        }
        if (box.isMouseOver(mx, my)) {
            box.setFocused(true);
            box.onClick(event, false);
            return true;
        }
        box.setFocused(false);
        return false;
    }

    /** Handles Enter (apply) / Esc (cancel) / key forwarding for a focused input box; true = consumed. */
    private boolean handleFocusedBoxKey(EditBox box, int key, KeyEvent event) {
        if (box == null || !box.isFocused()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (box == this.viewDistanceBox) {
                this.applyViewDistance();
            } else if (box == this.backgroundAlphaBox) {
                this.applyBackgroundAlpha();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            box.setFocused(false);
            return true;
        }
        return box.keyPressed(event);
    }

    /** Clears the render filter: restores rendering of all TLN columns. */
    private void clearFilter() {
        if (LodMapRenderer.INSTANCE.isCacheOnly()) {
            return; // previewing another dimension: no filter operations
        }
        RenderFilterState.INSTANCE.clear();
        // Rebuild the nodes removed by the filter right away, no voxy disable/enable needed
        RenderNodeRefresh.clearFilterImmediately();
        this.purgeResult = "filtercleared";
    }

    /** Applies all selections (chunk coords -> lvl0 section coords) to the render filter with the given mode. */
    private void applyFilterToSelections(RenderFilterState.Mode mode) {
        if (this.selections.isEmpty() || LodMapRenderer.INSTANCE.isCacheOnly()) {
            return; // previewing another dimension: no filter operations
        }
        List<RectFilter> sectionRects = new ArrayList<>();
        for (SelRect sel : this.selections) {
            sectionRects.add(this.selectionSectionRect(sel.chunkRect()));
        }
        RenderFilterState.INSTANCE.setRects(sectionRects, mode);
        // Sync render nodes immediately: restore columns the old filter removed, then remove/rebuild the newly blocked ones
        RenderNodeRefresh.clearFilterImmediately();
        RenderNodeRefresh.applyFilterImmediately();
    }

    /** Converts a chunk-coord selection to a closed lvl0 section rect (chunk >> 1). */
    private RectFilter selectionSectionRect(RectFilter chunkRect) {
        int x1 = chunkRect.minX() >> 1;
        int z1 = chunkRect.minZ() >> 1;
        int x2 = chunkRect.maxX() >> 1;
        int z2 = chunkRect.maxZ() >> 1;
        return new RectFilter(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    /**
     * Unblocks the selection (section coords): in BLOCK mode the rect is subtracted from the filter
     * (cleared when nothing is left); in ALLOW mode the rect is added back to the allow rect.
     */
    private void unblockSelection() {
        if (LodMapRenderer.INSTANCE.isCacheOnly()) {
            return; // previewing another dimension: no filter operations
        }
        RenderFilterState state = RenderFilterState.INSTANCE;
        for (SelRect sel : this.selections) {
            RectFilter sectionRect = this.selectionSectionRect(sel.chunkRect());
            if (state.getMode() == RenderFilterState.Mode.BLOCK) {
                state.removeRect(sectionRect);
            } else {
                state.addRect(sectionRect);
            }
        }
        // Restore columns the previous filter removed, then remove/rebuild the newly affected ones
        RenderNodeRefresh.clearFilterImmediately();
        RenderNodeRefresh.applyFilterImmediately();
    }

    /**
     * Inverts the filter: with no filter, applies allow-only to the selection;
     * with an existing filter, toggles its polarity (block <-> allow), keeping the rects.
     */
    private void invertFilter() {
        if (!RenderFilterState.INSTANCE.isEnabled()) {
            this.applyFilterToSelections(RenderFilterState.Mode.ALLOW);
            return;
        }
        RenderFilterState.INSTANCE.toggleMode();
        RenderNodeRefresh.clearFilterImmediately();
        RenderNodeRefresh.applyFilterImmediately();
    }

    /** Enter shortcut: blocks rendering of the selection (same as the first context menu item). */
    private void applySelectionToFilter() {
        this.applyFilterToSelections(RenderFilterState.Mode.BLOCK);
    }

    // Right-click context menu

    private void openContextMenu(int mx, int my) {
        if (this.selections.isEmpty()) {
            return;
        }
        this.menuItems.clear();
        // Previewing another dimension: block/allow filter items are disabled, but invert stays
        // available (it toggles the global filter polarity); cache ops remain usable
        if (LodMapRenderer.INSTANCE.isCacheOnly()) {
            this.menuItems.add(new MenuEntry("voxyrenderfilter.map.menu.invert", this::invertFilter));
        } else {
            RenderFilterState state = RenderFilterState.INSTANCE;
            // When every selection is fully blocked, the first item becomes "unblock" instead
            boolean fullyBlocked = true;
            for (SelRect sel : this.selections) {
                if (!state.isRectFullyBlocked(this.selectionSectionRect(sel.chunkRect()))) {
                    fullyBlocked = false;
                    break;
                }
            }
            this.menuItems.add(new MenuEntry(fullyBlocked
                            ? "voxyrenderfilter.map.menu.unblock"
                            : "voxyrenderfilter.map.menu.block",
                    fullyBlocked ? this::unblockSelection
                            : () -> this.applyFilterToSelections(RenderFilterState.Mode.BLOCK)));
            this.menuItems.add(new MenuEntry("voxyrenderfilter.map.menu.allow",
                    () -> this.applyFilterToSelections(RenderFilterState.Mode.ALLOW)));
            this.menuItems.add(new MenuEntry("voxyrenderfilter.map.menu.invert", this::invertFilter));
        }
        this.menuItems.add(new MenuEntry("voxyrenderfilter.map.menu.purge", this::purgeSelection));
        this.menuItems.add(new MenuEntry("voxyrenderfilter.map.menu.clear", this::clearSelection));
        // Size the menu to its content: width = widest label + padding, height = items + separator
        int itemH = 16;
        int pad = 2;
        int textW = 0;
        for (MenuEntry e : this.menuItems) {
            textW = Math.max(textW, this.mc.font.width(Component.translatable(e.label)));
        }
        this.menuW = textW + 18;
        this.menuH = pad * 2 + this.menuItems.size() * itemH + 1;
        // Open at the cursor, flipping inward when it would leave the screen
        this.menuX = Math.max(2, mx + this.menuW > this.width - 2 ? mx - this.menuW : mx);
        this.menuY = Math.max(2, my + this.menuH > this.height - 2 ? my - this.menuH : my);
        this.menuOpen = true;
    }

    /** Index of the selection under the cursor (chunk coords), -1 if none. */
    private int selectionAt(int mx, int my) {
        int cx = this.screenToChunkX(mx);
        int cz = this.screenToChunkZ(my);
        for (int i = 0; i < this.selections.size(); i++) {
            RectFilter r = this.selections.get(i).chunkRect();
            if (r.minX() <= cx && cx <= r.maxX() && r.minZ() <= cz && cz <= r.maxZ()) {
                return i;
            }
        }
        return -1;
    }

    private void purgeSelection() {
        if (this.selections.isEmpty() || this.purgeState == 1) {
            return;
        }
        if (LodMapRenderer.INSTANCE.isCacheOnly()) {
            // Previewing another dimension: only delete that dimension's cached map tiles for the
            // selection; the live world's LOD data must not be touched
            LongOpenHashSet columns = new LongOpenHashSet();
            for (SelRect sel : this.selections) {
                RectFilter r = sel.chunkRect();
                for (int tx = r.minX() >> 5; tx <= r.maxX() >> 5; tx++) {
                    for (int tz = r.minZ() >> 5; tz <= r.maxZ() >> 5; tz++) {
                        columns.add(ActiveTopLevelTracker.pack(tx, tz));
                    }
                }
            }
            if (!columns.isEmpty()) {
                LodMapRenderer.INSTANCE.rescanColumns(columns);
            }
            return;
        }
        WorldEngine engine = VoxyAccess.getCurrentEngine();
        if (engine == null) {
            this.purgeResult = "noengine";
            this.purgeState = 2;
            return;
        }
        // Delete all LOD levels covered by the selection: a coarse LOD section (lvl1-4) spans a larger
        // area that includes the selection, so it must be removed too or the deleted data renders again.
        List<RectFilter> chunkRects = new ArrayList<>();
        List<RectFilter> blockRects = new ArrayList<>();
        for (SelRect sel : this.selections) {
            RectFilter r = sel.chunkRect();
            chunkRects.add(r);
            blockRects.add(new RectFilter(r.minX() << 4, r.minZ() << 4,
                    ((r.maxX() + 1) << 4) - 1, ((r.maxZ() + 1) << 4) - 1));
        }
        this.purgeState = 1;
        CachePurgeService.purge(engine, blockRects, count -> {
            this.purgeResult = String.valueOf(count);
            this.purgeState = 2;
            // Re-ingest neighbor chunks wrongly deleted (inside the lvl0 section, loaded, outside the selection)
            this.restorePurgedNeighbors(chunkRects);
            if (count > 0) {
                // Remove the affected columns' render nodes now (otherwise stale LOD renders until voxy
                // reloads) and rebuild them delayed from the current disk/memory state
                RenderNodeRefresh.refreshForBlockRects(blockRects);
                // The terrain texture holds stale data; invalidate it so the render thread rebuilds from disk
                this.mc.execute(LodMapRenderer.INSTANCE::invalidate);
            }
            // Force a rescan of the disk coverage overlay (ignores an in-flight scan)
            this.cacheIndex.requestScan(VoxyAccess.getCurrentEngine(), true);
        });
    }

    /**
     * A deleted lvl0 section covers 2x2 chunks; chunks outside the selection were deleted by mistake.
     * They cannot be restored from nothing, so re-ingest the loaded ones from the live world (with
     * lighting, full detail, rebuilding coarse LODs upward). Unloaded neighbors regenerate on their
     * own when voxy loads them. World chunks are read on the render thread via {@link Minecraft#execute}.
     */
    private void restorePurgedNeighbors(List<RectFilter> chunkRects) {
        LongOpenHashSet targets = new LongOpenHashSet();
        for (RectFilter s : chunkRects) {
            // chunk -> lvl0 section (chunk >> 1); a section covers chunks [sec*2, sec*2+2)
            for (int secX = s.minX() >> 1; secX <= s.maxX() >> 1; secX++) {
                for (int secZ = s.minZ() >> 1; secZ <= s.maxZ() >> 1; secZ++) {
                    for (int cx = secX << 1; cx <= (secX << 1) + 1; cx++) {
                        for (int cz = secZ << 1; cz <= (secZ << 1) + 1; cz++) {
                            if (!inAnyRect(chunkRects, cx, cz)) {
                                targets.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                            }
                        }
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        this.mc.execute(() -> {
            Level level = this.mc.level;
            if (level == null) {
                return;
            }
            WorldIdentifier id = WorldIdentifier.of(level);
            if (id == null) {
                return;
            }
            LongIterator it = targets.iterator();
            while (it.hasNext()) {
                long packed = it.nextLong();
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                // Only re-ingest loaded chunks; unloaded ones regenerate when voxy loads them
                if (!level.isLoaded(new BlockPos(cx << 4, 0, cz << 4))) {
                    continue;
                }
                try {
                    VoxelIngestService.tryIngestChunk(id, level.getChunk(cx, cz));
                } catch (Exception ignored) {
                    // Engine may be switching; a failed restore is acceptable
                }
            }
        });
    }

    private static boolean inAnyRect(List<RectFilter> rects, int cx, int cz) {
        for (RectFilter r : rects) {
            if (r.minX() <= cx && cx <= r.maxX() && r.minZ() <= cz && cz <= r.maxZ()) {
                return true;
            }
        }
        return false;
    }

    // Rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (this.width == 0 || this.height == 0) {
            return;
        }
        extractor.fill(0, 0, this.width, this.height, (this.backgroundAlpha << 24) | BACKGROUND_RGB);

        // Real LOD terrain map: request the visible columns and draw (bottom layer)
        LodMapRenderer.INSTANCE.update(extractor, VoxyAccess.getCurrentEngine(),
                this.centerTlnX, this.centerTlnZ, this.zoom, this.width, this.height);
        // Overlays are engine-based; hide them while viewing another dimension's cache
        if (this.showOverlays && !LodMapRenderer.INSTANCE.isCacheOnly()) {
            this.drawCachedColumns(extractor);
            this.drawTopLevelColumns(extractor);
        }
        this.drawChunkGrid(extractor);
        this.drawSelections(extractor);
        this.drawRightDragPreview(extractor);
        if (this.showOverlays && !LodMapRenderer.INSTANCE.isCacheOnly()) {
            this.drawFilterBlockOverlay(extractor);
        }
        this.drawPlayer(extractor);
        this.drawButtons(extractor, mouseX, mouseY);
        this.drawViewDistanceInput(extractor, mouseX, mouseY, partialTick);
        this.drawBackgroundAlphaInput(extractor, mouseX, mouseY, partialTick);
        this.drawText(extractor);
        this.drawContextMenu(extractor, mouseX, mouseY);
    }

    /** Draws a faint translucent overlay of the disk LOD cache for this world (finest lvl0 sections only). */
    private void drawCachedColumns(GuiGraphicsExtractor extractor) {
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int z = this.zoom;
        if (z >= CHUNK_SELECT_ZOOM) {
            // High zoom: one tile per lvl0 section (32x32 blocks), sized zoom/16 px
            long[] sections = this.cacheIndex.getSections();
            if (sections.length == 0) {
                return;
            }
            int secPx = Math.max(1, z >> 4);
            for (long packed : sections) {
                int tlnX = (int) (packed >> 32) >> 4;
                int tlnZ = (int) packed >> 4;
                // Skip sections whose column already has a terrain texture, so the overlay does not hide it
                if (LodMapRenderer.INSTANCE.hasUploaded(tlnX, tlnZ)) {
                    continue;
                }
                int sx = (int) Math.floor(((packed >> 32) / 16.0 - this.centerTlnX) * z + halfW);
                int sy = (int) Math.floor(((int) packed / 16.0 - this.centerTlnZ) * z + halfH);
                if (sx + secPx < 0 || sy + secPx < 0 || sx > this.width || sy > this.height) {
                    continue;
                }
                // Blocked sections show orange, cached ones light blue
                extractor.fill(sx, sy, sx + secPx, sy + secPx,
                        RenderFilterState.INSTANCE.isSectionBlocked((int) (packed >> 32), (int) packed)
                                ? BLOCKED_COLOR : CACHED_COLOR);
            }
            return;
        }
        // Low zoom: one tile per TLN column (512x512 blocks)
        long[] columns = this.cacheIndex.getColumns();
        if (columns.length == 0) {
            return;
        }
        for (long packed : columns) {
            int tlnX = (int) (packed >> 32);
            int tlnZ = (int) packed;
            // Skip columns already drawn by the terrain texture
            if (LodMapRenderer.INSTANCE.hasUploaded(tlnX, tlnZ)) {
                continue;
            }
            int sx = (int) Math.floor((tlnX - this.centerTlnX) * z + halfW);
            int sy = (int) Math.floor((tlnZ - this.centerTlnZ) * z + halfH);
            if (sx + z < 0 || sy + z < 0 || sx > this.width || sy > this.height) {
                continue;
            }
            extractor.fill(sx, sy, sx + z, sy + z,
                    RenderFilterState.INSTANCE.isColumnBlocked(tlnX, tlnZ) ? BLOCKED_COLOR : CACHED_COLOR);
        }
    }

    /** Current voxy ring radius in TLN columns (mirrors voxy's setRenderDistance = ceil(sd+1)), or -1 when unavailable. */
    private int ringRadiusColumns() {
        VoxyConfig config = VoxyConfig.CONFIG;
        if (config == null || this.mc.player == null) {
            return -1;
        }
        return (int) Math.ceil(config.sectionRenderDistance + 1);
    }

    /** Whether the column is beyond the current voxy ring radius; hides stale tracker entries while a ring drain is still in progress. */
    private boolean outsideRing(int tlnX, int tlnZ) {
        int ringR = this.ringRadiusColumns();
        if (ringR < 0) {
            return false;
        }
        int pcx = (int) Math.floor(this.mc.player.getX() / 512.0);
        int pcz = (int) Math.floor(this.mc.player.getZ() / 512.0);
        return Math.max(Math.abs(tlnX - pcx), Math.abs(tlnZ - pcz)) > ringR;
    }

    private void drawTopLevelColumns(GuiGraphicsExtractor extractor) {
        LongOpenHashSet columns = ActiveTopLevelTracker.INSTANCE.getColumns();
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int z = this.zoom;
        LongIterator it = columns.iterator();
        this.visibleActiveCount = 0;
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tlnX = ActiveTopLevelTracker.columnX(packed);
            int tlnZ = ActiveTopLevelTracker.columnZ(packed);
            // The render ring is only a coverage range; show columns that have usable data on disk
            if (!this.cacheIndex.containsColumn(tlnX, tlnZ)) {
                continue;
            }
            // Skip columns outside the current ring: after a view distance shrink voxy drains
            // removals incrementally, so the tracker still lists old columns for a moment
            if (this.outsideRing(tlnX, tlnZ)) {
                continue;
            }
            this.visibleActiveCount++;
            int sx = (int) Math.floor((tlnX - this.centerTlnX) * z + halfW);
            int sy = (int) Math.floor((tlnZ - this.centerTlnZ) * z + halfH);
            if (sx + z < 0 || sy + z < 0 || sx > this.width || sy > this.height) {
                continue;
            }
            extractor.fill(sx, sy, sx + z, sy + z, ACTIVE_COLOR);
            if (z >= 4) {
                extractor.fill(sx, sy, sx + 1, sy + z, ACTIVE_BORDER_COLOR);
                extractor.fill(sx, sy, sx + z, sy + 1, ACTIVE_BORDER_COLOR);
            }
        }
    }

    /**
     * Draws chunk (16x16 block) grid lines inside each selection. Lines always follow chunk
     * boundaries regardless of the selection's creation granularity, so a region-file selection
     * shows a 32x32 chunk grid when zoomed in.
     */
    private void drawChunkGrid(GuiGraphicsExtractor extractor) {
        if (this.selections.isEmpty() || this.zoom < CHUNK_GRID_ZOOM) {
            return;
        }
        for (SelRect sel : this.selections) {
            RectFilter s = sel.chunkRect();
            // Chunk boundary spacing: 1 chunk (16x16 blocks)
            int step = 1;
            int linesX = (s.maxX() - s.minX()) / step + 2;
            int linesZ = (s.maxZ() - s.minZ()) / step + 2;
            if (linesX > CHUNK_GRID_MAX_LINES || linesZ > CHUNK_GRID_MAX_LINES) {
                continue;
            }
            int[] rect = this.selectionScreenRect(s);
            if (rect == null) {
                continue;
            }
            int z = this.zoom;
            int halfW = this.width >> 1;
            int halfH = this.height >> 1;
            for (int cx = s.minX(); cx <= s.maxX() + 1; cx += step) {
                int sx = (int) Math.floor((cx / 32.0 - this.centerTlnX) * z + halfW);
                if (sx < 0 || sx > this.width) {
                    continue;
                }
                extractor.fill(sx, rect[1], sx + 1, rect[3], GRID_COLOR);
            }
            for (int cz = s.minZ(); cz <= s.maxZ() + 1; cz += step) {
                int sy = (int) Math.floor((cz / 32.0 - this.centerTlnZ) * z + halfH);
                if (sy < 0 || sy > this.height) {
                    continue;
                }
                extractor.fill(rect[0], sy, rect[2], sy + 1, GRID_COLOR);
            }
        }
    }

    private void drawSelections(GuiGraphicsExtractor extractor) {
        for (SelRect sel : this.selections) {
            this.drawSelection(extractor, sel.chunkRect());
        }
    }

    /** Draws the in-progress right-drag cut (red border over a translucent red fill), the area subtracted on release. */
    private void drawRightDragPreview(GuiGraphicsExtractor extractor) {
        if (!this.rightDragging) {
            return;
        }
        RectFilter r = this.makeSelectionRect(this.rightAnchorX, this.rightAnchorZ, this.rightCurX, this.rightCurZ,
                this.rightSelSectionUnit);
        int[] rect = this.selectionScreenRect(r);
        if (rect == null) {
            return;
        }
        extractor.fill(rect[0] + 1, rect[1] + 1, rect[2] - 1, rect[3] - 1, 0x30FF5555);
        outlineRect(extractor, rect[0], rect[1], rect[2], rect[3], 0xFFFF5555);
    }

    private void drawSelection(GuiGraphicsExtractor extractor, RectFilter s) {
        int[] rect = this.selectionScreenRect(s);
        if (rect == null) {
            return;
        }
        int x1 = rect[0];
        int z1 = rect[1];
        int x2 = rect[2];
        int z2 = rect[3];
        // Translucent interior
        extractor.fill(x1 + 1, z1 + 1, x2 - 1, z2 - 1, 0x2233AAFF);
        // 1px border via four fills with explicit coords (outline's (x,y,w,h) semantics overshoot)
        extractor.fill(x1, z1, x2, z1 + 1, 0xFF33AAFF);
        extractor.fill(x1, z2 - 1, x2, z2, 0xFF33AAFF);
        extractor.fill(x1, z1, x1 + 1, z2, 0xFF33AAFF);
        extractor.fill(x2 - 1, z1, x2, z2, 0xFF33AAFF);
    }

    /**
     * Marks the filtered-out area orange over everything else (uncached terrain included), so the
     * filter scope is visible: inside the rects in BLOCK mode, outside them (screen minus the allow
     * rects) in ALLOW mode. Filter rects are lvl0 sections (32 blocks), scaled as /16 TLN columns.
     */
    private void drawFilterBlockOverlay(GuiGraphicsExtractor extractor) {
        RenderFilterState state = RenderFilterState.INSTANCE;
        if (!state.isEnabled()) {
            return;
        }
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int z = this.zoom;
        if (state.getMode() == RenderFilterState.Mode.ALLOW) {
            // Blocked = voxy ring coverage minus the allow rects. Stop at the ring boundary: columns
            // outside it have no render nodes and must not be covered by the invert overlay
            int[] view = this.ringScreenRect(halfW, halfH, z);
            if (view == null) {
                return;
            }
            List<int[]> blocked = new ArrayList<>();
            blocked.add(view);
            for (RectFilter f : state.getFilters()) {
                int[] r = this.filterScreenRect(f, halfW, halfH, z);
                if (r == null) {
                    continue;
                }
                List<int[]> next = new ArrayList<>();
                subtractScreenRects(blocked, r, next);
                blocked = next;
            }
            for (int[] r : blocked) {
                extractor.fill(r[0], r[1], r[2], r[3], BLOCKED_RECT_COLOR);
            }
            return;
        }
        for (RectFilter f : state.getFilters()) {
            int[] r = this.filterScreenRect(f, halfW, halfH, z);
            if (r == null) {
                continue;
            }
            extractor.fill(r[0], r[1], r[2], r[3], BLOCKED_RECT_COLOR);
        }
    }

    /** Screen rect [x1, z1, x2, z2) (half-open) of a filter rect (section coords), clipped; null when off-screen. */
    private int[] filterScreenRect(RectFilter f, int halfW, int halfH, int z) {
        int x1 = (int) Math.floor((f.minX() / 16.0 - this.centerTlnX) * z + halfW);
        int z1 = (int) Math.floor((f.minZ() / 16.0 - this.centerTlnZ) * z + halfH);
        int x2 = (int) Math.ceil(((f.maxX() + 1.0) / 16.0 - this.centerTlnX) * z + halfW);
        int z2 = (int) Math.ceil(((f.maxZ() + 1.0) / 16.0 - this.centerTlnZ) * z + halfH);
        int cX1 = Math.max(0, Math.min(x1, x2));
        int cX2 = Math.min(this.width, Math.max(x1, x2));
        int cZ1 = Math.max(0, Math.min(z1, z2));
        int cZ2 = Math.min(this.height, Math.max(z1, z2));
        if (cX2 - cX1 < 1 || cZ2 - cZ1 < 1) {
            return null;
        }
        return new int[] {cX1, cZ1, cX2, cZ2};
    }

    /** Screen rect of the voxy render ring [x1, z1, x2, z2), centered on the player at the current radius; null when unavailable. */
    private int[] ringScreenRect(int halfW, int halfH, int z) {
        int ringR = this.ringRadiusColumns();
        if (ringR < 0) {
            return null;
        }
        int pcx = (int) Math.floor(this.mc.player.getX() / 512.0);
        int pcz = (int) Math.floor(this.mc.player.getZ() / 512.0);
        int x1 = (int) Math.floor((pcx - ringR - this.centerTlnX) * z + halfW);
        int z1 = (int) Math.floor((pcz - ringR - this.centerTlnZ) * z + halfH);
        int x2 = (int) Math.ceil(((pcx + ringR + 1.0) - this.centerTlnX) * z + halfW);
        int z2 = (int) Math.ceil(((pcz + ringR + 1.0) - this.centerTlnZ) * z + halfH);
        int cX1 = Math.max(0, Math.min(x1, x2));
        int cX2 = Math.min(this.width, Math.max(x1, x2));
        int cZ1 = Math.max(0, Math.min(z1, z2));
        int cZ2 = Math.min(this.height, Math.max(z1, z2));
        if (cX2 - cX1 < 1 || cZ2 - cZ1 < 1) {
            return null;
        }
        return new int[] {cX1, cZ1, cX2, cZ2};
    }

    /** Screen rect subtraction (half-open): each piece minus b; non-empty results append to out. */
    private static void subtractScreenRects(List<int[]> pieces, int[] b, List<int[]> out) {
        int bx1 = b[0];
        int bz1 = b[1];
        int bx2 = b[2];
        int bz2 = b[3];
        for (int[] p : pieces) {
            int ax1 = p[0];
            int az1 = p[1];
            int ax2 = p[2];
            int az2 = p[3];
            if (ax2 <= bx1 || bx2 <= ax1 || az2 <= bz1 || bz2 <= az1) {
                out.add(p);
                continue;
            }
            if (ax1 < bx1) {
                out.add(new int[] {ax1, az1, bx1, az2});
            }
            if (bx2 < ax2) {
                out.add(new int[] {bx2, az1, ax2, az2});
            }
            int cx1 = Math.max(ax1, bx1);
            int cx2 = Math.min(ax2, bx2);
            if (az1 < bz1) {
                out.add(new int[] {cx1, az1, cx2, bz1});
            }
            if (bz2 < az2) {
                out.add(new int[] {cx1, bz2, cx2, az2});
            }
        }
    }

    /** Draws the context menu (dark panel, hover highlight, separator) near the cursor. */
    private void drawContextMenu(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        if (!this.menuOpen) {
            return;
        }
        this.hoveredMenu = -1;
        int itemH = 16;
        int pad = 2;
        extractor.fill(this.menuX, this.menuY, this.menuX + this.menuW, this.menuY + this.menuH, 0xEE1E1E28);
        outlineRect(extractor, this.menuX, this.menuY,
                this.menuX + this.menuW, this.menuY + this.menuH, 0xFF4A4A5A);
        int y = this.menuY + pad;
        for (int i = 0; i < this.menuItems.size(); i++) {
            if (i == 3) {
                // Separator after the invert item
                extractor.fill(this.menuX + 4, y, this.menuX + this.menuW - 4, y + 1, 0xFF4A4A5A);
                y += 1;
            }
            MenuEntry e = this.menuItems.get(i);
            boolean hovered = mouseX >= this.menuX + 2 && mouseX < this.menuX + this.menuW - 2
                    && mouseY >= y && mouseY < y + itemH;
            if (hovered) {
                this.hoveredMenu = i;
                // Hovered item: background + left highlight bar (colors must be 8-bit ARGB on 26.x)
                extractor.fill(this.menuX + 2, y, this.menuX + this.menuW - 2, y + itemH, 0xFF3A3A48);
                extractor.fill(this.menuX + 2, y, this.menuX + 3, y + itemH, 0xFF33AAFF);
            }
            extractor.text(this.mc.font, Component.translatable(e.label),
                    this.menuX + 8, y + (itemH - 8) / 2, hovered ? 0xFFFFFFFF : 0xFFCCCCCC, false);
            y += itemH;
        }
    }

    private void drawPlayer(GuiGraphicsExtractor extractor) {
        LocalPlayer player = this.mc.player;
        if (player == null) {
            return;
        }
        double px = player.getX();
        double pz = player.getZ();
        if (LodMapRenderer.INSTANCE.isCacheOnly()) {
            // Previewing another dimension: no arrow, except the nether<->overworld cross view,
            // where the position is converted (overworld 8 = nether 1)
            String current = this.currentDimId();
            String viewed = MapCache.INSTANCE.getDimension();
            if ("minecraft_the_nether".equals(current) && "minecraft_overworld".equals(viewed)) {
                px *= 8;
                pz *= 8;
            } else if ("minecraft_overworld".equals(current) && "minecraft_the_nether".equals(viewed)) {
                px /= 8;
                pz /= 8;
            } else {
                return;
            }
        }
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int sx = (int) Math.floor((px / 512.0 - this.centerTlnX) * this.zoom + halfW);
        int sy = (int) Math.floor((pz / 512.0 - this.centerTlnZ) * this.zoom + halfH);
        if (sx < -20 || sy < -20 || sx > this.width + 20 || sy > this.height + 20) {
            return;
        }
        // FTB-Chunks style: fixed-size white arrow pointing along the player's yaw
        PlayerArrowIcon.render(extractor, sx, sy, player.getYRot());
    }

    /** Sanitized dimension id of the current world, e.g. "minecraft_the_nether". */
    private String currentDimId() {
        Level level = this.mc.level;
        if (level == null) {
            return null;
        }
        return level.dimension().identifier().toString().replace(':', '_');
    }

    private void drawButtons(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        for (int i = 0; i < this.buttons.size(); i++) {
            ButtonZone b = this.buttons.get(i);
            boolean hovered = b.contains(mouseX, mouseY);
            extractor.fill(b.x, b.y, b.x + b.w, b.y + b.h, hovered ? 0xFF3A3A48 : 0xFF2A2A35);
            outlineRect(extractor, b.x, b.y, b.x + b.w, b.y + b.h, 0xFF4A4A5A);
            Component text = i == DIM_BUTTON_INDEX ? this.dimLabelComponent() : Component.translatable(b.label);
            extractor.text(this.mc.font, text, b.x + 4, b.y + (b.h - 8) / 2, 0xFFCCCCCC, false);
        }
    }

    /** 1px outline via four fills with explicit coords (outline's (x,y,w,h) semantics mismatch button size). */
    private static void outlineRect(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2, int color) {
        extractor.fill(x1, y1, x2, y1 + 1, color);
        extractor.fill(x1, y2 - 1, x2, y2, color);
        extractor.fill(x1, y1, x1 + 1, y2, color);
        extractor.fill(x2 - 1, y1, x2, y2, color);
    }

    private void drawText(GuiGraphicsExtractor extractor) {
        Font font = this.mc.font;
        int x = 8;
        int y = 4;
        extractor.text(font, Component.translatable("voxyrenderfilter.map.title"), x, y, 0xFFFFFFFF, true);
        y += 12;
        extractor.text(font, Component.translatable("voxyrenderfilter.map.active",
                this.visibleActiveCount), x, y, 0xFFAAAAAA, true);
        y += 12;
        if (this.cacheIndex.isScanning()) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.scanning"), x, y, 0xFFFFAA00, true);
        } else {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.cached",
                    this.cacheIndex.size()), x, y, 0xFFAAAAAA, true);
        }
        y += 12;
        if (LodMapRenderer.INSTANCE.isGenerating()) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.terrain.generating"),
                    x, y, 0xFFFFAA00, true);
            y += 12;
            int[] st = LodMapRenderer.INSTANCE.stats();
            extractor.text(font, Component.translatable("voxyrenderfilter.map.terrain.progress",
                    st[0], st[1], st[2], st[3]), x, y, 0xFF888899, true);
            y += 12;
        }
        extractor.text(font, Component.translatable(
                this.multiSelect ? "voxyrenderfilter.map.mode.multi" : "voxyrenderfilter.map.mode.single"),
                x, y, this.multiSelect ? 0xFFFFB020 : 0xFF888899, true);
        y += 12;
        int shown = 0;
        for (SelRect sel : this.selections) {
            if (shown >= TEXT_MAX_SELECTIONS) {
                extractor.text(font, Component.translatable("voxyrenderfilter.map.selection.more",
                        this.selections.size() - shown), x, y, 0xFF666677, true);
                break;
            }
            shown++;
            RectFilter s = sel.chunkRect();
            // Section selections show lvl0 section coords (chunk >> 1), region ones the region index (chunk >> 5)
            int shift = sel.sectionUnit() ? 1 : 5;
            String unitKey = sel.sectionUnit()
                    ? "voxyrenderfilter.map.selection.section"
                    : "voxyrenderfilter.map.selection.region";
            extractor.text(font, Component.translatable(unitKey,
                    s.minX() >> shift, s.minZ() >> shift, s.maxX() >> shift, s.maxZ() >> shift),
                    x, y, 0xFF22AAFF, true);
            y += 12;
            int chunksX = s.maxX() - s.minX() + 1;
            int chunksZ = s.maxZ() - s.minZ() + 1;
            extractor.text(font, Component.translatable("voxyrenderfilter.map.selection.size",
                    chunksX * 16, chunksZ * 16, chunksX, chunksZ,
                    (chunksX + 1) / 2, (chunksZ + 1) / 2,
                    (chunksX + 31) / 32, (chunksZ + 31) / 32), x, y, 0xFF66CCFF, true);
            y += 12;
        }
        RectFilter filter = RenderFilterState.INSTANCE.getFilter();
        if (filter != null) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.filter.active",
                    filter.minX(), filter.minZ(), filter.maxX(), filter.maxZ(),
                    RenderFilterState.INSTANCE.getBlockedCount()), x, y, 0xFF88FF88, true);
            y += 12;
        }
        if (this.purgeState == 1) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.purging"), x, y, 0xFFFFAA00, true);
        } else if (this.purgeState == 2) {
            switch (this.purgeResult) {
                case "filtercleared" ->
                        extractor.text(font, Component.translatable("voxyrenderfilter.map.filter.cleared"), x, y, 0xFF22DD44, true);
                case "-1" ->
                        extractor.text(font, Component.translatable("voxyrenderfilter.map.purge.failed"), x, y, 0xFFFF4444, true);
                case "noengine" ->
                        extractor.text(font, Component.translatable("voxyrenderfilter.map.noengine"), x, y, 0xFFFF4444, true);
                case null, default ->
                        extractor.text(font, Component.translatable("voxyrenderfilter.map.purged", this.purgeResult),
                                x, y, 0xFF22DD44, true);
            }
        }
        extractor.text(font, Component.translatable("voxyrenderfilter.map.help"),
                8, this.height - 16, 0xFF666677, true);
    }

    /** Screen rect of a selection (chunk coords), clipped; null when off-screen. */
    private int[] selectionScreenRect(RectFilter s) {
        int z = this.zoom;
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int x1 = (int) Math.floor((s.minX() / 32.0 - this.centerTlnX) * z + halfW);
        int z1 = (int) Math.floor((s.minZ() / 32.0 - this.centerTlnZ) * z + halfH);
        int x2 = (int) Math.ceil(((s.maxX() + 1.0) / 32.0 - this.centerTlnX) * z + halfW);
        int z2 = (int) Math.ceil(((s.maxZ() + 1.0) / 32.0 - this.centerTlnZ) * z + halfH);
        int minX = Math.max(0, Math.min(x1, x2));
        int maxX = Math.min(this.width, Math.max(x1, x2));
        int minZ = Math.max(0, Math.min(z1, z2));
        int maxZ = Math.min(this.height, Math.max(z1, z2));
        if (maxX - minX < 1 || maxZ - minZ < 1) {
            return null;
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    // Input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bool) {
        super.mouseClicked(event, bool);
        int button = event.buttonInfo().button();
        int mx = (int) event.x();
        int my = (int) event.y();
        // Menu open: clicking an item runs it and closes; clicking outside closes
        if (this.menuOpen) {
            if (mx >= this.menuX && mx < this.menuX + this.menuW
                    && my >= this.menuY && my < this.menuY + this.menuH) {
                int idx = this.menuItemAt(my);
                this.menuOpen = false;
                if (idx >= 0 && idx < this.menuItems.size()) {
                    this.menuItems.get(idx).action().run();
                }
                return true;
            }
            this.menuOpen = false;
        }
        if (button == 0) {
            boolean onViewBox = this.handleBoxClick(this.viewDistanceBox, mx, my, event);
            boolean onAlphaBox = this.handleBoxClick(this.backgroundAlphaBox, mx, my, event);
            if (onViewBox || onAlphaBox) {
                return true;
            }
            for (ButtonZone b : this.buttons) {
                if (b.contains(mx, my)) {
                    b.action().run();
                    return true;
                }
            }
            if (!this.selectionMode) {
                // Selection off: left-drag pans the map instead of drawing a box
                this.panning = true;
                return true;
            }
            this.dragging = true;
            this.selSectionUnit = this.zoom >= CHUNK_SELECT_ZOOM;
            int[] p = this.snapToUnit(mx, my, this.selSectionUnit);
            this.selAnchorX = p[0];
            this.selAnchorZ = p[1];
            if (!this.multiSelect) {
                this.selections.clear();
            }
            this.selections.add(new SelRect(this.makeSelectionRect(this.selAnchorX, this.selAnchorZ, this.selSectionUnit),
                    this.selSectionUnit));
            return true;
        }
        if (button == 1) {
            // Right-drag subtracts a region from the existing selection; a plain right-click
            // (no movement before release) opens the menu on a selection or clears empty space.
            // The click/drag decision is made on release.
            this.rightDragging = true;
            this.rightSelSectionUnit = this.zoom >= CHUNK_SELECT_ZOOM;
            int[] p = this.snapToUnit(mx, my, this.rightSelSectionUnit);
            this.rightAnchorX = p[0];
            this.rightAnchorZ = p[1];
            this.rightCurX = p[0];
            this.rightCurZ = p[1];
            return true;
        }
        return false;
    }

    /** Menu Y -> item index (same layout as {@link #drawContextMenu}, including the separator); -1 when not on an item. */
    private int menuItemAt(int my) {
        int y = this.menuY + 2;
        for (int i = 0; i < this.menuItems.size(); i++) {
            if (i == 3) {
                y += 1;
            }
            if (my >= y && my < y + 16) {
                return i;
            }
            y += 16;
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.rightDragging) {
            int[] p = this.snapToUnit(event.x(), event.y(), this.rightSelSectionUnit);
            this.rightCurX = p[0];
            this.rightCurZ = p[1];
            return true;
        }
        if (this.panning) {
            // Grab-and-pull panning: content follows the cursor (1 screen px = 1/zoom TLN columns)
            this.centerTlnX -= dx / this.zoom;
            this.centerTlnZ -= dy / this.zoom;
            return true;
        }
        if (this.dragging) {
            SelRect last = this.selections.get(this.selections.size() - 1);
            int[] p = this.snapToUnit(event.x(), event.y(), last.sectionUnit());
            int cx = p[0];
            int cz = p[1];
            if (event.hasShiftDown()) {
                // Shift: square selection anchored on the start, side = larger axis span
                int dxc = cx - this.selAnchorX;
                int dzc = cz - this.selAnchorZ;
                int side = Math.max(Math.abs(dxc), Math.abs(dzc));
                cx = this.selAnchorX + Integer.signum(dxc) * side;
                cz = this.selAnchorZ + Integer.signum(dzc) * side;
            }
            this.selections.set(this.selections.size() - 1,
                    new SelRect(this.makeSelectionRect(cx, cz, last.sectionUnit()), last.sectionUnit()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean boxFinished = this.dragging;
        this.dragging = false;
        this.panning = false;
        if (boxFinished) {
            this.normalizeSelections();
        }
        if (this.rightDragging) {
            this.rightDragging = false;
            int[] p = this.snapToUnit(event.x(), event.y(), this.rightSelSectionUnit);
            int ex = p[0];
            int ez = p[1];
            // Movement threshold: a click keeps the anchor, a drag subtracts the covered area
            if (ex != this.rightAnchorX || ez != this.rightAnchorZ) {
                this.subtractSelection(this.makeSelectionRect(this.rightAnchorX, this.rightAnchorZ, ex, ez,
                        this.rightSelSectionUnit));
            } else if (this.selectionAt((int) event.x(), (int) event.y()) >= 0) {
                // Plain right-click on a selection opens the menu; on empty space it clears
                this.openContextMenu((int) event.x(), (int) event.y());
            } else {
                this.clearSelection();
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    /**
     * Cuts the given chunk-coord rect out of every selection: each selection becomes the set of
     * pieces left after subtracting it (rect difference); pieces fully inside the cut disappear.
     * Unit (section/region) of each remaining piece is preserved.
     */
    private void subtractSelection(RectFilter cut) {
        if (this.selections.isEmpty()) {
            return;
        }
        List<SelRect> result = new ArrayList<>();
        for (SelRect sel : this.selections) {
            for (RectFilter piece : subtractRects(sel.chunkRect(), cut)) {
                result.add(new SelRect(piece, sel.sectionUnit()));
            }
        }
        this.selections.clear();
        this.selections.addAll(result);
    }

    /** Rect difference a minus b (inclusive chunk rects); non-empty pieces are appended to out. */
    private static List<RectFilter> subtractRects(RectFilter a, RectFilter b) {
        List<RectFilter> out = new ArrayList<>();
        // Half-open intervals [min, max+1)
        int ax1 = a.minX(), az1 = a.minZ(), ax2 = a.maxX() + 1, az2 = a.maxZ() + 1;
        int bx1 = b.minX(), bz1 = b.minZ(), bx2 = b.maxX() + 1, bz2 = b.maxZ() + 1;
        if (ax2 <= bx1 || bx2 <= ax1 || az2 <= bz1 || bz2 <= az1) {
            out.add(a);
            return out;
        }
        if (ax1 < bx1) {
            out.add(new RectFilter(ax1, az1, bx1 - 1, az2 - 1));
        }
        if (bx2 < ax2) {
            out.add(new RectFilter(bx2, az1, ax2 - 1, az2 - 1));
        }
        int cx1 = Math.max(ax1, bx1);
        int cx2 = Math.min(ax2, bx2);
        if (az1 < bz1) {
            out.add(new RectFilter(cx1, az1, cx2 - 1, bz1 - 1));
        }
        if (bz2 < az2) {
            out.add(new RectFilter(cx1, bz2, cx2 - 1, az2 - 1));
        }
        return out;
    }

    /**
     * Normalizes the selection list into pairwise-disjoint rectangles: overlapping selections are
     * unioned and re-partitioned via coordinate compression, so no area is covered twice. Pieces
     * covering any region-unit (coarse) cell become region-unit; all-section pieces stay
     * section-unit. Called when a left-drag selection finishes; subtract keeps disjointness, so
     * the invariant holds afterwards.
     */
    private void normalizeSelections() {
        if (this.selections.size() < 2) {
            return;
        }
        int count = this.selections.size();
        int[] xs = new int[count * 2];
        int[] zs = new int[count * 2];
        int k = 0;
        for (SelRect sel : this.selections) {
            xs[k] = sel.chunkRect().minX();
            xs[k + 1] = sel.chunkRect().maxX() + 1;
            zs[k] = sel.chunkRect().minZ();
            zs[k + 1] = sel.chunkRect().maxZ() + 1;
            k += 2;
        }
        Arrays.sort(xs);
        Arrays.sort(zs);
        int nx = 0;
        for (int v : xs) {
            if (nx == 0 || xs[nx - 1] != v) {
                xs[nx++] = v;
            }
        }
        int nz = 0;
        for (int v : zs) {
            if (nz == 0 || zs[nz - 1] != v) {
                zs[nz++] = v;
            }
        }
        boolean[][] covered = new boolean[nx - 1][nz - 1];
        boolean[][] regionCell = new boolean[nx - 1][nz - 1];
        for (SelRect sel : this.selections) {
            RectFilter r = sel.chunkRect();
            int xi0 = Arrays.binarySearch(xs, 0, nx, r.minX());
            int xi1 = Arrays.binarySearch(xs, 0, nx, r.maxX() + 1);
            int zi0 = Arrays.binarySearch(zs, 0, nz, r.minZ());
            int zi1 = Arrays.binarySearch(zs, 0, nz, r.maxZ() + 1);
            for (int i = xi0; i < xi1; i++) {
                for (int j = zi0; j < zi1; j++) {
                    covered[i][j] = true;
                    if (!sel.sectionUnit()) {
                        regionCell[i][j] = true;
                    }
                }
            }
        }
        List<SelRect> merged = new ArrayList<>();
        for (int j = 0; j < nz - 1; j++) {
            for (int i = 0; i < nx - 1; i++) {
                if (!covered[i][j]) {
                    continue;
                }
                // Maximal covered x-run in this z-band, extended down while the identical span
                // stays covered; consumed cells are cleared so lower bands scan fresh
                int i2 = i;
                while (i2 + 1 < nx - 1 && covered[i2 + 1][j]) {
                    i2++;
                }
                int j2 = j;
                extendDown:
                while (j2 + 1 < nz - 1) {
                    for (int t = i; t <= i2; t++) {
                        if (!covered[t][j2 + 1]) {
                            break extendDown;
                        }
                    }
                    j2++;
                }
                boolean anyRegion = false;
                for (int t = i; t <= i2 && !anyRegion; t++) {
                    for (int u = j; u <= j2; u++) {
                        if (regionCell[t][u]) {
                            anyRegion = true;
                            break;
                        }
                    }
                }
                for (int t = i; t <= i2; t++) {
                    for (int u = j; u <= j2; u++) {
                        covered[t][u] = false;
                    }
                }
                merged.add(new SelRect(
                        new RectFilter(xs[i], zs[j], xs[i2 + 1] - 1, zs[j2 + 1] - 1),
                        !anyRegion));
                i = i2;
            }
        }
        this.selections.clear();
        this.selections.addAll(merged);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double wx, double wy) {
        if (wy != 0) {
            // Zoom anchored on the continuous world coords under the cursor (fractional TLN columns)
            double beforeX = (mouseX - this.width / 2.0) / this.zoom + this.centerTlnX;
            double beforeZ = (mouseY - this.height / 2.0) / this.zoom + this.centerTlnZ;
            int newZoom = getNewZoom(wy);
            if (newZoom != this.zoom) {
                this.zoom = newZoom;
                this.centerTlnX = beforeX - (mouseX - this.width / 2.0) / this.zoom;
                this.centerTlnZ = beforeZ - (mouseY - this.height / 2.0) / this.zoom;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, wx, wy);
    }

    private int getNewZoom(double wy) {
        int newZoom = (int) Math.round(this.zoom * (wy > 0 ? 1.5 : 1 / 1.5));
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        // In the chunk-grid zoom range, snap zoom to multiples of 32 so each chunk cell (z/32) is
        // always an integer pixel count and the grid stays even; below it no grid is drawn
        if (newZoom >= CHUNK_SELECT_ZOOM) {
            newZoom = Math.max(CHUNK_SELECT_ZOOM,
                    Math.min(MAX_ZOOM, Math.round(newZoom / 32f) * 32));
        }
        return newZoom;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (this.handleFocusedBoxKey(this.viewDistanceBox, key, event)
                || this.handleFocusedBoxKey(this.backgroundAlphaBox, key, event)) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            this.multiSelect = !this.multiSelect;
            if (!this.multiSelect && this.selections.size() > 1) {
                // Leaving multi-select keeps only the last box drawn
                SelRect last = this.selections.get(this.selections.size() - 1);
                this.selections.clear();
                this.selections.add(last);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            this.applySelectionToFilter();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
            this.purgeSelection();
            return true;
        }
        if (key == GLFW.GLFW_KEY_C) {
            this.clearSelection();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) {
            this.rescanSelection();
            return true;
        }
        if (key == GLFW.GLFW_KEY_H || key == GLFW.GLFW_KEY_HOME) {
            this.centerOnPlayer();
            return true;
        }
        if (key == GLFW.GLFW_KEY_G) {
            this.toggleSelectionMode();
            return true;
        }
        double pan = this.width * PAN_FRACTION / this.zoom; // in TLN columns, scales with zoom
        if (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_UP) {
            this.centerTlnZ -= pan;
            return true;
        }
        if (key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_DOWN) {
            this.centerTlnZ += pan;
            return true;
        }
        if (key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_LEFT) {
            this.centerTlnX -= pan;
            return true;
        }
        if (key == GLFW.GLFW_KEY_D || key == GLFW.GLFW_KEY_RIGHT) {
            this.centerTlnX += pan;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        EditBox box = this.viewDistanceBox != null && this.viewDistanceBox.isFocused() ? this.viewDistanceBox
                : this.backgroundAlphaBox != null && this.backgroundAlphaBox.isFocused() ? this.backgroundAlphaBox : null;
        return box != null && box.charTyped(event);
    }

    /**
     * Screen coords -> chunk coords snapped to the selection granularity:
     * sectionUnit = lvl0 section (2-chunk alignment), otherwise region file (32-chunk alignment).
     * Uses floor, correct for negative coords.
     */
    private int[] snapToUnit(double sx, double sy, boolean sectionUnit) {
        int cx = this.screenToChunkX(sx);
        int cz = this.screenToChunkZ(sy);
        if (sectionUnit) {
            // lvl0 section granularity: 2-chunk alignment
            cx = (cx >> 1) << 1;
            cz = (cz >> 1) << 1;
        } else {
            // Region file granularity: 32-chunk alignment
            cx = (cx >> 5) << 5;
            cz = (cz >> 5) << 5;
        }
        return new int[] {cx, cz};
    }

    private RectFilter makeSelectionRect(int cx, int cz, boolean sectionUnit) {
        return this.makeSelectionRect(this.selAnchorX, this.selAnchorZ, cx, cz, sectionUnit);
    }

    private RectFilter makeSelectionRect(int ax, int az, int cx, int cz, boolean sectionUnit) {
        int minX = Math.min(ax, cx);
        int maxX = Math.max(ax, cx);
        int minZ = Math.min(az, cz);
        int maxZ = Math.max(az, cz);
        if (sectionUnit) {
            maxX += 1;
            maxZ += 1;
        } else {
            maxX += 31;
            maxZ += 31;
        }
        return new RectFilter(minX, minZ, maxX, maxZ);
    }

    /** Screen X -> chunk index under the cursor (16-block units). */
    private int screenToChunkX(double sx) {
        return (int) Math.floor(((sx - this.width / 2.0) / this.zoom + this.centerTlnX) * 32.0);
    }

    private int screenToChunkZ(double sy) {
        return (int) Math.floor(((sy - this.height / 2.0) / this.zoom + this.centerTlnZ) * 32.0);
    }
}
