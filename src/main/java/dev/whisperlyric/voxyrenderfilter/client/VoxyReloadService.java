package dev.whisperlyric.voxyrenderfilter.client;

import dev.whisperlyric.voxyrenderfilter.mixin.VoxyInstanceAccessor;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * On server switch, frees a voxy engine only when it is completely idle. voxy holds a reference
 * count on engines; freeing an in-use one crashes on the next chunk unload, so such engines are
 * left to voxy's own {@code cleanIdle()}. The world identifier (seed) differs across sub-servers,
 * so voxy already reads the matching cache without intervention; this is a safe fallback only.
 */
public final class VoxyReloadService {

    private VoxyReloadService() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> reloadCurrentEngine(client)));
    }

    private static void reloadCurrentEngine(Minecraft client) {
        Level level = client.level;
        if (level == null) {
            return;
        }
        WorldIdentifier id = WorldIdentifier.of(level);
        if (id == null) {
            return;
        }
        VoxyInstance instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning()) {
            return;
        }
        VoxyInstanceAccessor accessor = (VoxyInstanceAccessor) instance;
        HashMap<WorldIdentifier, WorldEngine> worlds = accessor.voxyrenderfilter$getActiveWorlds();
        StampedLock lock = accessor.voxyrenderfilter$getActiveWorldLock();
        long stamp = lock.writeLock();
        try {
            WorldEngine old = worlds.get(id);
            if (old == null || !old.isLive() || old.isWorldUsed()) {
                return; // Engines still in use are left to voxy's lifecycle management
            }
            worlds.remove(id);
            old.free(); // An idle engine (no references, no sections) is safe to free here
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
