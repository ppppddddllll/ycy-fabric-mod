package com.ycy.fabric.config;

import com.google.gson.annotations.SerializedName;

/**
 * Single event-to-command mapping with display name
 * Serialized to/from events.json
 */
public class EventMapping {
    @SerializedName("event_id")
    private String eventId;

    @SerializedName("command_id")
    private String commandId;

    @SerializedName("cooldown_ms")
    private long cooldownMs;

    @SerializedName("enabled")
    private boolean enabled;

    @SerializedName("display_name")
    private String displayName;

    // Runtime-only, not persisted
    private transient long lastTriggerTime = 0;

    public EventMapping() {}

    public EventMapping(String eventId, String commandId, long cooldownMs,
                        boolean enabled, String displayName) {
        this.eventId = eventId;
        this.commandId = commandId;
        this.cooldownMs = cooldownMs;
        this.enabled = enabled;
        this.displayName = displayName;
    }

    public boolean canTrigger() {
        if (!enabled) return false;
        return (System.currentTimeMillis() - lastTriggerTime) >= cooldownMs;
    }

    public void markTriggered() {
        lastTriggerTime = System.currentTimeMillis();
    }

    // Getters / Setters

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }

    public long getCooldownMs() { return cooldownMs; }
    public void setCooldownMs(long cooldownMs) { this.cooldownMs = cooldownMs; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
