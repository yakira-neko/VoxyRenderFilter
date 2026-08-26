package dev.whisperlyric.voxyrenderfilter.tracker;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Tracks which TLN columns (512x512 blocks) voxy currently LOD-renders, kept up to date by
 * RenderDistanceTrackerMixin on add/rem. {@link #columns} = columns with real render nodes
 * (filter-blocked adds are skipped); {@link #ringColumns} = the whole render ring including
 * filtered columns, used to decide whether a column still needs rebuilding after deletion/filtering.
 * All access happens on the client render thread, no locking needed.
 */
public final class ActiveTopLevelTracker {

    public static final ActiveTopLevelTracker INSTANCE = new ActiveTopLevelTracker();

    private final LongOpenHashSet columns = new LongOpenHashSet();
    private final LongOpenHashSet ringColumns = new LongOpenHashSet();

    private ActiveTopLevelTracker() {
    }

    public void onTopLevelAdded(int tlnX, int tlnZ) {
        this.columns.add(pack(tlnX, tlnZ));
    }

    public void onTopLevelRemoved(int tlnX, int tlnZ) {
        this.columns.remove(pack(tlnX, tlnZ));
    }

    public void onRingAdd(int tlnX, int tlnZ) {
        this.ringColumns.add(pack(tlnX, tlnZ));
    }

    public void onRingRemove(int tlnX, int tlnZ) {
        this.ringColumns.remove(pack(tlnX, tlnZ));
    }

    public LongOpenHashSet getColumns() {
        return this.columns;
    }

    /** All columns currently covered by the render ring (including filtered ones), used to confine deselection masking to the voxy render distance. */
    public LongOpenHashSet getRingColumns() {
        return this.ringColumns;
    }

    public boolean contains(int tlnX, int tlnZ) {
        return this.columns.contains(pack(tlnX, tlnZ));
    }

    public boolean isInRing(int tlnX, int tlnZ) {
        return this.ringColumns.contains(pack(tlnX, tlnZ));
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
