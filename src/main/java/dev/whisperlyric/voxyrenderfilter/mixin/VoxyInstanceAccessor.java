package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * 暴露 voxy {@link VoxyInstance} 的私有 activeWorlds 表及其 StampedLock，
 * 供服务器切换时安全移除空闲引擎（voxy 自身没有「释放指定世界」的公开接口）。
 */
@Mixin(VoxyInstance.class)
public interface VoxyInstanceAccessor {

    @Accessor(value = "activeWorlds", remap = false)
    HashMap<WorldIdentifier, WorldEngine> voxyrenderfilter$getActiveWorlds();

    @Accessor(value = "activeWorldLock", remap = false)
    StampedLock voxyrenderfilter$getActiveWorldLock();
}
