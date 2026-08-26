package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.client.VoxyIngestPause;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pauses data writes while the cache is being deleted: intercepts {@link VoxyClientInstance#isIngestEnabled}
 * and returns false when the pause switch is on (voxy checks this method at both the tryIngestChunk and rawIngest entry points).
 */
@Mixin(VoxyClientInstance.class)
public class VoxyClientInstanceIngestMixin {

    @Inject(method = "isIngestEnabled", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$pauseIngest(WorldIdentifier worldId, CallbackInfoReturnable<Boolean> cir) {
        if (VoxyIngestPause.isPaused()) {
            cir.setReturnValue(false);
        }
    }
}
