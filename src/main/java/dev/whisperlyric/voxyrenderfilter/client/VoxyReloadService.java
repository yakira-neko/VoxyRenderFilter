package dev.whisperlyric.voxyrenderfilter.client;

import dev.whisperlyric.voxyrenderfilter.mixin.VoxyInstanceAccessor;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * velocity 兼容：代理切换子服时，客户端始终连接同一个地址（proxy），
 * voxy 按「连接 + 世界标识（维度 + 种子）」定位缓存文件。
 * <p>
 * 世界标识包含 biomeSeed，切换子服后种子不同 → 标识不同 → voxy 会自然为每个
 * 世界创建独立引擎并读取对应缓存，无需主动干预；本服务只做安全兜底。
 * <p>
 * 崩溃修复：绝不能直接对仍被使用的引擎调用 {@link WorldEngine#free()}。
 * voxy 渲染系统在创建时对引擎持有引用计数（{@code VoxyRenderSystem} 构造时
 * {@code acquireRef()}），直接 free() 会把引擎置为 not-live，之后 chunk 卸载时
 * ingest 服务调用 {@code acquireRef()} 会抛 {@code IllegalStateException} 崩溃。
 * 因此这里仅当引擎完全空闲（无引用计数、无已加载 section）时才加锁移除并释放，
 * 让 voxy 在需要时按当前磁盘缓存重建；使用中的引擎保持不动，由 voxy 自身的
 * {@code cleanIdle()} 在世界空闲后统一回收。
 */
public final class VoxyReloadService {

    private VoxyReloadService() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> reloadCurrentEngine(client)));
    }

    private static void reloadCurrentEngine(Minecraft client) {
        Level level = client.level;
        if (level == null) {
            return;
        }
        WorldIdentifier id = WorldIdentifier.of(level);
        if (id == null) {
            return;
        }
        VoxyInstance instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning()) {
            return;
        }
        VoxyInstanceAccessor accessor = (VoxyInstanceAccessor) instance;
        HashMap<WorldIdentifier, WorldEngine> worlds = accessor.voxyrenderfilter$getActiveWorlds();
        StampedLock lock = accessor.voxyrenderfilter$getActiveWorldLock();
        long stamp = lock.writeLock();
        try {
            WorldEngine old = worlds.get(id);
            if (old == null || !old.isLive() || old.isWorldUsed()) {
                return; // 使用中的引擎交给 voxy 生命周期管理
            }
            worlds.remove(id);
            old.free(); // 空闲引擎（无引用、无 section）此时释放是安全的
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
