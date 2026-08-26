package dev.whisperlyric.voxyrenderfilter.map;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Block -> map color mapping (ARGB). Uses vanilla {@code Block.defaultMapColor().col} as fallback,
 * covering all blocks with vanilla-style colors; MapColor.NONE (air, barrier, ...) maps to 0 = transparent.
 */
public final class BlockColors {

    private BlockColors() {
    }

    /** Block state -> map color (ARGB); 0 means transparent (no map color). */
    public static int argbFor(BlockState state) {
        MapColor mc = state.getBlock().defaultMapColor();
        if (mc == MapColor.NONE) {
            return 0;
        }
        return soften(mc.col);
    }

    /**
     * Soften a vanilla map color: desaturate (40/60 mix with luminance) and dim to 85%.
     * Vanilla colors (e.g. grass 0x7FBF00) are harsh lime and look better softened at map scale.
     */
    private static int soften(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int gray = (r * 299 + g * 587 + b * 114) / 1000;
        int sr = (r * 40 + gray * 60) / 100;
        int sg = (g * 40 + gray * 60) / 100;
        int sb = (b * 40 + gray * 60) / 100;
        int fr = sr * 85 / 100;
        int fg = sg * 85 / 100;
        int fb = sb * 85 / 100;
        return 0xFF000000 | (fr << 16) | (fg << 8) | fb;
    }

    /** ARGB -> ABGR (used by com.mojang.blaze3d.platform.NativeImage.setPixelABGR). */
    public static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF0000) >> 16) | ((argb & 0xFF) << 16);
    }
}
