package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the internal {@link AsyncNodeManager} of {@link VoxyRenderSystem} so TLN top-level render
 * nodes can be removed or rebuilt immediately after the cache is deleted.
 */
@Mixin(VoxyRenderSystem.class)
public interface VoxyRenderSystemAccessor {

    @Accessor("nodeManager")
    AsyncNodeManager voxyrenderfilter$getNodeManager();
}
