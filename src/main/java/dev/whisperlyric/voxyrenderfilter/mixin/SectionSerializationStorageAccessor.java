package dev.whisperlyric.voxyrenderfilter.mixin;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link SectionSerializationStorage} 的私有 backend 字段，
 * 以便删除缓存时调用 {@link StorageBackend#deleteSectionData(long)}。
 * voxy 类不做映射，因此 remap = false。
 */
@Mixin(SectionSerializationStorage.class)
public interface SectionSerializationStorageAccessor {

    @Accessor(value = "backend", remap = false)
    StorageBackend voxyrenderfilter$getBackend();
}
