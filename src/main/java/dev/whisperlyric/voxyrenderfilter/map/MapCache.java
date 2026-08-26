package dev.whisperlyric.voxyrenderfilter.map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.whisperlyric.voxyrenderfilter.config.VrfConfig;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Disk cache for the generated terrain map: one 512x512 PNG per TLN column (= one region file)
 * plus a sidecar file holding a hash of that column's stored section keys. On the next session the
 * hash is recomputed from disk; a match loads the cached image, a mismatch regenerates it.
 * Layout mirrors voxy's per-world storage, always split per dimension:
 *   singleplayer: <saveDir>/vrf_mapcache/<dimensionId>/<colX>_<colZ>.png + .hash
 *   multiplayer:  <gameDir>/vrf_mapcache/<serverIp>/<worldKey>/<dimensionId>/<colX>_<colZ>.png + .hash
 * The multiplayer worldKey (world seed) separates sub-servers behind a proxy such as Velocity,
 * which all share the proxy's IP, exactly like voxy's WorldIdentifier-keyed storage.
 * Known trade-off: in-place overwrites of the same section key (re-ingesting an unchanged area) are
 * not detected by a key-set hash; acceptable for the map's coarse overview, the hash is isolated
 * here so it can be upgraded to a sampled data hash later. Best-effort: any IO failure silently
 * falls back to regenerating without caching.
 */
public final class MapCache {

    public static final MapCache INSTANCE = new MapCache();

    /** Base cache root for the current world/server (no dimension part). */
    private volatile Path root;
    /** Currently viewed dimension id, e.g. "minecraft_overworld". */
    private volatile String dimensionId;
    /** Active cache dir = root/<dimensionId>. */
    private volatile Path dir;

    private MapCache() {
    }

    /** Points the cache at the current world and dimension; call on world/engine switch (render thread). */
    public void setWorld(Level level) {
        if (level == null) {
            this.root = null;
            this.dimensionId = null;
            this.dir = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String dimId = sanitize(level.dimension().identifier().toString());
        Path base;
        IntegratedServer singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) {
            // Singleplayer: <saveDir>/vrf_mapcache/<dimensionId>/...
            base = singleplayer.getWorldPath(LevelResource.ROOT).resolve("vrf_mapcache");
        } else {
            // Multiplayer: <gameDir>/vrf_mapcache/<serverIp>/<worldKey>/<dimensionId>/...
            // The worldKey (world seed) separates sub-servers behind a proxy (Velocity etc.):
            // they all share the proxy's IP, so the seed distinguishes them, mirroring how voxy
            // keys its storage by WorldIdentifier.
            ServerData server = mc.getCurrentServer();
            // The ip mapping (config) redirects multi-line servers' addresses to one cache folder;
            // every cache CRUD derives from this mapped id
            String serverId = server != null ? VrfConfig.INSTANCE.mapServerIp(server.ip) : "UNKNOWN";
            WorldIdentifier id = WorldIdentifier.of(level);
            String worldKey = id != null ? Long.toUnsignedString(id.biomeSeed) : "UNKNOWN";
            base = mc.gameDirectory.toPath().resolve("vrf_mapcache").resolve(serverId).resolve(worldKey);
        }
        this.root = base;
        this.dimensionId = dimId;
        this.dir = base.resolve(dimId);
    }

    /** The currently viewed dimension id, or null before the first setWorld. */
    public String getDimension() {
        return this.dimensionId;
    }

    /** Switches the active cache directory to another dimension under the same root. */
    public void setDimension(String dimId) {
        Path root = this.root;
        this.dimensionId = dimId;
        this.dir = root != null && dimId != null ? root.resolve(dimId) : null;
    }

    /** Sorted dimension ids under the current root that already contain cached images. */
    public java.util.List<String> listDimensions() {
        Path root = this.root;
        if (root == null || !Files.isDirectory(root)) {
            return java.util.List.of();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(sub -> {
                try (var inner = Files.list(sub)) {
                    if (inner.anyMatch(p -> p.getFileName().toString().endsWith(".png"))) {
                        result.add(sub.getFileName().toString());
                    }
                } catch (IOException ignored) {
                    // skip unreadable directory
                }
            });
        } catch (IOException ignored) {
            return java.util.List.of();
        }
        result.sort(null);
        return result;
    }

    /** Whether a cached image exists for this column. */
    public boolean hasCached(int colX, int colZ) {
        Path dir = this.dir;
        if (dir == null) {
            return false;
        }
        return Files.isRegularFile(dir.resolve(fileBase(colX, colZ) + ".png"));
    }

    /** Deletes the cached image and hash of a column (forces regeneration on next generation). */
    public void deleteImage(int colX, int colZ) {
        Path dir = this.dir;
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(fileBase(colX, colZ) + ".png"));
            Files.deleteIfExists(dir.resolve(fileBase(colX, colZ) + ".hash"));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Deterministic hash of a column's stored section keys (order-independent). */
    public static long computeHash(List<Long> keys) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64 offset basis
        long[] sorted = keys.stream().mapToLong(Long::longValue).sorted().toArray();
        for (long k : sorted) {
            for (int i = 0; i < 8; i++) {
                h ^= (k >>> (i * 8)) & 0xFFL;
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    /** Whether a cached image for this column is still valid for the given hash. */
    public boolean hashMatches(int colX, int colZ, long hash) {
        Path dir = this.dir;
        if (dir == null) {
            return false;
        }
        try {
            Path hashFile = dir.resolve(fileBase(colX, colZ) + ".hash");
            if (!Files.isRegularFile(hashFile)) {
                return false;
            }
            return Long.parseUnsignedLong(Files.readString(hashFile).trim(), 16) == hash;
        } catch (Exception e) {
            return false;
        }
    }

    /** Loads the cached column image, or null when absent/unreadable. */
    public NativeImage loadImage(int colX, int colZ) {
        Path dir = this.dir;
        if (dir == null) {
            return null;
        }
        try {
            Path file = dir.resolve(fileBase(colX, colZ) + ".png");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return NativeImage.read(Files.newInputStream(file));
        } catch (Exception e) {
            return null;
        }
    }

    /** Writes the column image and its section-key hash; failures are ignored (best-effort). */
    public void saveImage(int colX, int colZ, NativeImage image, long hash) {
        Path dir = this.dir;
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(fileBase(colX, colZ) + ".png"));
            Files.writeString(dir.resolve(fileBase(colX, colZ) + ".hash"), Long.toUnsignedString(hash, 16));
        } catch (IOException ignored) {
            // Cache is best-effort; the freshly generated image is still handed to the renderer
        }
    }

    private static String fileBase(int colX, int colZ) {
        return colX + "_" + colZ;
    }

    /** Keeps the world id filesystem-safe (alphanumerics, dashes, underscores only). */
    private static String sanitize(String worldKey) {
        return worldKey.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
