package dev.whisperlyric.voxyrenderfilter.filter;

import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局过滤状态（单例）。
 * <p>
 * 启用后仅允许渲染矩形内的 TLN 列，其余列在 {@code RenderDistanceTracker.add}
 * 阶段被拦截，voxy 不会为其创建顶层节点。
 */
public final class RenderFilterState {

    public static final RenderFilterState INSTANCE = new RenderFilterState();

    private volatile RectFilter filter;
    private final AtomicLong blockedCount = new AtomicLong();

    private RenderFilterState() {
    }

    public boolean isEnabled() {
        return this.filter != null;
    }

    public RectFilter getFilter() {
        return this.filter;
    }

    /** 方块坐标 -> TLN 列坐标。 */
    public static int blockToTln(int blockCoord) {
        return blockCoord >> 9;
    }

    public void setRect(int blockX1, int blockZ1, int blockX2, int blockZ2) {
        int x1 = blockToTln(blockX1);
        int z1 = blockToTln(blockZ1);
        int x2 = blockToTln(blockX2);
        int z2 = blockToTln(blockZ2);
        this.filter = new RectFilter(Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2));
    }

    public void clear() {
        this.filter = null;
        this.blockedCount.set(0);
    }

    public boolean allows(int tlnX, int tlnZ) {
        RectFilter f = this.filter;
        return f == null || f.contains(tlnX, tlnZ);
    }

    public void countBlocked() {
        this.blockedCount.incrementAndGet();
    }

    public long getBlockedCount() {
        return this.blockedCount.get();
    }

    /** HUD 用的一行状态文本，未启用返回 null（不绘制）。 */
    public Component describeHud() {
        RectFilter f = this.filter;
        if (f == null) {
            return null;
        }
        return Component.translatable("voxyrenderfilter.hud.active",
                f.minX(), f.minZ(), f.maxX(), f.maxZ(), this.blockedCount.get());
    }
}
