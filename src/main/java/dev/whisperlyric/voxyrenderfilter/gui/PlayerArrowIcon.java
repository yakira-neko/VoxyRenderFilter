package dev.whisperlyric.voxyrenderfilter.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix3x2fStack;

import java.util.Arrays;

/**
 * Player marker: an equilateral triangle arrow (tip up) with a black outline, drawn the same
 * "rasterize once, rotate as a whole" way as Xaero's World Map, using only vanilla APIs: on first
 * render the triangle is rasterized once into a 32x32 {@link NativeImage} (2x supersampled for
 * crispness), uploaded as a {@link DynamicTexture}; each frame the GUI pose is translated to the
 * player position, rotated by yaw, and a single centered UV quad is blitted via
 * {@link GuiGraphicsExtractor#blit} (the GPU does the rotated rasterization).
 */
public final class PlayerArrowIcon {

    /** Texture edge (2x supersample: 12x12 icon -> 24x24, plus a 4px transparent margin). */
    private static final int TEX_SIZE = 32;
    /** Target icon edge in world pixels when drawn; scales as a whole. */
    private static final double DESIGN_SIZE = 12.0;

    private static GpuTextureView textureView;
    private static GpuSampler sampler;

    private PlayerArrowIcon() {
    }

    /** Draws the player arrow at (sx, sy) facing yawDeg (MC yaw, 0 = south). */
    public static void render(GuiGraphicsExtractor extractor, int sx, int sy, float yawDeg) {
        render(extractor, sx, sy, yawDeg, 1.0);
    }

    /** Same as {@link #render(GuiGraphicsExtractor, int, int, float)} with a whole-icon scale. */
    public static void render(GuiGraphicsExtractor extractor, int sx, int sy, float yawDeg, double scale) {
        if (textureView == null && !initTexture()) {
            return;
        }
        int size = (int) Math.round(DESIGN_SIZE * scale);
        if (size <= 0) {
            return;
        }
        // Tip defaults to up (north), rotated by (yaw+180) degrees around the player position,
        // matching the orientation of the old per-pixel version
        float rad = (float) Math.toRadians(yawDeg + 180.0);
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        try {
            pose.translate(sx, sy);
            pose.rotate(rad);
            // 26.x blit semantics: the 4 ints are absolute (x0,y0,x1,y1), the 4 floats are normalized
            // (u0,u1,v0,v1) UVs; the old (x,y,w,h)/(u0,v0,u1,v1) convention makes w/h the right edge
            // and degenerates the UV to a single texel column, hiding the arrow
            int half = size / 2;
            extractor.blit(textureView, sampler, -half, -half, half, half, 0f, 1f, 0f, 1f);
        } finally {
            pose.popMatrix();
        }
    }

    /** Creates and uploads the arrow texture on first use (render thread). */
    private static synchronized boolean initTexture() {
        if (textureView != null) {
            return true;
        }
        try {
            NativeImage img = new NativeImage(TEX_SIZE, TEX_SIZE, true);
            // 2x supersample coords (the original 12x12 doubled), offset (4,4) to center;
            // a narrower base and higher tip make the arrow sharper
            int[][] pts = {{16, 2}, {10, 28}, {22, 28}};
            fillPolygon(img, expand(pts, 3), 0xFF000000); // outline first (solid black)
            fillPolygon(img, pts, 0xAAFFFFFF);            // translucent white body (~60%), background shows through
            DynamicTexture tex = new DynamicTexture(() -> "voxyrenderfilter_player_arrow", img);
            tex.upload();
            textureView = tex.getTextureView();
            sampler = tex.getSampler();
            return true;
        } catch (RuntimeException ignored) {
            // upload failed: stay uninitialized, retried on the next call
            return false;
        }
    }

    /** Moves each vertex away from the centroid by dist (source texture grid, for the outline). */
    private static int[][] expand(int[][] pts, double dist) {
        double cx = 0;
        double cy = 0;
        for (int[] p : pts) {
            cx += p[0];
            cy += p[1];
        }
        cx /= pts.length;
        cy /= pts.length;
        int[][] out = new int[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            double dx = pts[i][0] - cx;
            double dy = pts[i][1] - cy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-6) {
                out[i][0] = pts[i][0];
                out[i][1] = pts[i][1];
                continue;
            }
            out[i][0] = (int) Math.round(pts[i][0] + dx / len * dist);
            out[i][1] = (int) Math.round(pts[i][1] + dy / len * dist);
        }
        return out;
    }

    /** Scanline polygon fill (even-odd rule), writes ABGR-packed colors into the NativeImage. */
    private static void fillPolygon(NativeImage img, int[][] pts, int abgr) {
        int n = pts.length;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int[] p : pts) {
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double[] xs = new double[n];
        for (int y = Math.max(0, minY); y <= Math.min(TEX_SIZE - 1, maxY); y++) {
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                int[] a = pts[i];
                int[] b = pts[(i + 1) % n];
                if ((a[1] <= y && b[1] > y) || (b[1] <= y && a[1] > y)) {
                    xs[cnt++] = a[0] + (double) (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
                }
            }
            Arrays.sort(xs, 0, cnt);
            for (int i = 0; i + 1 < cnt; i += 2) {
                int x0 = (int) Math.ceil(xs[i]);
                int x1 = (int) Math.floor(xs[i + 1]);
                if (x1 < x0) {
                    continue;
                }
                for (int x = Math.max(0, x0); x <= Math.min(TEX_SIZE - 1, x1); x++) {
                    img.setPixel(x, y, abgr);
                }
            }
        }
    }
}
