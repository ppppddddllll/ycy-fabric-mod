package com.ycy.fabric.event;

import com.ycy.fabric.config.EventMapping;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

import java.util.function.Consumer;

/**
 * Fabric API event handlers dispatching to EventRegistry
 * Covers all events from the YOKONEX protocol
 */
public class EventHandlers {

    public static void register(EventRegistry registry, Consumer<EventMapping> callback) {

        // --- Damage events ---
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof PlayerEntity)) return true;

            trigger(registry, GameEventType.PLAYER_DAMAGE.getId(), callback);

            if (source.isOf(DamageTypes.ON_FIRE) || source.isOf(DamageTypes.IN_FIRE) || source.isOf(DamageTypes.LAVA))
                trigger(registry, GameEventType.PLAYER_ON_FIRE.getId(), callback);
            if (source.isOf(DamageTypes.DROWN))
                trigger(registry, GameEventType.PLAYER_DROWN.getId(), callback);
            if (source.isOf(DamageTypes.EXPLOSION) || source.isOf(DamageTypes.PLAYER_EXPLOSION))
                trigger(registry, GameEventType.EXPLOSION_NEARBY.getId(), callback);
            return true;
        });

        // --- Death / kill ---
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof PlayerEntity) {
                trigger(registry, GameEventType.PLAYER_DEATH.getId(), callback);
            }
            if (source.getAttacker() instanceof PlayerEntity && !(entity instanceof PlayerEntity)) {
                trigger(registry, GameEventType.ENTITY_KILLED.getId(), callback);
            }
        });

        // --- Player join/leave ---
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server)
                -> trigger(registry, GameEventType.PLAYER_JOIN.getId(), callback));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server)
                -> trigger(registry, GameEventType.PLAYER_LEAVE.getId(), callback));

        // --- Block events ---
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity)
                -> trigger(registry, GameEventType.BLOCK_BREAK.getId(), callback));

        // --- Periodic checks ---
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (PlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.getHealth() > 0 && player.getHealth() <= 8.0f)
                    trigger(registry, GameEventType.PLAYER_LOW_HP.getId(), callback);

                if (player.getActiveStatusEffects().entrySet().stream()
                        .anyMatch(e -> e.getKey().getTranslationKey().contains("poison")))
                    trigger(registry, GameEventType.PLAYER_POISONED.getId(), callback);

                long hostileNearby = player.getWorld().getOtherEntities(player,
                        new Box(player.getBlockPos()).expand(5), e -> e instanceof HostileEntity).size();
                if (hostileNearby > 0)
                    trigger(registry, GameEventType.MOB_NEARBY.getId(), callback);
            }
        });
    }

    private static void trigger(EventRegistry registry, String eventId, Consumer<EventMapping> callback) {
        registry.findTriggerable(eventId).ifPresent(mapping -> {
            mapping.markTriggered();
            callback.accept(mapping);
        });
    }
}
