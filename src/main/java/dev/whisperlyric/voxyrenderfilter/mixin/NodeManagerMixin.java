package dev.whisperlyric.voxyrenderfilter.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Safety net against voxy's "Position not in top level map" crash: removing a top-level node that
 * was never created (or already removed by an add/remove race during ring resizes / filter toggles)
 * throws inside voxy's AsyncNodeManager and kills rendering. Skipping the removal for absent nodes
 * keeps the node manager alive.
 */
@Mixin(NodeManager.class)
public abstract class NodeManagerMixin {

    @Shadow
    @Final
    private LongOpenHashSet topLevelNodes;

    @Inject(method = "removeTopLevelNode", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$skipMissingTopLevel(long pos, CallbackInfo ci) {
        if (!this.topLevelNodes.contains(pos)) {
            ci.cancel();
        }
    }
}
