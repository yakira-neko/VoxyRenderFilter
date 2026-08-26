package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.world.service.SectionSavingService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link SectionSavingService}'s private executor service so the save queue can be drained
 * before the cache is deleted (ensures iteratePositions scans all data on disk and nothing is missed).
 */
@Mixin(SectionSavingService.class)
public interface SectionSavingServiceAccessor {

    @Accessor(value = "service", remap = false)
    Service voxyrenderfilter$getService();
}
