package dev.whisperlyric.voxyrenderfilter.filter;

/**
 * 不可变矩形，坐标单位为 TLN 列（lvl4 section 坐标，即方块坐标 >> 9）。
 */
public record RectFilter(int minX, int minZ, int maxX, int maxZ) {

    public boolean contains(int tlnX, int tlnZ) {
        return tlnX >= this.minX && tlnX <= this.maxX
            && tlnZ >= this.minZ && tlnZ <= this.maxZ;
    }
}
