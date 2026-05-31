package com.ycy.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ycy.fabric.YcyModClient;
import com.ycy.fabric.event.EventRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Config manager: settings.json + events.json
 * On first run, copies default_events.json from JAR to config dir
 */
public class ConfigManager {
    private static final String SETTINGS_FILE = "settings.json";
    private static final String EVENTS_FILE = "events.json";
    private static final String DEFAULT_EVENTS_RESOURCE = "assets/ycy/default_events.json";
    private static final String DEFAULT_URL = "ws://localhost:18790";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configDir;
    private String savedUrl = DEFAULT_URL;
    private String savedUid = "";
    private String savedToken = "";

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
        try { Files.createDirectories(configDir); } catch (IOException e) {
            YcyModClient.LOGGER.error("[YCY Config] Failed to create dir", e);
        }
        loadSettings();
    }

    // ==================== Settings ====================

    private void loadSettings() {
        Path path = configDir.resolve(SETTINGS_FILE);
        if (!Files.exists(path)) return;
        try {
            SettingsData obj = GSON.fromJson(Files.readString(path), SettingsData.class);
            if (obj != null) {
                if (obj.url != null && !obj.url.isEmpty()) savedUrl = obj.url;
                if (obj.uid != null) savedUid = obj.uid;
                if (obj.token != null) savedToken = obj.token;
            }
        } catch (Exception e) { YcyModClient.LOGGER.warn("[YCY Config] Load settings failed", e); }
    }

    private void saveSettings() {
        try {
            SettingsData data = new SettingsData();
            data.url = savedUrl;
            data.uid = savedUid;
            data.token = savedToken;
            Files.writeString(configDir.resolve(SETTINGS_FILE), GSON.toJson(data));
        } catch (IOException e) { YcyModClient.LOGGER.error("[YCY Config] Save settings failed", e); }
    }

    public String loadUrl() { return savedUrl; }
    public String loadUid() { return savedUid; }
    public String loadToken() { return savedToken; }

    public void saveUrl(String url) {
        if (url != null && !url.isBlank()) { savedUrl = url; saveSettings(); }
    }
    public void saveUid(String uid) { savedUid = uid != null ? uid : ""; saveSettings(); }
    public void saveToken(String token) { savedToken = token != null ? token : ""; saveSettings(); }

    // ==================== Events ====================

    /**
     * Load events from config/events.json.
     * On first run, copy default_events.json from JAR to config dir.
     */
    public void loadEvents(EventRegistry registry) {
        Path path = configDir.resolve(EVENTS_FILE);

        // First run: copy default events from JAR
        if (!Files.exists(path)) {
            copyDefaultEvents(path);
        }

        if (!Files.exists(path)) return;

        try {
            String json = Files.readString(path);
            Type listType = new TypeToken<List<EventMapping>>(){}.getType();
            List<EventMapping> events = GSON.fromJson(json, listType);
            if (events != null) {
                for (EventMapping e : events) {
                    registry.register(e);
                }
                YcyModClient.LOGGER.info("[YCY Config] Loaded {} events from {}", events.size(), path);
            }
        } catch (Exception e) {
            YcyModClient.LOGGER.error("[YCY Config] Failed to load events.json", e);
        }
    }

    /**
     * Save all events (including user-modified states) back to JSON
     */
    public void saveEvents(EventRegistry registry) {
        List<EventMapping> all = registry.getMappings();
        try {
            Files.writeString(configDir.resolve(EVENTS_FILE), GSON.toJson(all));
            YcyModClient.LOGGER.info("[YCY Config] Saved {} events", all.size());
        } catch (IOException e) {
            YcyModClient.LOGGER.error("[YCY Config] Failed to save events.json", e);
        }
    }

    private void copyDefaultEvents(Path dest) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_EVENTS_RESOURCE)) {
            if (in == null) {
                YcyModClient.LOGGER.warn("[YCY Config] Default events not found in JAR: {}", DEFAULT_EVENTS_RESOURCE);
                return;
            }
            Files.copy(in, dest);
            YcyModClient.LOGGER.info("[YCY Config] Copied default events to {}", dest);
        } catch (IOException e) {
            YcyModClient.LOGGER.error("[YCY Config] Failed to copy default events", e);
        }
    }

    public Path getConfigDir() { return configDir; }

    // ==================== Inner class ====================

    static class SettingsData {
        String url;
        String uid;
        String token;
    }
}
