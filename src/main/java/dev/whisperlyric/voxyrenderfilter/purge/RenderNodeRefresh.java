package dev.whisperlyric.voxyrenderfilter.purge;

import dev.whisperlyric.voxyrenderfilter.filter.RectFilter;
import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import dev.whisperlyric.voxyrenderfilter.mixin.AsyncNodeManagerAccessor;
import dev.whisperlyric.voxyrenderfilter.mixin.VoxyRenderSystemAccessor;
import dev.whisperlyric.voxyrenderfilter.tracker.ActiveTopLevelTracker;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

/**
 * Syncs the in-memory render nodes (TLN top-level) right after a disk deletion or filter change, so
 * voxy reflects the result without being disabled/re-enabled. Deletion: remove affected columns'
 * nodes, rebuild delayed from the current disk state. Filtering: remove blocked columns' nodes on
 * apply, rebuild them on clear. {@code removeTopLevelNode} throws "Position not in top level map"
 * for never-added columns (killing the AsyncNodeManager thread), so {@link ActiveTopLevelTracker#contains}
 * must be checked first. The sets below and the tracker are only accessed on the render thread.
 */
public final class RenderNodeRefresh {

    /** Reschedule interval (ms) while waiting for AsyncNodeManager to process a removal batch. */
    private static final long RE_ADD_POLL_INTERVAL_MILLIS = 20;
    /**
     * Last-resort deadline (ms) for the removal wait. voxy normally drains removals within one
     * async cycle, but large purges queue thousands of removals and voxy syncs its results with
     * the render thread, so the drain can stall well beyond a second; re-adding earlier would
     * cancel the still-pending removals (addTopLevel strips the id from tlnRem) and resurrect the
     * stale nodes.
     */
    private static final long RE_ADD_TIMEOUT_MILLIS = 30000;
    /** Shared daemon scheduler for the delayed re-add checks. */
    private static final ScheduledExecutorService RE_ADD_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VRF TLN re-add");
        t.setDaemon(true);
        return t;
    });

    /** Columns whose nodes we removed, waiting for the delayed rebuild (packed). */
    private static final LongOpenHashSet PENDING_REBUILD = new LongOpenHashSet();
    /** Columns whose nodes we removed because of the filter (packed); rebuilt when the filter clears. */
    private static final LongOpenHashSet FILTER_REMOVED = new LongOpenHashSet();
    /** Partially blocked columns rebuilt with empty meshes (packed); rebuilt again to restore real meshes on filter clear. */
    private static final LongOpenHashSet FILTER_EMPTIED = new LongOpenHashSet();

    private RenderNodeRefresh() {
    }

    /**
     * Refreshes render nodes of the TLN columns covered by {@code blockRects} (block coords).
     * Callable from any thread; internally scheduled on the render thread via {@link Minecraft#execute}.
     */
    public static void refreshForBlockRects(List<RectFilter> blockRects) {
        LongOpenHashSet columns = new LongOpenHashSet();
        for (RectFilter r : blockRects) {
            int x1 = RenderFilterState.blockToTln(r.minX());
            int x2 = RenderFilterState.blockToTln(r.maxX());
            int z1 = RenderFilterState.blockToTln(r.minZ());
            int z2 = RenderFilterState.blockToTln(r.maxZ());
            for (int tx = x1; tx <= x2; tx++) {
                for (int tz = z1; tz <= z2; tz++) {
                    columns.add(ActiveTopLevelTracker.pack(tx, tz));
                }
            }
        }
        if (columns.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> removeColumns(mc, columns));
    }

    /** Removes nodes of columns that actually have one (render thread); false when nothing to remove or the render system is unavailable. */
    private static boolean removeColumns(Minecraft mc, LongOpenHashSet columns) {
        VoxyRenderSystem renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem == null) {
            return false;
        }
        Level level = mc.level;
        if (level == null) {
            return false;
        }
        AsyncNodeManager nodeManager = ((VoxyRenderSystemAccessor) renderSystem).voxyrenderfilter$getNodeManager();
        // Same as voxy's VoxyRenderSystem constructor: lvl4 section y range = level section range >> 5
        int minSec = level.getMinSectionY() >> 5;
        int maxSec = (level.getMaxSectionY() - 1) >> 5;
        LongOpenHashSet ids = new LongOpenHashSet();
        LongOpenHashSet removed = new LongOpenHashSet();
        LongIterator it = columns.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tx = ActiveTopLevelTracker.columnX(packed);
            int tz = ActiveTopLevelTracker.columnZ(packed);
            // Skip columns never added (outside the ring / filter-blocked): removeTopLevelNode throws for them
            if (!ActiveTopLevelTracker.INSTANCE.contains(tx, tz)) {
                continue;
            }
            removed.add(packed);
            for (int y = minSec; y <= maxSec; y++) {
                ids.add(WorldEngine.getWorldSectionId(4, tx, y, tz));
            }
        }
        if (removed.isEmpty()) {
            return false;
        }
        try {
            it = ids.iterator();
            while (it.hasNext()) {
                nodeManager.removeTopLevel(it.nextLong());
            }
        } catch (IllegalStateException ignored) {
            // render system is shutting down, drop this refresh
            return false;
        }
        it = removed.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            ActiveTopLevelTracker.INSTANCE.onTopLevelRemoved(
                    ActiveTopLevelTracker.columnX(packed), ActiveTopLevelTracker.columnZ(packed));
            PENDING_REBUILD.add(packed);
        }
        scheduleReAdd(mc, removed, minSec, maxSec);
        return true;
    }

    /** Re-adds after AsyncNodeManager's background thread processed each column's removals; without
     * this wait, addTopLevel cancels the unprocessed removeTopLevel and nodes never rebuild. */
    private static void scheduleReAdd(Minecraft mc, LongOpenHashSet removed, int minSec, int maxSec) {
        LongOpenHashSet columns = new LongOpenHashSet(removed);
        scheduleReAddCheck(0, mc, columns, minSec, maxSec, System.currentTimeMillis() + RE_ADD_TIMEOUT_MILLIS);
    }

    /**
     * Waits for voxy to process each column's removal and re-adds columns as they become ready:
     * an add while the removal is still queued only cancels the removal (addTopLevel strips the
     * id from tlnRem), which resurrects the stale node and blocks the rebuild. Large purges hold
     * thousands of removals, so per-column readiness replaces a fixed whole-batch timeout; the
     * deadline is only a last resort for removals voxy never drains.
     */
    private static void scheduleReAddCheck(long delayMs, Minecraft mc, LongOpenHashSet columns,
                                           int minSec, int maxSec, long deadline) {
        RE_ADD_SCHEDULER.schedule(() -> {
            if (columns.isEmpty()) {
                return;
            }
            LongOpenHashSet ready = new LongOpenHashSet();
            if (!collectDrained(columns, minSec, maxSec, ready)) {
                return; // render system gone, nothing to rebuild
            }
            if (!ready.isEmpty()) {
                mc.execute(() -> reAddColumns(mc, ready));
            }
            if (!columns.isEmpty()) {
                if (System.currentTimeMillis() < deadline) {
                    scheduleReAddCheck(RE_ADD_POLL_INTERVAL_MILLIS, mc, columns, minSec, maxSec, deadline);
                } else {
                    // Last resort: re-add anyway; worst case a pending removal is cancelled and
                    // the stale node stays until the ring rebuilds it
                    mc.execute(() -> reAddColumns(mc, columns));
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Moves columns whose removal voxy has processed (none of their node ids remain in tlnRem)
     * from {@code columns} into {@code ready}. Returns false when the render system is gone.
     * Runs on the re-add scheduler thread; tlnRem is read under its StampedLock.
     */
    private static boolean collectDrained(LongOpenHashSet columns, int minSec, int maxSec, LongOpenHashSet ready) {
        VoxyRenderSystem renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem == null) {
            return false;
        }
        AsyncNodeManager nodeManager = ((VoxyRenderSystemAccessor) renderSystem).voxyrenderfilter$getNodeManager();
        AsyncNodeManagerAccessor accessor = (AsyncNodeManagerAccessor) nodeManager;
        LongOpenHashSet rem = accessor.voxyrenderfilter$getTlnRem();
        StampedLock lock = accessor.voxyrenderfilter$getTlnLock();
        long stamp = lock.readLock();
        try {
            if (rem.isEmpty()) {
                ready.addAll(columns);
                columns.clear();
                return true;
            }
            LongIterator it = columns.iterator();
            while (it.hasNext()) {
                long packed = it.nextLong();
                int tx = ActiveTopLevelTracker.columnX(packed);
                int tz = ActiveTopLevelTracker.columnZ(packed);
                boolean pending = false;
                for (int y = minSec; y <= maxSec; y++) {
                    if (rem.contains(WorldEngine.getWorldSectionId(4, tx, y, tz))) {
                        pending = true;
                        break;
                    }
                }
                if (!pending) {
                    it.remove();
                    ready.add(packed);
                }
            }
            return true;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    private static void reAddColumns(Minecraft mc, LongOpenHashSet columns) {
        VoxyRenderSystem renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem == null) {
            return;
        }
        Level level = mc.level;
        if (level == null) {
            return;
        }
        AsyncNodeManager nodeManager = ((VoxyRenderSystemAccessor) renderSystem).voxyrenderfilter$getNodeManager();
        int minSec = level.getMinSectionY() >> 5;
        int maxSec = (level.getMaxSectionY() - 1) >> 5;
        LongIterator it = columns.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tx = ActiveTopLevelTracker.columnX(packed);
            int tz = ActiveTopLevelTracker.columnZ(packed);
            // Rebuild only what we removed, still in the ring, and not re-added or filter-blocked in the meantime
            if (!PENDING_REBUILD.remove(packed)) {
                continue;
            }
            if (!ActiveTopLevelTracker.INSTANCE.isInRing(tx, tz)) {
                continue;
            }
            if (ActiveTopLevelTracker.INSTANCE.contains(tx, tz)) {
                continue;
            }
            if (!RenderFilterState.INSTANCE.allowsColumn(tx, tz)) {
                continue;
            }
            for (int y = minSec; y <= maxSec; y++) {
                try {
                    nodeManager.addTopLevel(WorldEngine.getWorldSectionId(4, tx, y, tz));
                } catch (IllegalStateException ignored) {
                    // render system is shutting down, skip
                }
            }
            ActiveTopLevelTracker.INSTANCE.onTopLevelAdded(tx, tz);
        }
    }

    /**
     * Applies a filter change immediately, at section granularity: fully blocked columns lose their
     * TLN node (restored on clear); partially blocked columns are removed and rebuilt delayed, so
     * blocked lvl0 sections get empty meshes (32x32 holes) from RenderGenerationServiceMixin.
     * Must run on the render thread.
     */
    public static void applyFilterImmediately() {
        RenderFilterState state = RenderFilterState.INSTANCE;
        VoxyRenderSystem renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        AsyncNodeManager nodeManager = ((VoxyRenderSystemAccessor) renderSystem).voxyrenderfilter$getNodeManager();
        int minSec = level.getMinSectionY() >> 5;
        int maxSec = (level.getMaxSectionY() - 1) >> 5;
        // Snapshot iteration: the ring may add columns meanwhile
        LongOpenHashSet snapshot = new LongOpenHashSet(ActiveTopLevelTracker.INSTANCE.getColumns());
        LongOpenHashSet rebuild = new LongOpenHashSet();
        LongIterator it = snapshot.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tx = ActiveTopLevelTracker.columnX(packed);
            int tz = ActiveTopLevelTracker.columnZ(packed);
            if (!state.isColumnBlocked(tx, tz)) {
                continue;
            }
            // Fully blocked: drop the whole-column node, restored on filter clear
            for (int y = minSec; y <= maxSec; y++) {
                try {
                    nodeManager.removeTopLevel(WorldEngine.getWorldSectionId(4, tx, y, tz));
                } catch (IllegalStateException ignored) {
                    return;
                }
            }
            ActiveTopLevelTracker.INSTANCE.onTopLevelRemoved(tx, tz);
            FILTER_REMOVED.add(packed);
        }
        // Partially blocked: rebuild delayed so blocked sections regenerate empty meshes
        it = snapshot.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tx = ActiveTopLevelTracker.columnX(packed);
            int tz = ActiveTopLevelTracker.columnZ(packed);
            if (!state.allowsColumn(tx, tz) || !state.columnHasBlockedSection(tx, tz)) {
                continue;
            }
            if (!ActiveTopLevelTracker.INSTANCE.contains(tx, tz)) {
                continue;
            }
            for (int y = minSec; y <= maxSec; y++) {
                try {
                    nodeManager.removeTopLevel(WorldEngine.getWorldSectionId(4, tx, y, tz));
                } catch (IllegalStateException ignored) {
                    return;
                }
            }
            ActiveTopLevelTracker.INSTANCE.onTopLevelRemoved(tx, tz);
            PENDING_REBUILD.add(packed);
            FILTER_EMPTIED.add(packed);
            rebuild.add(packed);
        }
        if (!rebuild.isEmpty()) {
            scheduleReAdd(mc, rebuild, minSec, maxSec);
        }
    }

    /** Restores rendering after the filter clears: fully blocked columns rebuild immediately,
     * emptied partial columns rebuild delayed to restore their real meshes. Must run on the render thread. */
    public static void clearFilterImmediately() {
        VoxyRenderSystem renderSystem = IVoxyRenderSystemHolder.getNullable();
        if (renderSystem == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        AsyncNodeManager nodeManager = ((VoxyRenderSystemAccessor) renderSystem).voxyrenderfilter$getNodeManager();
        int minSec = level.getMinSectionY() >> 5;
        int maxSec = (level.getMaxSectionY() - 1) >> 5;
        // Fully blocked columns: rebuild immediately
        LongIterator it = FILTER_REMOVED.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tx = ActiveTopLevelTracker.columnX(packed);
            int tz = ActiveTopLevelTracker.columnZ(packed);
            if (ActiveTopLevelTracker.INSTANCE.isInRing(tx, tz)
                    && !ActiveTopLevelTracker.INSTANCE.contains(tx, tz)) {
                for (int y = minSec; y <= maxSec; y++) {
                    try {
                        nodeManager.addTopLevel(WorldEngine.getWorldSectionId(4, tx, y, tz));
                    } catch (IllegalStateException ignored) {
                        // render system is shutting down, skip
                    }
                }
                ActiveTopLevelTracker.INSTANCE.onTopLevelAdded(tx, tz);
            }
        }
        FILTER_REMOVED.clear();
        // Partially blocked: rebuild delayed to restore the emptied sections' meshes
        LongOpenHashSet rebuild = new LongOpenHashSet();
        it = FILTER_EMPTIED.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            int tx = ActiveTopLevelTracker.columnX(packed);
            int tz = ActiveTopLevelTracker.columnZ(packed);
            if (!ActiveTopLevelTracker.INSTANCE.contains(tx, tz)) {
                continue;
            }
            for (int y = minSec; y <= maxSec; y++) {
                try {
                    nodeManager.removeTopLevel(WorldEngine.getWorldSectionId(4, tx, y, tz));
                } catch (IllegalStateException ignored) {
                    return;
                }
            }
            ActiveTopLevelTracker.INSTANCE.onTopLevelRemoved(tx, tz);
            PENDING_REBUILD.add(packed);
            rebuild.add(packed);
        }
        FILTER_EMPTIED.clear();
        if (!rebuild.isEmpty()) {
            scheduleReAdd(mc, rebuild, minSec, maxSec);
        }
    }
}
