package dev.whisperlyric.voxyrenderfilter.client;

import dev.whisperlyric.voxyrenderfilter.command.VoxyRenderFilterCommands;
import dev.whisperlyric.voxyrenderfilter.config.VrfConfig;
import dev.whisperlyric.voxyrenderfilter.map.LodMapRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class VoxyRenderFilterClient implements ClientModInitializer {

    /** AUTO scan mode refreshes the map cache around the player about every 2 seconds. */
    private static final int REFRESH_INTERVAL = 40;

    private static int refreshTick;

    @Override
    public void onInitializeClient() {
        VrfConfig.INSTANCE.load();
        VoxyRenderFilterKeybinds.register();
        VoxyReloadService.register();
        VoxyRenderFilterCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++refreshTick >= REFRESH_INTERVAL) {
                refreshTick = 0;
                LodMapRenderer.INSTANCE.refreshNearPlayer();
            }
        });
        // Leaving a server/world: release the terrain textures and engine references so nothing
        // lingers in memory until the next join
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(LodMapRenderer.INSTANCE::invalidate));
    }
}
