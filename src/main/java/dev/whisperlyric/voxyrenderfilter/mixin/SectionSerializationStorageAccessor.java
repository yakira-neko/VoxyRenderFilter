package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link SectionSerializationStorage}'s private backend field so the cache deletion code can
 * call {@link StorageBackend#deleteSectionData(long)}. voxy classes are not mapped, hence remap = false.
 */
@Mixin(SectionSerializationStorage.class)
public interface SectionSerializationStorageAccessor {

    @Accessor(value = "backend", remap = false)
    StorageBackend voxyrenderfilter$getBackend();
}
