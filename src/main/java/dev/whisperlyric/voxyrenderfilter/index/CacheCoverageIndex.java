package dev.whisperlyric.voxyrenderfilter.index;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.Arrays;

/**
 * Disk cache index: a background thread scans the sections saved in voxy's storage and aggregates
 * an immutable snapshot of which lvl0 sections (32x32 blocks) and TLN columns (512x512 blocks)
 * have cached data, for the map's faint overlay. Scan and render run on different threads; the
 * volatile arrays guarantee visibility.
 */
public final class CacheCoverageIndex {

    public interface Listener {
        void onScanFinished();
    }

    private static final long[] EMPTY = new long[0];

    private final Listener listener;
    /** lvl0 sections (32x32 blocks), packed (x, z), ascending; finest cached position info. */
    private volatile long[] sortedSections = EMPTY;
    /** TLN columns (512x512 blocks), packed (x, z), ascending; only columns containing lvl0 sections. */
    private volatile long[] sortedColumns = EMPTY;
    private volatile boolean scanning;
    private Thread scanThread;

    public CacheCoverageIndex(Listener listener) {
        this.listener = listener;
    }

    /** Scans all saved sections of the given engine in the background, then calls back the listener (background thread). */
    public void requestScan(WorldEngine engine) {
        this.requestScan(engine, false);
    }

    /**
     * Scans all saved sections of the given engine in the background, then calls back the listener (background thread).
     *
     * @param force when true, waits for an ongoing scan to finish and rescans immediately
     *              (used to refresh the display after deleting cache); when false, an ongoing scan is skipped.
     */
    public void requestScan(WorldEngine engine, boolean force) {
        if (engine == null) {
            return;
        }
        Thread prev = this.scanThread;
        if (prev != null && prev.isAlive()) {
            if (!force) {
                return;
            }
            try {
                prev.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (this.scanning) {
            return;
        }
        this.scanning = true;
        this.sortedColumns = EMPTY;
        this.sortedSections = EMPTY;
        Thread worker = new Thread(() -> {
            engine.acquireRef();
            try {
                LongOpenHashSet sections = new LongOpenHashSet();
                LongOpenHashSet columns = new LongOpenHashSet();
                // Runs in parallel with the map's disk scans (LMDB read cursors are concurrent-safe)
                engine.storage.iteratePositions(-1, key -> {
                    int lvl = WorldEngine.getLevel(key);
                    int x = WorldEngine.getX(key);
                    int z = WorldEngine.getZ(key);
                    // lvl0 is voxy's finest storage granularity (32x32 blocks). Aggregating only lvl0
                    // as "covered" prevents coarse LODs from keeping a column shown as cached after its lvl0 is deleted.
                    if (lvl == 0) {
                        sections.add(pack(x, z));
                        columns.add(pack(x >> (WorldEngine.MAX_LOD_LAYER - lvl),
                                z >> (WorldEngine.MAX_LOD_LAYER - lvl)));
                    }
                });
                long[] secArr = sections.toLongArray();
                Arrays.sort(secArr);
                this.sortedSections = secArr;
                long[] colArr = columns.toLongArray();
                Arrays.sort(colArr);
                this.sortedColumns = colArr;
            } catch (Exception e) {
                this.sortedColumns = EMPTY;
                this.sortedSections = EMPTY;
            } finally {
                this.scanning = false;
                if (this.listener != null) {
                    this.listener.onScanFinished();
                }
                engine.releaseRef();
            }
        }, "VRF cache scan");
        worker.setDaemon(true);
        this.scanThread = worker;
        worker.start();
    }

    /** TLN columns containing lvl0 sections (packed, ascending), read-only. */
    public long[] getColumns() {
        return this.sortedColumns;
    }

    /** All lvl0 sections (packed (x, z), ascending), read-only; coordinates in 32-block granularity. */
    public long[] getSections() {
        return this.sortedSections;
    }

    public boolean containsColumn(int tlnX, int tlnZ) {
        return Arrays.binarySearch(this.sortedColumns, pack(tlnX, tlnZ)) >= 0;
    }

    public int size() {
        return this.sortedColumns.length;
    }

    public boolean isScanning() {
        return this.scanning;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
