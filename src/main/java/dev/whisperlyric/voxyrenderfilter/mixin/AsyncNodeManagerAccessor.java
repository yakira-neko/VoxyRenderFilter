package dev.whisperlyric.voxyrenderfilter.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.locks.StampedLock;

/**
 * Exposes {@link AsyncNodeManager}'s pending-removal set ({@code tlnRem}) and its lock so that
 * {@code RenderNodeRefresh} can confirm a removal has actually been processed by the background
 * thread before a delayed rebuild. This prevents {@code addTopLevel} and an unprocessed
 * {@code removeTopLevel} from cancelling each other out, leaving nodes never rebuilt or in a mismatched state.
 */
@Mixin(AsyncNodeManager.class)
public interface AsyncNodeManagerAccessor {

    @Accessor("tlnRem")
    LongOpenHashSet voxyrenderfilter$getTlnRem();

    @Accessor("tlnLock")
    StampedLock voxyrenderfilter$getTlnLock();
}
