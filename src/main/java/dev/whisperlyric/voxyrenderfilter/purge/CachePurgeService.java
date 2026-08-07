package dev.whisperlyric.voxyrenderfilter.purge;

import dev.whisperlyric.voxyrenderfilter.filter.RectFilter;
import dev.whisperlyric.voxyrenderfilter.util.VoxyAccess;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 后台删除选中矩形（TLN 坐标）内的 voxy LOD 缓存。
 * <ul>
 *   <li>磁盘：直接调用 {@link StorageBackend#deleteSectionData(long)}（绕过无 delete 接口的 SectionStorage）</li>
 *   <li>内存：对已加载的 section 先驱逐引用；若其带脏标记则先清标记，防止 release 时触发保存把数据写回磁盘</li>
 * </ul>
 * 删除后 voxy 再次加载该区域时会发现磁盘无数据，从而按最高细节重新生成。
 */
public final class CachePurgeService {

    private static final AtomicLong GENERATION = new AtomicLong();

    private CachePurgeService() {
    }

    /**
     * 异步删除 tlnRect 内所有已存 section。完成后回调删除数量；backend 不可用时回调 -1。
     */
    public static void purge(WorldEngine engine, RectFilter tlnRect, Consumer<Integer> onDone) {
        long gen = GENERATION.incrementAndGet();
        engine.markActive();
        Thread worker = new Thread(() -> {
            int count = 0;
            engine.acquireRef();
            try {
                StorageBackend backend = VoxyAccess.getStorageBackend(engine);
                if (backend == null) {
                    onDone.accept(-1);
                    return;
                }
                LongArrayList keys = new LongArrayList();
                engine.storage.iteratePositions(-1, keys::add);
                for (int i = 0; i < keys.size(); i++) {
                    if (gen != GENERATION.get()) {
                        return; // 已被新的删除任务取代
                    }
                    long key = keys.getLong(i);
                    int lvl = WorldEngine.getLevel(key);
                    int tlnX = WorldEngine.getX(key) >> (4 - lvl);
                    int tlnZ = WorldEngine.getZ(key) >> (4 - lvl);
                    if (!tlnRect.contains(tlnX, tlnZ)) {
                        continue;
                    }
                    evictFromMemory(engine, key);
                    backend.deleteSectionData(key);
                    count++;
                }
                backend.flush();
                onDone.accept(count);
            } catch (IllegalStateException e) {
                // 世界在扫描途中被 voxy 释放
                onDone.accept(-1);
            } finally {
                // 注意：onDone 回调在持有引用时执行（回调可能触发新的扫描），再释放引用
                engine.releaseRef();
            }
        }, "VRF cache purge");
        worker.setDaemon(true);
        worker.start();
    }

    private static void evictFromMemory(WorldEngine engine, long key) {
        WorldSection section = engine.acquireIfExists(key);
        if (section == null) {
            return;
        }
        try {
            if (section.shouldSave()) {
                // 防止磁盘删除后 release 触发保存把数据写回去
                section.setNotDirty();
            }
        } finally {
            section.release();
        }
    }
}
