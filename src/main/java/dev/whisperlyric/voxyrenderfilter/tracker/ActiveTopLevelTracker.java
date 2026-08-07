package dev.whisperlyric.voxyrenderfilter.tracker;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 记录 voxy 当前活跃（正在 LOD 渲染）的顶层区块（TLN 列，= 方块坐标 &gt;&gt; 9，512×512 方块）集合。
 * 由 {@code RenderDistanceTrackerMixin} 在 add/rem 时维护。
 * add/rem 与地图绘制都发生在客户端主线程（渲染线程），因此无需加锁。
 */
public final class ActiveTopLevelTracker {

    public static final ActiveTopLevelTracker INSTANCE = new ActiveTopLevelTracker();

    private final LongOpenHashSet columns = new LongOpenHashSet();

    private ActiveTopLevelTracker() {
    }

    public void onTopLevelAdded(int tlnX, int tlnZ) {
        this.columns.add(pack(tlnX, tlnZ));
    }

    public void onTopLevelRemoved(int tlnX, int tlnZ) {
        this.columns.remove(pack(tlnX, tlnZ));
    }

    public LongOpenHashSet getColumns() {
        return this.columns;
    }

    public int size() {
        return this.columns.size();
    }

    public static long pack(int tlnX, int tlnZ) {
        return ((long) tlnX << 32) | (tlnZ & 0xFFFFFFFFL);
    }

    public static int columnX(long packed) {
        return (int) (packed >> 32);
    }

    public static int columnZ(long packed) {
        return (int) packed;
    }
}
