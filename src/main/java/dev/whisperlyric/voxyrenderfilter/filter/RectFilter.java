package dev.whisperlyric.voxyrenderfilter.filter;

/**
 * Immutable rectangle with coordinates in TLN columns (lvl4 section coordinates,
 * i.e. block coordinates >> 9).
 */
public record RectFilter(int minX, int minZ, int maxX, int maxZ) {

    public boolean contains(int tlnX, int tlnZ) {
        return tlnX >= this.minX && tlnX <= this.maxX
            && tlnZ >= this.minZ && tlnZ <= this.maxZ;
    }
}
