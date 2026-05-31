package com.ycy.fabric;

import com.ycy.fabric.bridge.BridgeManager;
import com.ycy.fabric.config.ConfigManager;
import com.ycy.fabric.config.EventMapping;
import com.ycy.fabric.event.EventHandlers;
import com.ycy.fabric.event.EventRegistry;
import com.ycy.fabric.command.YcyCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * YOKONEX Minecraft Link Fabric Mod v2
 * WebSocket → embedded bridge → Tencent IM → YOKONEX device
 */
public class YcyModClient implements ClientModInitializer {
    public static final String MOD_ID = "ycy-link";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigManager configManager;
    private static EventRegistry eventRegistry;
    private static boolean modEnabled = true;
    private static boolean bridgeReady = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[YCY] Initializing YOKONEX Link mod");

        // Config
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        configManager = new ConfigManager(configDir);

        // Event registry (loaded from events.json)
        eventRegistry = new EventRegistry();
        configManager.loadEvents(eventRegistry);

        // Key bindings
        com.ycy.fabric.client.ModKeyBindings.register();

        // Bridge (WebSocket + Node.js process)
        BridgeManager bridge = BridgeManager.instance();
        bridge.setWsUrl(configManager.loadUrl());
        bridge.initialize(configDir);
        bridge.setOnReady(() -> { bridgeReady = true; LOGGER.info("[YCY] Connected"); });
        bridge.setOnDisconnect(() -> { bridgeReady = false; LOGGER.warn("[YCY] Disconnected"); });

        // Events → commands
        EventHandlers.register(eventRegistry, this::onEventTriggered);

        // Commands
        YcyCommand.register();

        // Tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> BridgeManager.instance().tick());

        LOGGER.info("[YCY] Mod initialized. /ycy gui to configure, Y key shortcut.");
    }

    public void onEventTriggered(EventMapping mapping) {
        if (!modEnabled || !bridgeReady) return;
        mapping.markTriggered();
        BridgeManager.instance().sendCommand(mapping.getCommandId());
    }

    public static ConfigManager getConfigManager() { return configManager; }
    public static EventRegistry getEventRegistry() { return eventRegistry; }
    public static boolean isModEnabled() { return modEnabled; }
    public static void setModEnabled(boolean e) { modEnabled = e; }
    public static boolean isBridgeReady() { return bridgeReady; }
}
