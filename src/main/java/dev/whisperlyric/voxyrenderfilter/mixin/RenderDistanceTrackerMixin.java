package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import dev.whisperlyric.voxyrenderfilter.tracker.ActiveTopLevelTracker;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the render ring's add/rem (TLN column coords, block >> 9) to apply the filter and keep
 * {@link ActiveTopLevelTracker} in sync: a fully blocked column cancels the whole-column add, so
 * voxy renders only the selected region. All add/rem events are also recorded as ring membership
 * (filtered ones included) to know whether a column is still inside the ring for rebuild decisions.
 */
@Mixin(RenderDistanceTracker.class)
public abstract class RenderDistanceTrackerMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$filterTopLevelColumn(int x, int z, CallbackInfo ci) {
        ActiveTopLevelTracker.INSTANCE.onRingAdd(x, z);
        // Only intercept columns whose whole 16x16 section group is blocked; partially blocked
        // columns keep their top-level node and RenderGenerationServiceMixin substitutes an empty
        // mesh for each blocked section during generation.
        if (RenderFilterState.INSTANCE.isColumnBlocked(x, z)) {
            RenderFilterState.INSTANCE.countBlocked();
            ci.cancel();
        } else {
            ActiveTopLevelTracker.INSTANCE.onTopLevelAdded(x, z);
        }
    }

    @Inject(method = "rem", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$trackTopLevelRemoved(int x, int z, CallbackInfo ci) {
        ActiveTopLevelTracker.INSTANCE.onRingRemove(x, z);
        if (ActiveTopLevelTracker.INSTANCE.contains(x, z)) {
            ActiveTopLevelTracker.INSTANCE.onTopLevelRemoved(x, z);
            return;
        }
        // This column never had a render node created (blocked by the filter, or already removed by
        // us); voxy's removeTopLevel would throw "Position not in top level map" on the
        // AsyncNodeManager background thread and crash the render system, so the call is cancelled.
        // Note: RingTracker has already marked the column inactive internally; cancelling only skips
        // voxy's node removal callback.
        ci.cancel();
    }
}
