package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 HUD 渲染入口绘制当前过滤状态一行文本。
 * <p>
 * 26.x 的 HUD 走 {@code GuiGraphicsExtractor}（抽离 + 帧末绘制），
 * 文本用绝对屏幕坐标，颜色必须为 8 位 ARGB。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"))
    private void voxyrenderfilter$drawFilterStatus(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        Component text = RenderFilterState.INSTANCE.describeHud();
        if (text == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 颜色必须显式 8 位 ARGB，否则高位 0 = alpha 0 = 全透明
        graphics.text(mc.font, text.getVisualOrderText(), 4, 4, 0xFFFFFFFF, true);
    }
}
