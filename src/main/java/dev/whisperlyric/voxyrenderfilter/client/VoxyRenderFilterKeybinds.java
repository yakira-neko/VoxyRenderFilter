package dev.whisperlyric.voxyrenderfilter.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.whisperlyric.voxyrenderfilter.gui.MapScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Keybind: unbound by default; players set it in the Controls -> VoxyRenderFilter category.
 * Pressing the key toggles the cache overlay map (replaces the /voxyrenderfilter map command).
 */
public final class VoxyRenderFilterKeybinds {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("voxyrenderfilter", "map"));

    private static KeyMapping mapKey;

    private VoxyRenderFilterKeybinds() {
    }

    public static void register() {
        mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.voxyrenderfilter.map",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (mapKey.consumeClick()) {
                toggleMap(client);
            }
        });
    }

    private static void toggleMap(Minecraft client) {
        // 26.2: the screen accessors moved off Minecraft onto Gui
        if (client.gui.screen() instanceof MapScreen) {
            client.gui.setScreen(null);
        } else {
            client.gui.setScreen(new MapScreen());
        }
    }
}
