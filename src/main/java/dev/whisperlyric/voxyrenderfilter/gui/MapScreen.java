package dev.whisperlyric.voxyrenderfilter.gui;

import dev.whisperlyric.voxyrenderfilter.filter.RectFilter;
import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import dev.whisperlyric.voxyrenderfilter.index.CacheCoverageIndex;
import dev.whisperlyric.voxyrenderfilter.purge.CachePurgeService;
import dev.whisperlyric.voxyrenderfilter.tracker.ActiveTopLevelTracker;
import dev.whisperlyric.voxyrenderfilter.util.VoxyAccess;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存地图（小地图风格，类似 FTB Chunks / Xaero）：
 * <ul>
 *   <li>淡色 = 此世界磁盘中已缓存的 LOD 区块（后台扫描存储文件，按 TLN 列 512×512 方块聚合）</li>
 *   <li>绿色 = voxy 当前 LOD 渲染中的顶层区块（实时，随视野移动更新）</li>
 *   <li>左键拖拽框选、右键取消、滚轮缩放（以光标为中心）、WASD/方向键平移</li>
 *   <li>缩放可到单区块级别（最大 4096px / TLN 列 = 128px / chunk）；选区始终按 chunk(16 方块) 粒度吸附</li>
 *   <li>选区显示方块/chunk/region 尺寸；放大后选区内叠加 chunk 网格线</li>
 * </ul>
 */
public class MapScreen extends Screen {

    private static final int MIN_ZOOM = 2;
    /** 最大缩放：px / TLN 列（512 方块）。4096 = 每 chunk 128px，可看清单个区块。 */
    private static final int MAX_ZOOM = 4096;
    /** 每次平移为当前可见宽度的比例（随缩放自适应）。 */
    private static final double PAN_FRACTION = 0.5;
    /** 缩放达到该值（每 chunk ≥ 4px）时在选区内绘制 chunk 网格。 */
    private static final int CHUNK_GRID_ZOOM = 128;
    /** 选区内每轴最多绘制的 chunk 网格线数量，避免极端选区下每帧绘制过多线段。 */
    private static final int CHUNK_GRID_MAX_LINES = 256;
    /** 磁盘缓存列的淡色覆盖。 */
    private static final int CACHED_COLOR = 0x50A0B8C8;

    private record ButtonZone(int x, int y, int w, int h, String label, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    private final Minecraft mc;

    /** 视图中心，单位 = TLN 列（可带小数，即 512 方块粒度的连续坐标）。 */
    private double centerTlnX;
    private double centerTlnZ;
    private int zoom = 8;

    /** 选区，单位 = chunk（16 方块），min/max 均为 chunk 序号（含端点）。 */
    private RectFilter selection;
    private int selAnchorX;
    private int selAnchorZ;
    private boolean dragging;

    private volatile int purgeState;      // 0 idle, 1 running, 2 finished
    private volatile String purgeResult = "";

    private final CacheCoverageIndex cacheIndex = new CacheCoverageIndex(null);
    private final List<ButtonZone> buttons = new ArrayList<>();

    public MapScreen() {
        super(Component.translatable("voxyrenderfilter.map.title"));
        this.mc = Minecraft.getInstance();
    }

    @Override
    protected void init() {
        this.centerOnPlayer();
        this.requestScan();
        this.buttons.clear();
        int x = this.width - 112;
        this.buttons.add(new ButtonZone(x, 4, 108, 18, "voxyrenderfilter.map.button.filter", this::applySelectionToFilter));
        this.buttons.add(new ButtonZone(x, 26, 108, 18, "voxyrenderfilter.map.button.purge", this::purgeSelection));
        this.buttons.add(new ButtonZone(x, 48, 108, 18, "voxyrenderfilter.map.button.clear", this::clearSelection));
        this.buttons.add(new ButtonZone(x, 70, 108, 18, "voxyrenderfilter.map.button.rescan", this::requestScan));
        this.buttons.add(new ButtonZone(x, 92, 108, 18, "voxyrenderfilter.map.button.center", this::centerOnPlayer));
    }

    private void requestScan() {
        this.cacheIndex.requestScan(VoxyAccess.getCurrentEngine());
    }

    private void centerOnPlayer() {
        LocalPlayer player = this.mc.player;
        if (player != null) {
            // 用精确的小数坐标使玩家标记始终位于屏幕中心
            this.centerTlnX = player.getX() / 512.0;
            this.centerTlnZ = player.getZ() / 512.0;
        }
    }

    private void clearSelection() {
        this.selection = null;
    }

    /** 选区（chunk 坐标）-> 方块坐标 -> 渲染过滤（自动转为 TLN 列）。 */
    private void applySelectionToFilter() {
        if (this.selection == null) {
            return;
        }
        RenderFilterState.INSTANCE.setRect(
                this.selection.minX() << 4, this.selection.minZ() << 4,
                ((this.selection.maxX() + 1) << 4) - 1, ((this.selection.maxZ() + 1) << 4) - 1);
        this.mc.setScreen(null);
    }

    private void purgeSelection() {
        if (this.selection == null || this.purgeState == 1) {
            return;
        }
        WorldEngine engine = VoxyAccess.getCurrentEngine();
        if (engine == null) {
            this.purgeResult = "noengine";
            this.purgeState = 2;
            return;
        }
        // 删除粒度受限于 TLN 列（region 文件），chunk 选区 -> 覆盖到的列区间
        RectFilter tlnRect = new RectFilter(
                this.selection.minX() >> 5, this.selection.minZ() >> 5,
                this.selection.maxX() >> 5, this.selection.maxZ() >> 5);
        this.purgeState = 1;
        CachePurgeService.purge(engine, tlnRect, count -> {
            this.purgeResult = String.valueOf(count);
            this.purgeState = 2;
            // 磁盘变化后重新扫描，刷新淡色覆盖层
            this.requestScan();
        });
    }

    //===================================================================================
    // 渲染
    //===================================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (this.width == 0 || this.height == 0) {
            return;
        }
        extractor.fill(0, 0, this.width, this.height, 0xFF15151D);

        this.drawCachedColumns(extractor);
        this.drawTopLevelColumns(extractor);
        this.drawChunkGrid(extractor);
        this.drawSelection(extractor);
        this.drawPlayer(extractor);
        this.drawButtons(extractor, mouseX, mouseY);
        this.drawText(extractor);
    }

    /** 磁盘中已缓存的 LOD 区块（此世界全部缓存），以淡色半透明覆盖显示。 */
    private void drawCachedColumns(GuiGraphicsExtractor extractor) {
        long[] columns = this.cacheIndex.getColumns();
        if (columns.length == 0) {
            return;
        }
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int z = this.zoom;
        for (long packed : columns) {
            int sx = (int) Math.floor(((packed >> 32) - this.centerTlnX) * z + halfW);
            int sy = (int) Math.floor(((int) packed - this.centerTlnZ) * z + halfH);
            if (sx + z < 0 || sy + z < 0 || sx > this.width || sy > this.height) {
                continue;
            }
            extractor.fill(sx, sy, sx + z, sy + z, CACHED_COLOR);
        }
    }

    private void drawTopLevelColumns(GuiGraphicsExtractor extractor) {
        LongOpenHashSet columns = ActiveTopLevelTracker.INSTANCE.getColumns();
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int z = this.zoom;
        LongIterator it = columns.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int sx = (int) Math.floor((ActiveTopLevelTracker.columnX(packed) - this.centerTlnX) * z + halfW);
            int sy = (int) Math.floor((ActiveTopLevelTracker.columnZ(packed) - this.centerTlnZ) * z + halfH);
            if (sx + z < 0 || sy + z < 0 || sx > this.width || sy > this.height) {
                continue;
            }
            extractor.fill(sx, sy, sx + z, sy + z, 0xFF2F9E4F);
            if (z >= 4) {
                extractor.fill(sx, sy, sx + 1, sy + z, 0xFF1B5E2F);
                extractor.fill(sx, sy, sx + z, sy + 1, 0xFF1B5E2F);
            }
        }
    }

    /** 选区内按 chunk 边界绘制网格线（1 TLN 列 = 32 chunks）。 */
    private void drawChunkGrid(GuiGraphicsExtractor extractor) {
        if (this.selection == null || this.zoom < CHUNK_GRID_ZOOM) {
            return;
        }
        int linesX = this.selection.maxX() - this.selection.minX() + 2;
        int linesZ = this.selection.maxZ() - this.selection.minZ() + 2;
        if (linesX > CHUNK_GRID_MAX_LINES || linesZ > CHUNK_GRID_MAX_LINES) {
            return;
        }
        int[] rect = this.selectionScreenRect();
        if (rect == null) {
            return;
        }
        int z = this.zoom;
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        for (int cx = this.selection.minX(); cx <= this.selection.maxX() + 1; cx++) {
            int sx = (int) Math.floor((cx / 32.0 - this.centerTlnX) * z + halfW);
            if (sx < 0 || sx > this.width) {
                continue;
            }
            extractor.fill(sx, rect[1], sx + 1, rect[3], 0xFF3A3A4A);
        }
        for (int cz = this.selection.minZ(); cz <= this.selection.maxZ() + 1; cz++) {
            int sy = (int) Math.floor((cz / 32.0 - this.centerTlnZ) * z + halfH);
            if (sy < 0 || sy > this.height) {
                continue;
            }
            extractor.fill(rect[0], sy, rect[2], sy + 1, 0xFF3A3A4A);
        }
    }

    private void drawSelection(GuiGraphicsExtractor extractor) {
        if (this.selection == null) {
            return;
        }
        int[] rect = this.selectionScreenRect();
        if (rect == null) {
            return;
        }
        int x1 = rect[0];
        int z1 = rect[1];
        int x2 = rect[2];
        int z2 = rect[3];
        // 内部半透明
        extractor.fill(x1 + 1, z1 + 1, x2 - 1, z2 - 1, 0x2233AAFF);
        // 四条 1px 边框（用 fill 明确坐标，避免 outline 的绘制语义问题）
        extractor.fill(x1, z1, x2, z1 + 1, 0xFF33AAFF);
        extractor.fill(x1, z2 - 1, x2, z2, 0xFF33AAFF);
        extractor.fill(x1, z1, x1 + 1, z2, 0xFF33AAFF);
        extractor.fill(x2 - 1, z1, x2, z2, 0xFF33AAFF);
    }

    private void drawPlayer(GuiGraphicsExtractor extractor) {
        LocalPlayer player = this.mc.player;
        if (player == null) {
            return;
        }
        int z = this.zoom;
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int sx = (int) Math.round((player.getX() / 512.0 - this.centerTlnX) * z + halfW);
        int sy = (int) Math.round((player.getZ() / 512.0 - this.centerTlnZ) * z + halfH);
        if (sx < -8 || sx > this.width + 8 || sy < -8 || sy > this.height + 8) {
            return;
        }
        // 玩家标记（白色 3x3，中心黑点）
        extractor.fill(sx - 1, sy - 1, sx + 2, sy + 2, 0xFFFFFFFF);
        extractor.fill(sx, sy, sx + 1, sy + 1, 0xFF000000);
        // 朝向箭头（沿 yaw 方向延伸）
        double yawRad = Math.toRadians(player.getYRot());
        double dx = -Math.sin(yawRad);
        double dz = Math.cos(yawRad);
        int len = Math.max(4, z / 2);
        for (int i = 3; i <= len; i++) {
            int px = sx + (int) Math.round(dx * i);
            int py = sy + (int) Math.round(dz * i);
            extractor.fill(px, py, px + 1, py + 1, 0xFFFFFFFF);
        }
    }

    private void drawButtons(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        for (ButtonZone b : this.buttons) {
            boolean hovered = b.contains(mouseX, mouseY);
            extractor.fill(b.x, b.y, b.x + b.w, b.y + b.h, hovered ? 0xFF3A3A48 : 0xFF2A2A35);
            extractor.outline(b.x, b.y, b.x + b.w, b.y + b.h, 0xFF4A4A5A);
            extractor.text(this.mc.font, Component.translatable(b.label),
                    b.x + 4, b.y + (b.h - 8) / 2, 0xFFCCCCCC, false);
        }
    }

    private void drawText(GuiGraphicsExtractor extractor) {
        Font font = this.mc.font;
        int x = 8;
        int y = 4;
        extractor.text(font, Component.translatable("voxyrenderfilter.map.title"), x, y, 0xFFFFFFFF, true);
        y += 12;
        extractor.text(font, Component.translatable("voxyrenderfilter.map.active",
                ActiveTopLevelTracker.INSTANCE.size()), x, y, 0xFFAAAAAA, true);
        y += 12;
        if (this.cacheIndex.isScanning()) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.scanning"), x, y, 0xFFFFAA00, true);
        } else {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.cached",
                    this.cacheIndex.size()), x, y, 0xFFAAAAAA, true);
        }
        y += 12;
        if (this.selection != null) {
            int chunksX = this.selection.maxX() - this.selection.minX() + 1;
            int chunksZ = this.selection.maxZ() - this.selection.minZ() + 1;
            extractor.text(font, Component.translatable("voxyrenderfilter.map.selection",
                    this.selection.minX(), this.selection.minZ(), this.selection.maxX(), this.selection.maxZ()),
                    x, y, 0xFF22AAFF, true);
            y += 12;
            extractor.text(font, Component.translatable("voxyrenderfilter.map.selection.size",
                    chunksX * 16, chunksZ * 16, chunksX, chunksZ,
                    (chunksX + 31) / 32, (chunksZ + 31) / 32), x, y, 0xFF66CCFF, true);
            y += 12;
        }
        RectFilter filter = RenderFilterState.INSTANCE.getFilter();
        if (filter != null) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.filter.active",
                    filter.minX(), filter.minZ(), filter.maxX(), filter.maxZ()), x, y, 0xFF88FF88, true);
            y += 12;
        }
        if (this.purgeState == 1) {
            extractor.text(font, Component.translatable("voxyrenderfilter.map.purging"), x, y, 0xFFFFAA00, true);
        } else if (this.purgeState == 2) {
            if ("-1".equals(this.purgeResult)) {
                extractor.text(font, Component.translatable("voxyrenderfilter.map.purge.failed"), x, y, 0xFFFF4444, true);
            } else if ("noengine".equals(this.purgeResult)) {
                extractor.text(font, Component.translatable("voxyrenderfilter.map.noengine"), x, y, 0xFFFF4444, true);
            } else {
                extractor.text(font, Component.translatable("voxyrenderfilter.map.purged", this.purgeResult),
                        x, y, 0xFF22DD44, true);
            }
        }
        extractor.text(font, Component.translatable("voxyrenderfilter.map.help"),
                8, this.height - 16, 0xFF666677, true);
    }

    /** 选区（chunk 坐标）在屏幕上的裁剪矩形 [x1, z1, x2, z2]，不可见时返回 null。 */
    private int[] selectionScreenRect() {
        int z = this.zoom;
        int halfW = this.width >> 1;
        int halfH = this.height >> 1;
        int x1 = (int) Math.floor((this.selection.minX() / 32.0 - this.centerTlnX) * z + halfW);
        int z1 = (int) Math.floor((this.selection.minZ() / 32.0 - this.centerTlnZ) * z + halfH);
        int x2 = (int) Math.ceil(((this.selection.maxX() + 1.0) / 32.0 - this.centerTlnX) * z + halfW);
        int z2 = (int) Math.ceil(((this.selection.maxZ() + 1.0) / 32.0 - this.centerTlnZ) * z + halfH);
        int minX = Math.max(0, Math.min(x1, x2));
        int maxX = Math.min(this.width, Math.max(x1, x2));
        int minZ = Math.max(0, Math.min(z1, z2));
        int maxZ = Math.min(this.height, Math.max(z1, z2));
        if (maxX - minX < 1 || maxZ - minZ < 1) {
            return null;
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    //===================================================================================
    // 输入
    //===================================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean bool) {
        super.mouseClicked(event, bool);
        int button = event.buttonInfo().button();
        if (button == 0) {
            for (ButtonZone b : this.buttons) {
                if (b.contains(event.x(), event.y())) {
                    b.action().run();
                    return true;
                }
            }
            this.dragging = true;
            this.selAnchorX = this.screenToChunkX(event.x());
            this.selAnchorZ = this.screenToChunkZ(event.y());
            this.selection = new RectFilter(this.selAnchorX, this.selAnchorZ, this.selAnchorX, this.selAnchorZ);
            return true;
        }
        if (button == 1) {
            this.clearSelection();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.dragging) {
            int cx = this.screenToChunkX(event.x());
            int cz = this.screenToChunkZ(event.y());
            this.selection = new RectFilter(
                    Math.min(this.selAnchorX, cx), Math.min(this.selAnchorZ, cz),
                    Math.max(this.selAnchorX, cx), Math.max(this.selAnchorZ, cz));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double wx, double wy) {
        if (wy != 0) {
            // 以光标下的连续世界坐标（TLN 列，可带小数）为缩放锚点
            double beforeX = (mouseX - this.width / 2.0) / this.zoom + this.centerTlnX;
            double beforeZ = (mouseY - this.height / 2.0) / this.zoom + this.centerTlnZ;
            int newZoom = (int) Math.round(this.zoom * (wy > 0 ? 1.5 : 1 / 1.5));
            newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
            if (newZoom != this.zoom) {
                this.zoom = newZoom;
                this.centerTlnX = beforeX - (mouseX - this.width / 2.0) / this.zoom;
                this.centerTlnZ = beforeZ - (mouseY - this.height / 2.0) / this.zoom;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, wx, wy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
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
            this.requestScan();
            return true;
        }
        if (key == GLFW.GLFW_KEY_H || key == GLFW.GLFW_KEY_HOME) {
            this.centerOnPlayer();
            return true;
        }
        double pan = this.width * PAN_FRACTION / this.zoom; // TLN 列数，随缩放自适应
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

    /** 屏幕横坐标 -> 光标下的 chunk 序号（16 方块粒度）。 */
    private int screenToChunkX(double sx) {
        return (int) Math.floor(((sx - this.width / 2.0) / this.zoom + this.centerTlnX) * 32.0);
    }

    private int screenToChunkZ(double sy) {
        return (int) Math.floor(((sy - this.height / 2.0) / this.zoom + this.centerTlnZ) * 32.0);
    }
}
