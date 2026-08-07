package dev.whisperlyric.voxyrenderfilter.index;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.Arrays;

/**
 * 磁盘缓存索引：后台线程扫描 voxy 存储中已保存的 section，
 * 聚合为「TLN 列（区域，512×512 方块）是否有缓存」的不可变快照，供地图淡色覆盖层显示。
 * 扫描与渲染在不同线程，通过 volatile 数组保证可见性。
 */
public final class CacheCoverageIndex {

    public interface Listener {
        void onScanFinished();
    }

    private static final long[] EMPTY = new long[0];

    private final Listener listener;
    private volatile long[] sortedColumns = EMPTY;
    private volatile boolean scanning;

    public CacheCoverageIndex(Listener listener) {
        this.listener = listener;
    }

    /** 后台扫描指定引擎的全部已存 section，完成后回调 listener（后台线程）。 */
    public void requestScan(WorldEngine engine) {
        if (engine == null || this.scanning) {
            return;
        }
        this.scanning = true;
        this.sortedColumns = EMPTY;
        Thread worker = new Thread(() -> {
            engine.acquireRef();
            try {
                LongOpenHashSet columns = new LongOpenHashSet();
                engine.storage.iteratePositions(-1, key -> {
                    int lvl = WorldEngine.getLevel(key);
                    int colX = WorldEngine.getX(key) >> (WorldEngine.MAX_LOD_LAYER - lvl);
                    int colZ = WorldEngine.getZ(key) >> (WorldEngine.MAX_LOD_LAYER - lvl);
                    columns.add(pack(colX, colZ));
                });
                long[] arr = columns.toLongArray();
                Arrays.sort(arr);
                this.sortedColumns = arr;
            } catch (Exception e) {
                this.sortedColumns = EMPTY;
            } finally {
                this.scanning = false;
                if (this.listener != null) {
                    this.listener.onScanFinished();
                }
                engine.releaseRef();
            }
        }, "VRF cache scan");
        worker.setDaemon(true);
        worker.start();
    }

    /** 按列序号升序排列的已缓存 TLN 列（packed），只读。 */
    public long[] getColumns() {
        return this.sortedColumns;
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
