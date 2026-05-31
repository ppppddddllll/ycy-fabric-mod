package com.ycy.fabric.event;

import com.ycy.fabric.config.EventMapping;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry that holds all event-to-command mappings
 * Thread-safe for concurrent access
 */
public class EventRegistry {
    private final Map<String, EventMapping> mappingByEventId = new LinkedHashMap<>();
    private final List<EventMapping> mappings = new CopyOnWriteArrayList<>();

    /**
     * Register an event mapping. Overwrites if same eventId already exists.
     */
    public void register(EventMapping mapping) {
        mappings.removeIf(m -> m.getEventId().equals(mapping.getEventId()));
        mappings.add(mapping);
        mappingByEventId.put(mapping.getEventId(), mapping);
    }

    /**
     * Find a mapping by event ID
     */
    public Optional<EventMapping> findByEventId(String eventId) {
        return Optional.ofNullable(mappingByEventId.get(eventId));
    }

    /**
     * Find the mapping and check if triggerable (not in cooldown, enabled)
     */
    public Optional<EventMapping> findTriggerable(String eventId) {
        return findByEventId(eventId).filter(EventMapping::canTrigger);
    }

    /**
     * Remove a mapping by event ID
     */
    public void remove(String eventId) {
        mappings.removeIf(m -> m.getEventId().equals(eventId));
        mappingByEventId.remove(eventId);
    }

    /**
     * Get all registered mappings
     */
    public List<EventMapping> getMappings() {
        return new ArrayList<>(mappings);
    }

    /**
     * Toggle enabled state for an event
     */
    public boolean toggleEnabled(String eventId) {
        return findByEventId(eventId).map(m -> {
            m.setEnabled(!m.isEnabled());
            return m.isEnabled();
        }).orElse(false);
    }
}
