package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import dev.whisperlyric.voxyrenderfilter.tracker.ActiveTopLevelTracker;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在玩家移动时按 TLN 列坐标（lvl4 section 坐标，方块坐标 >> 9）推送
 * {@code addTopLevelNode}。在 HEAD 拦截并检查过滤矩形，命中过滤则取消整列 TLN 添加，
 * 从而让 voxy 只渲染选中区域；未被过滤的列记录到 {@link ActiveTopLevelTracker} 供地图显示。
 */
@Mixin(RenderDistanceTracker.class)
public abstract class RenderDistanceTrackerMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxyrenderfilter$filterTopLevelColumn(int x, int z, CallbackInfo ci) {
        if (!RenderFilterState.INSTANCE.allows(x, z)) {
            RenderFilterState.INSTANCE.countBlocked();
            ci.cancel();
        } else {
            ActiveTopLevelTracker.INSTANCE.onTopLevelAdded(x, z);
        }
    }

    @Inject(method = "rem", at = @At("HEAD"), remap = false)
    private void voxyrenderfilter$trackTopLevelRemoved(int x, int z, CallbackInfo ci) {
        ActiveTopLevelTracker.INSTANCE.onTopLevelRemoved(x, z);
    }
}
