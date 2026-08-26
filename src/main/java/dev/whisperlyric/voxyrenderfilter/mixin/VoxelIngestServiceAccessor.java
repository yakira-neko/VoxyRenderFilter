package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link VoxelIngestService}'s private executor service to drain in-flight ingest tasks before deleting the cache.
 */
@Mixin(VoxelIngestService.class)
public interface VoxelIngestServiceAccessor {

    @Accessor(value = "service", remap = false)
    Service voxyrenderfilter$getService();
}
