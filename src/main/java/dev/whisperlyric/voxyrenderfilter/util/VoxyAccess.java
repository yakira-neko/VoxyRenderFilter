package dev.whisperlyric.voxyrenderfilter.util;

import dev.whisperlyric.voxyrenderfilter.mixin.SectionSerializationStorageAccessor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;

/**
 * 从当前客户端世界获取 voxy 的 WorldEngine / StorageBackend 的工具方法。
 */
public final class VoxyAccess {

    private VoxyAccess() {
    }

    /**
     * 当前客户端世界的 voxy WorldEngine（未加载或不可用时返回 null）。
     */
    public static WorldEngine getCurrentEngine() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        WorldIdentifier identifier = WorldIdentifier.of(mc.level);
        if (identifier == null) {
            return null;
        }
        WorldEngine engine = identifier.getNullable();
        return (engine != null && engine.isLive()) ? engine : null;
    }

    /**
     * 当前 WorldEngine 的磁盘 backend。
     * voxy 默认 storage 运行时类型是 {@link SectionSerializationStorage}，
     * 用户自定义存储时可能不是该类型，此时返回 null（调用方按失败处理）。
     */
    public static StorageBackend getStorageBackend(WorldEngine engine) {
        if (engine == null || !(engine.storage instanceof SectionSerializationStorage storage)) {
            return null;
        }
        return ((SectionSerializationStorageAccessor) storage).voxyrenderfilter$getBackend();
    }
}
