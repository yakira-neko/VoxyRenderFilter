package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * Exposes voxy {@link VoxyInstance}'s private activeWorlds table and its StampedLock so idle engines
 * can be removed safely on server switch (voxy has no public interface for releasing a specific world).
 */
@Mixin(VoxyInstance.class)
public interface VoxyInstanceAccessor {

    @Accessor(value = "activeWorlds", remap = false)
    HashMap<WorldIdentifier, WorldEngine> voxyrenderfilter$getActiveWorlds();

    @Accessor(value = "activeWorldLock", remap = false)
    StampedLock voxyrenderfilter$getActiveWorldLock();

    @Accessor(value = "savingService", remap = false)
    SectionSavingService voxyrenderfilter$getSavingService();
}
