package dev.whisperlyric.voxyrenderfilter.util;

import dev.whisperlyric.voxyrenderfilter.mixin.SectionSerializationStorageAccessor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;

public final class VoxyAccess {

    private VoxyAccess() {
    }

    /**
     * voxy WorldEngine of the current client world (null when not loaded or unavailable).
     */
    public static WorldEngine getCurrentEngine() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        WorldIdentifier identifier = WorldIdentifier.of(mc.level);
        if (identifier == null) {
            return null;
        }
        WorldEngine engine = identifier.getNullable();
        return (engine != null && engine.isLive()) ? engine : null;
    }

    /**
     * Disk backend of the current WorldEngine.
     * voxy's default storage runtime type is {@link SectionSerializationStorage}; a custom storage
     * configuration may use another type, in which case null is returned (callers treat it as failure).
     */
    public static StorageBackend getStorageBackend(WorldEngine engine) {
        if (engine == null || !(engine.storage instanceof SectionSerializationStorage storage)) {
            return null;
        }
        return ((SectionSerializationStorageAccessor) storage).voxyrenderfilter$getBackend();
    }
}
