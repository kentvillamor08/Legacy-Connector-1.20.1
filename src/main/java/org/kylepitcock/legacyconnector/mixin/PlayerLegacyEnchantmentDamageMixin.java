package org.kylepitcock.legacyconnector.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.kylepitcock.legacyconnector.v1_20_1.migrate.LegacyEnchantments;
import org.kylepitcock.legacyconnector.v1_20_1.migrate.LegacyMobType;
import org.kylepitcock.legacyconnector.v1_20_1.migrate.MiscCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Restores Forge 1.20.1's stack-aware legacy enchantment bonus at the primary-target stage of Player.attack.
 *
 * <p>NeoForge 1.21.1's ServerPlayer.getEnchantedDamage uses the modern data-component effect pipeline. Legacy
 * enchantment definitions emitted by LegacyEnchantments have empty target effects, so their old Java hooks
 * must be added alongside that result. Wrap only ordinal 0: target Player.attack invokes getEnchantedDamage
 * again for sweep victims, whereas Forge 1.20.1 calculated the bonus once and reused it for the sweep.</p>
 */
@Mixin(Player.class)
public abstract class PlayerLegacyEnchantmentDamageMixin {
    @WrapOperation(
            method = "attack(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getEnchantedDamage(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/damagesource/DamageSource;)F",
                    ordinal = 0
            ),
            require = 1
    )
    private float legacyconnector$addLegacyPrimaryTargetBonus(
            Player player,
            Entity target,
            float damage,
            DamageSource source,
            Operation<Float> original
    ) {
        float modernDamage = original.call(player, target, damage, source);
        LegacyMobType type = target instanceof LivingEntity living
                ? MiscCompat.getMobType(living)
                : LegacyMobType.UNDEFINED;
        ItemStack weapon = player.getMainHandItem();
        return modernDamage + LegacyEnchantments.legacyDamageBonus(weapon, type);
    }
}
