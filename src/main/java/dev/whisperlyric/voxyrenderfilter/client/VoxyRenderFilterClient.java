package dev.whisperlyric.voxyrenderfilter.client;

import dev.whisperlyric.voxyrenderfilter.command.VoxyRenderFilterCommands;
import net.fabricmc.api.ClientModInitializer;

public final class VoxyRenderFilterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VoxyRenderFilterKeybinds.register();
        VoxyReloadService.register();
        VoxyRenderFilterCommands.register();
    }
}
