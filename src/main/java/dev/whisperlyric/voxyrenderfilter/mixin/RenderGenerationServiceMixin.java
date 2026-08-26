package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Mesh generation interception: a filtered lvl0 section (32x32 blocks) is not meshed, the request
 * is answered with {@link BuiltSection#empty} instead, so a stable 32x32 hole renders while the
 * rest of the column renders normally (no whole-column TLN removal needed). The filter state is a
 * volatile array, safe to read from voxy's background thread.
 */
@Mixin(RenderGenerationService.class)
public abstract class RenderGenerationServiceMixin {

    @Shadow
    private Consumer<BuiltSection> resultConsumer;

    @Inject(method = "enqueueTask", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$emptyBlockedSection(long pos, CallbackInfo ci) {
        if (WorldEngine.getLevel(pos) != 0) {
            // Only intercept the finest lvl0 meshes; lvl1-4 coarse meshes serve long distances and are handled by the whole-column removal logic
            return;
        }
        RenderFilterState state = RenderFilterState.INSTANCE;
        if (!state.isEnabled() || !state.isSectionBlocked(WorldEngine.getX(pos), WorldEngine.getZ(pos))) {
            return;
        }
        Consumer<BuiltSection> consumer = this.resultConsumer;
        if (consumer != null) {
            consumer.accept(BuiltSection.empty(pos));
        }
        ci.cancel();
    }
}
