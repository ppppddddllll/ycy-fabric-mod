package com.ycy.fabric.client;

import com.ycy.fabric.YcyModClient;
import com.ycy.fabric.gui.YcyConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Registers keyboard shortcuts for the YCY Link mod
 */
public class ModKeyBindings {
    public static final String CATEGORY = "category.ycy_link";

    public static KeyBinding OPEN_GUI_KEY;
    public static KeyBinding EMERGENCY_STOP_KEY;

    private static boolean guiKeyWasDown = false;
    private static boolean stopKeyWasDown = false;

    public static void register() {
        // Open config GUI: default Y key
        OPEN_GUI_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ycy_link.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                CATEGORY
        ));

        // Emergency stop: default N key
        EMERGENCY_STOP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ycy_link.emergency_stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                CATEGORY
        ));

        // Listen for key events in client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Open GUI key
            boolean guiDown = OPEN_GUI_KEY.isPressed();
            if (guiDown && !guiKeyWasDown && client.currentScreen == null) {
                client.setScreen(new YcyConfigScreen());
            }
            guiKeyWasDown = guiDown;

            // Emergency stop key
            boolean stopDown = EMERGENCY_STOP_KEY.isPressed();
            if (stopDown && !stopKeyWasDown) {
                com.ycy.fabric.bridge.BridgeManager.instance().stopAll();
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable("ycy.command.stop_sent"), true);
                }
            }
            stopKeyWasDown = stopDown;
        });

        YcyModClient.LOGGER.info("[YCY] Key bindings registered: Y=open gui, N=emergency stop");
    }
}
