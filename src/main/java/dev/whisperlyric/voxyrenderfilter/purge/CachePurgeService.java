package dev.whisperlyric.voxyrenderfilter.purge;

import dev.whisperlyric.voxyrenderfilter.client.VoxyIngestPause;
import dev.whisperlyric.voxyrenderfilter.filter.RectFilter;
import dev.whisperlyric.voxyrenderfilter.mixin.SectionSavingServiceAccessor;
import dev.whisperlyric.voxyrenderfilter.mixin.VoxyInstanceAccessor;
import dev.whisperlyric.voxyrenderfilter.mixin.VoxelIngestServiceAccessor;
import dev.whisperlyric.voxyrenderfilter.util.VoxyAccess;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyInstance;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Deletes the voxy LOD cache inside the given block rects in the background.
 * Disk: {@link StorageBackend#deleteSectionData(long)} directly (SectionStorage has no delete API).
 * Memory: loaded dirty sections are cleared first so releasing them does not save the data back.
 * All LOD levels (lvl0-4) whose section overlaps the selection are deleted: a coarse LOD section
 * covers a larger area that includes the selection, so keeping it would resurrect the deleted data.
 * Deleted regions regenerate at max detail the next time voxy loads them.
 */
public final class CachePurgeService {

    private static final AtomicLong GENERATION = new AtomicLong();

    private CachePurgeService() {
    }

    /**
     * Asynchronously deletes all stored sections inside a single blockRect. Invokes the callback with the
     * deleted count on completion, or -1 if the backend is unavailable.
     */
    public static void purge(WorldEngine engine, RectFilter blockRect, Consumer<Integer> onDone) {
        purge(engine, List.of(blockRect), onDone);
    }

    /**
     * Deletes all stored sections (all LOD levels) inside multiple blockRects (their union) on one
     * worker thread, so concurrent purges cannot cancel each other via {@link #GENERATION} increments.
     * Order: pause ingest, drain the in-flight ingest/save queues, delete, resume ingest. The
     * callback receives the deleted count, or -1 when the backend is unavailable.
     */
    public static void purge(WorldEngine engine, List<RectFilter> blockRects, Consumer<Integer> onDone) {
        long gen = GENERATION.incrementAndGet();
        engine.markActive();
        Thread worker = new Thread(() -> {
            VoxyInstance instance = engine.instanceIn;
            if (instance != null) {
                VoxyIngestPause.setPaused(true);
            }
            int count = 0;
            engine.acquireRef();
            try {
                StorageBackend backend = VoxyAccess.getStorageBackend(engine);
                if (backend == null) {
                    onDone.accept(-1);
                    return;
                }
                if (instance != null) {
                    // Drain in-flight ingest so it cannot write the data back (resurrect it) afterwards.
                    ((VoxelIngestServiceAccessor) instance.getIngestService())
                            .voxyrenderfilter$getService().blockTillEmpty();
                    // Drain the save queue so every dirty section in memory is flushed; otherwise the scan would miss them.
                    ((SectionSavingServiceAccessor) ((VoxyInstanceAccessor) instance)
                            .voxyrenderfilter$getSavingService()).voxyrenderfilter$getService().blockTillEmpty();
                }
                LongArrayList keys = new LongArrayList();
                engine.storage.iteratePositions(-1, keys::add);
                for (int i = 0; i < keys.size(); i++) {
                    if (gen != GENERATION.get()) {
                        return; // superseded by a newer purge
                    }
                    long key = keys.getLong(i);
                    // Every level overlapping the selection is deleted: a coarse LOD (lvl1-4) covers
                    // a larger section containing the selected area, so keeping it would let the
                    // deleted region render again from that data.
                    if (!overlapsAny(blockRects, key)) {
                        continue;
                    }
                    evictFromMemory(engine, key);
                    backend.deleteSectionData(key);
                    count++;
                }
                backend.flush();
                onDone.accept(count);
            } catch (IllegalStateException e) {
                // The world was released by voxy during the scan.
                onDone.accept(-1);
            } finally {
                if (instance != null) {
                    VoxyIngestPause.setPaused(false);
                }
                // Note: onDone runs while the reference is still held (the callback may trigger a new scan); release it afterwards.
                engine.releaseRef();
            }
        }, "VRF cache purge");
        worker.setDaemon(true);
        worker.start();
    }

    /** Whether the section for this key (at lvl, covering 16<<(lvl+1) blocks) overlaps any rectangle. */
    private static boolean overlapsAny(List<RectFilter> rects, long key) {
        int lvl = WorldEngine.getLevel(key);
        int shift = WorldEngine.MAX_LOD_LAYER + 1 + lvl; // section size in blocks: 16<<(lvl+1) = 1<<(5+lvl)
        long size = 1L << shift;
        long x0 = ((long) WorldEngine.getX(key)) << shift;
        long z0 = ((long) WorldEngine.getZ(key)) << shift;
        long x1 = x0 + size;
        long z1 = z0 + size;
        for (RectFilter r : rects) {
            if (r.maxX() >= x0 && r.minX() < x1 && r.maxZ() >= z0 && r.minZ() < z1) {
                return true;
            }
        }
        return false;
    }

    private static void evictFromMemory(WorldEngine engine, long key) {
        WorldSection section = engine.acquireIfExists(key);
        if (section == null) {
            return;
        }
        try {
            if (section.shouldSave()) {
                // Prevent release from triggering a save that writes the data back after the disk deletion.
                section.setNotDirty();
            }
            // Clear the in-memory data: the section may still sit in the ActiveSectionTracker cache (primary / LRU secondary);
            // without clearing, nodes rebuilt after the deletion would get stale data from the cache and the effect would only appear after voxy reloads.
            long[] data = section._unsafeGetRawDataArray();
            if (data != null) {
                Arrays.fill(data, 0L);
            }
        } finally {
            section.release();
        }
    }
}
