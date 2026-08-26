package dev.whisperlyric.voxyrenderfilter.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.whisperlyric.voxyrenderfilter.filter.RectFilter;
import dev.whisperlyric.voxyrenderfilter.filter.RenderFilterState;
import dev.whisperlyric.voxyrenderfilter.purge.CachePurgeService;
import dev.whisperlyric.voxyrenderfilter.purge.RenderNodeRefresh;
import dev.whisperlyric.voxyrenderfilter.util.VoxyAccess;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Client commands:
 * /voxyrenderfilter purge <x1> <z1> <x2> <z2> - block coordinates; deletes the LOD cache inside the rect in the background
 * /voxyrenderfilter filter rect <x1> <z1> <x2> <z2> - block coordinates; sets the render filter rect
 * /voxyrenderfilter filter clear - clears the filter
 * /voxyrenderfilter filter status - shows the current state
 * The cache overlay map is opened with a keybind instead (default M, see VoxyRenderFilterKeybinds)
 */
public final class VoxyRenderFilterCommands {

    private VoxyRenderFilterCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(command()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return ClientCommands.literal("voxyrenderfilter")
                .then(ClientCommands.literal("purge")
                        .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            int x1 = IntegerArgumentType.getInteger(ctx, "x1");
                                                            int z1 = IntegerArgumentType.getInteger(ctx, "z1");
                                                            int x2 = IntegerArgumentType.getInteger(ctx, "x2");
                                                            int z2 = IntegerArgumentType.getInteger(ctx, "z2");
                                                            RectFilter rect = new RectFilter(
                                                                    Math.min(x1, x2),
                                                                    Math.min(z1, z2),
                                                                    Math.max(x1, x2),
                                                                    Math.max(z1, z2));
                                                            var engine = VoxyAccess.getCurrentEngine();
                                                            if (engine == null) {
                                                                ctx.getSource().sendFeedback(Component.translatable(
                                                                        "voxyrenderfilter.command.purge.failed"));
                                                                return 0;
                                                            }
                                                            FabricClientCommandSource source = ctx.getSource();
                                                            CachePurgeService.purge(engine, rect, count -> {
                                                                if (count > 0) {
                                                                    // Same as the map's delete: remove the affected columns' render nodes now and rebuild delayed
                                                                    RenderNodeRefresh.refreshForBlockRects(List.of(rect));
                                                                }
                                                                Minecraft.getInstance().execute(() ->
                                                                        source.sendFeedback(Component.translatable(
                                                                                count >= 0
                                                                                        ? "voxyrenderfilter.command.purge.done"
                                                                                        : "voxyrenderfilter.command.purge.failed",
                                                                                count)));
                                                            });
                                                            return 1;
                                                        }))))))
                .then(ClientCommands.literal("filter")
                        .then(ClientCommands.literal("rect")
                                .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(ctx -> {
                                                                    int x1 = IntegerArgumentType.getInteger(ctx, "x1");
                                                                    int z1 = IntegerArgumentType.getInteger(ctx, "z1");
                                                                    int x2 = IntegerArgumentType.getInteger(ctx, "x2");
                                                                    int z2 = IntegerArgumentType.getInteger(ctx, "z2");
                                                                    RenderFilterState.INSTANCE.setRect(x1, z1, x2, z2);
                                                                    // Restore columns the old filter removed, then remove/rebuild the newly blocked ones
                                                                    RenderNodeRefresh.clearFilterImmediately();
                                                                    RenderNodeRefresh.applyFilterImmediately();
                                                                    ctx.getSource().sendFeedback(Component.translatable(
                                                                            "voxyrenderfilter.command.filter.rect", x1, z1, x2, z2));
                                                                    return 1;
                                                                }))))))
                        .then(ClientCommands.literal("clear")
                                .executes(ctx -> {
                                    RenderFilterState.INSTANCE.clear();
                                    // Immediately rebuild render nodes previously removed by the filter
                                    RenderNodeRefresh.clearFilterImmediately();
                                    ctx.getSource().sendFeedback(Component.translatable(
                                            "voxyrenderfilter.command.filter.clear"));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("status")
                                .executes(ctx -> {
                                    RenderFilterState state = RenderFilterState.INSTANCE;
                                    if (!state.isEnabled()) {
                                        ctx.getSource().sendFeedback(Component.translatable(
                                                "voxyrenderfilter.command.filter.status.none"));
                                    } else {
                                        var f = state.getFilter();
                                        if (f != null) {
                                            ctx.getSource().sendFeedback(Component.translatable(
                                                    "voxyrenderfilter.command.filter.status.active",
                                                    f.minX(), f.minZ(), f.maxX(), f.maxZ(), state.getBlockedCount()));
                                        }
                                    }
                                    return 1;
                                })));
    }
}
