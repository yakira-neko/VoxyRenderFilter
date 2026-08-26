package dev.whisperlyric.voxyrenderfilter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent client config (single JSON file at <gameDir>/config/voxyrenderfilter.json).
 * Fields are volatile so voxy/worker threads can read them safely.
 */
public final class VrfConfig {

    public enum ScanMode {
        /** Continuously refresh the map cache around the player (vanilla render distance) in the background. */
        AUTO,
        /** Only rescan when the map's rescan action is used; otherwise cached images load directly. */
        MANUAL
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final VrfConfig INSTANCE = new VrfConfig();

    public volatile ScanMode scanMode = ScanMode.AUTO;
    public volatile int backgroundAlpha = 255;
    /**
     * IP mapping rules for multi-line servers: every cache path op (create/read/update/delete) of
     * the source ips is redirected to the target ip's folder. Format:
     *   "ip1": ["ip2", "ip3"]   -- ip2/ip3 share ip1's cache.
     */
    public volatile Map<String, List<String>> ipMappings = new LinkedHashMap<>();

    private VrfConfig() {
    }

    public void load() {
        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            VrfConfig loaded = GSON.fromJson(Files.readString(file), VrfConfig.class);
            if (loaded != null) {
                if (loaded.scanMode != null) {
                    this.scanMode = loaded.scanMode;
                }
                this.backgroundAlpha = loaded.backgroundAlpha;
                if (loaded.ipMappings != null) {
                    this.ipMappings = loaded.ipMappings;
                }
            }
        } catch (Exception ignored) {
            // Corrupt config falls back to defaults
        }
    }

    public void save() {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException ignored) {
            // Persisting is best-effort
        }
    }

    /**
     * Applies the IP mapping to a server address: when the address (or its sanitized form) is listed
     * as a source of a mapping, the mapped target's sanitized id is returned; otherwise the
     * sanitized address itself. All cache CRUD derives its folder from this value.
     */
    public String mapServerIp(String rawIp) {
        String key = sanitize(rawIp);
        Map<String, List<String>> mappings = this.ipMappings;
        if (mappings == null || mappings.isEmpty()) {
            return key;
        }
        for (Map.Entry<String, List<String>> e : mappings.entrySet()) {
            String target = sanitize(e.getKey());
            List<String> sources = e.getValue();
            if (sources != null) {
                for (String src : sources) {
                    if (sanitize(src).equals(key)) {
                        return target;
                    }
                }
            }
        }
        return key;
    }

    /** Keeps an address filesystem-safe (alphanumerics, dashes, underscores only). */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static Path configFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("voxyrenderfilter.json");
    }
}
