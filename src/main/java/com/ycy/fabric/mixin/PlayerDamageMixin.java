package com.ycy.fabric.mixin;

import com.ycy.fabric.YcyModClient;
import com.ycy.fabric.config.EventMapping;
import com.ycy.fabric.event.GameEventType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept player damage with precise control
 * Complements the Fabric API event handlers for more accurate event detection
 */
@Mixin(PlayerEntity.class)
public class PlayerDamageMixin {

    /**
     * Intercept damage application to trigger YOKONEX events
     */
    @Inject(method = "damage", at = @At("HEAD"))
    private void onPlayerDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        PlayerEntity player = (PlayerEntity) (Object) this;

        // Only trigger if mod is enabled
        if (!YcyModClient.isModEnabled()) return;

        var registry = YcyModClient.getEventRegistry();

        // Explosion detection (more precise than Fabric API)
        if (source.isOf(DamageTypes.EXPLOSION) || source.isOf(DamageTypes.PLAYER_EXPLOSION)) {
            registry.findTriggerable(GameEventType.EXPLOSION_NEARBY.getId()).ifPresent(mapping -> {
                mapping.markTriggered();
                YcyModClient.LOGGER.debug("[YCY] Explosion event triggered -> {}", mapping.getCommandId());
            });
        }
    }
}
