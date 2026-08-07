package dev.whisperlyric.voxyrenderfilter.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.whisperlyric.voxyrenderfilter.gui.MapScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * 快捷键：默认未绑定，玩家在 控制-&gt;VoxyRenderFilter 分类中自行设置。
 * 按键触发时打开/关闭缓存覆盖地图（代替 /voxyrenderfilter map 命令）。
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
        if (client.screen instanceof MapScreen) {
            client.setScreen(null);
        } else {
            client.setScreen(new MapScreen());
        }
    }
}
