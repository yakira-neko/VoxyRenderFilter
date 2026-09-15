package dev.whisperlyric.voxyrenderfilter.mixin;

import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a one-line status text of the current filter state at the HUD rendering entry point.
 * In 26.2 the HUD was split out of {@code Gui} into {@link Hud}, which owns
 * {@code extractHotbarAndDecorations}; the HUD renders through {@code GuiGraphicsExtractor}
 * (extract, then draw at frame end), so text uses absolute screen coordinates and colors must be
 * 8-bit ARGB.
 */
@Mixin(Hud.class)
public abstract class GuiMixin {

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"))
    private void voxyrenderfilter$drawFilterStatus(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        Component text = RenderFilterState.INSTANCE.describeHud();
        if (text == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Color must be explicit 8-bit ARGB; a zero high byte means alpha 0 = fully transparent
        graphics.text(mc.font, text.getVisualOrderText(), 4, 4, 0xFFFFFFFF, true);
    }
}
