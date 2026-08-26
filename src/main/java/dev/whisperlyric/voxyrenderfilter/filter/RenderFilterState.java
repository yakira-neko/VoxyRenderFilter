package dev.whisperlyric.voxyrenderfilter.filter;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global filter state (singleton). Two modes:
 * - {@link Mode#BLOCK}: sections inside the rects are not rendered.
 * - {@link Mode#ALLOW}: only sections inside the rects are rendered.
 * Rects are in lvl0 section coords (32x32 blocks, voxy's finest storage unit). Rendering is
 * whole-column (TLN) granularity: fully blocked columns lose their node, partially blocked ones
 * keep it with empty meshes for the blocked sections.
 */
public final class RenderFilterState {

    public enum Mode {
        /** Do not render inside the rects. */
        BLOCK,
        /** Only render inside the rects. */
        ALLOW
    }

    /** lvl0 sections per TLN column axis. */
    private static final int SECTIONS_PER_COLUMN = 16;

    // EMPTY must be declared before INSTANCE: the INSTANCE initializer (filters = EMPTY) runs at
    // class load, so a later declaration would still be null when assigned.
    private static final RectFilter[] EMPTY = new RectFilter[0];

    public static final RenderFilterState INSTANCE = new RenderFilterState();

    /** Filter rects (lvl0 section coords, inclusive); empty array = disabled. */
    private volatile RectFilter[] filters = EMPTY;
    private volatile Mode mode = Mode.BLOCK;
    private final Object modeLock = new Object();
    private final AtomicLong blockedCount = new AtomicLong();

    private RenderFilterState() {
    }

    public boolean isEnabled() {
        RectFilter[] f = this.filters;
        return f != null && f.length > 0;
    }

    /** First filter rect, or null when disabled; use {@link #getFilters()} for multi-select. */
    public RectFilter getFilter() {
        RectFilter[] f = this.filters;
        return f == null || f.length == 0 ? null : f[0];
    }

    public RectFilter[] getFilters() {
        RectFilter[] f = this.filters;
        return f == null ? EMPTY : f;
    }

    public Mode getMode() {
        return this.mode;
    }

    /** Block coordinate -> lvl0 section coordinate (32-block granularity). */
    public static int blockToSection(int blockCoord) {
        return blockCoord >> 5;
    }

    /** Block coordinate -> TLN column coordinate (512-block granularity, for delete/rebuild). */
    public static int blockToTln(int blockCoord) {
        return blockCoord >> 9;
    }

    /** Set a single rect from block coordinates (command path). */
    public void setRect(int blockX1, int blockZ1, int blockX2, int blockZ2) {
        this.setRect(blockX1, blockZ1, blockX2, blockZ2, Mode.ALLOW);
    }

    /** Set multiple filter rects (lvl0 section coords) and mode (map path). */
    public void setRects(List<RectFilter> sectionRects, Mode mode) {
        this.filters = sectionRects.isEmpty() ? EMPTY : sectionRects.toArray(new RectFilter[0]);
        this.mode = mode;
        this.blockedCount.set(0);
    }

    /** Invert the filter polarity (BLOCK <-> ALLOW), rects unchanged. */
    public void toggleMode() {
        synchronized (this.modeLock) {
            this.mode = this.mode == Mode.BLOCK ? Mode.ALLOW : Mode.BLOCK;
        }
        this.blockedCount.set(0);
    }

    /** Set a single rect (block coords) with an explicit mode. */
    public void setRect(int blockX1, int blockZ1, int blockX2, int blockZ2, Mode mode) {
        int x1 = blockToSection(blockX1);
        int z1 = blockToSection(blockZ1);
        int x2 = blockToSection(blockX2);
        int z2 = blockToSection(blockZ2);
        this.filters = new RectFilter[] {
                new RectFilter(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2))};
        this.mode = mode;
        this.blockedCount.set(0);
    }

    /** Subtract a rect (section coords) from the filter via rectangle difference; empty result clears the filter. */
    public void removeRect(RectFilter r) {
        RectFilter[] f = this.filters;
        if (f == null || f.length == 0) {
            return;
        }
        List<RectFilter> result = new ArrayList<>();
        for (RectFilter a : f) {
            subtract(a, r, result);
        }
        if (result.isEmpty()) {
            this.filters = EMPTY;
        } else {
            this.filters = result.toArray(new RectFilter[0]);
        }
        this.blockedCount.set(0);
    }

    /** Add a rect (section coords) to the filter (appended, not merged). */
    public void addRect(RectFilter r) {
        RectFilter[] f = this.filters;
        if (f == null || f.length == 0) {
            this.filters = new RectFilter[] {r};
        } else {
            List<RectFilter> list = new ArrayList<>(Arrays.asList(f));
            list.add(r);
            this.filters = list.toArray(new RectFilter[0]);
        }
        this.blockedCount.set(0);
    }

    public void clear() {
        this.filters = EMPTY;
        this.blockedCount.set(0);
    }

    /** Whether the lvl0 section is blocked (map orange overlay / mesh interception). */
    public boolean isSectionBlocked(int sectionX, int sectionZ) {
        RectFilter[] f = this.filters;
        if (f == null || f.length == 0) {
            return false;
        }
        return !allowsSection0(f, sectionX, sectionZ);
    }

    /** Whether the section may render (depends on mode); always true without a filter. */
    public boolean allowsSection(int sectionX, int sectionZ) {
        RectFilter[] f = this.filters;
        return f == null || f.length == 0 || allowsSection0(f, sectionX, sectionZ);
    }

    private static boolean allowsSection0(RectFilter[] f, int sectionX, int sectionZ) {
        boolean inside = false;
        for (RectFilter r : f) {
            if (r.contains(sectionX, sectionZ)) {
                inside = true;
                break;
            }
        }
        return (INSTANCE.mode == Mode.BLOCK) != inside;
    }

    /** Whether the whole column is blocked (all 16x16 sections), i.e. its TLN node can be removed. */
    public boolean isColumnBlocked(int tlnX, int tlnZ) {
        RectFilter[] f = this.filters;
        if (f == null || f.length == 0) {
            return false;
        }
        int sx0 = tlnX << 4;
        int sz0 = tlnZ << 4;
        for (int sx = sx0; sx < sx0 + SECTIONS_PER_COLUMN; sx++) {
            for (int sz = sz0; sz < sz0 + SECTIONS_PER_COLUMN; sz++) {
                if (allowsSection0(f, sx, sz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the column keeps its TLN node (= not fully blocked). */
    public boolean allowsColumn(int tlnX, int tlnZ) {
        return !isColumnBlocked(tlnX, tlnZ);
    }

    /** Whether the column contains blocked sections (partial block, needs rebuild for empty meshes). */
    public boolean columnHasBlockedSection(int tlnX, int tlnZ) {
        RectFilter[] f = this.filters;
        if (f == null || f.length == 0) {
            return false;
        }
        int sx0 = tlnX << 4;
        int sz0 = tlnZ << 4;
        for (int sx = sx0; sx < sx0 + SECTIONS_PER_COLUMN; sx++) {
            for (int sz = sz0; sz < sz0 + SECTIONS_PER_COLUMN; sz++) {
                if (!allowsSection0(f, sx, sz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether an entire section rect (inclusive) lies in blocked area (for the "unblock" context menu). */
    public boolean isRectFullyBlocked(RectFilter sectionRect) {
        for (int sx = sectionRect.minX(); sx <= sectionRect.maxX(); sx++) {
            for (int sz = sectionRect.minZ(); sz <= sectionRect.maxZ(); sz++) {
                if (allowsSection(sx, sz)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void countBlocked() {
        this.blockedCount.incrementAndGet();
    }

    public long getBlockedCount() {
        return this.blockedCount.get();
    }

    /** One-line HUD status text, or null when disabled (nothing drawn). */
    public Component describeHud() {
        RectFilter f = this.getFilter();
        if (f == null) {
            return null;
        }
        boolean block = this.mode == Mode.BLOCK;
        return Component.translatable(block ? "voxyrenderfilter.hud.block" : "voxyrenderfilter.hud.allow",
                f.minX(), f.minZ(), f.maxX(), f.maxZ(), this.blockedCount.get());
    }

    /** Rect difference a minus b (inclusive rects); non-empty results are appended to out. */
    private static void subtract(RectFilter a, RectFilter b, List<RectFilter> out) {
        // Half-open intervals [min, max+1)
        int ax1 = a.minX(), az1 = a.minZ(), ax2 = a.maxX() + 1, az2 = a.maxZ() + 1;
        int bx1 = b.minX(), bz1 = b.minZ(), bx2 = b.maxX() + 1, bz2 = b.maxZ() + 1;
        if (ax2 <= bx1 || bx2 <= ax1 || az2 <= bz1 || bz2 <= az1) {
            out.add(a);
            return;
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
    }
}
