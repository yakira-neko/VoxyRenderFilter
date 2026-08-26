package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.client.VoxyIngestPause;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Same as {@link VoxyClientInstanceIngestMixin}, covering the generic instance when the method is
 * not overridden by a subclass.
 */
@Mixin(VoxyInstance.class)
public class VoxyInstanceIngestMixin {

    @Inject(method = "isIngestEnabled", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$pauseIngest(WorldIdentifier worldId, CallbackInfoReturnable<Boolean> cir) {
        if (VoxyIngestPause.isPaused()) {
            cir.setReturnValue(false);
        }
    }
}
