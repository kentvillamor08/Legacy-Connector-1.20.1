/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import legacyconnector.v1_20_1.ForgeApiTail1201;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.living.ZombieEvent$SummonAidEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.sugar.Local;

/**
 * Restores Forge 1.20.1 ZombieEvent.SummonAidEvent at the exact pre-gate
 * location in NeoForge 1.21.1 Zombie.hurt(DamageSource, float).
 *
 * <p>The pinned NeoForge 21.1.258 bytecode resolves the nullable attacker into
 * local slot 4, then immediately tests it with the first IFNULL instruction.
 * This injection runs after accepted damage and attacker fallback resolution,
 * but before the target's attacker, difficulty, chance and game-rule gates.</p>
 *
 * <p>DEFAULT leaves the target method untouched. DENY suppresses only the aid
 * spawn and returns the already-successful hurt result. ALLOW bypasses the
 * target's four reinforcement gates while retaining its native 50-attempt
 * placement, spawn-rule, collision, finalization and entity-add sequence.</p>
 */
@Mixin(Zombie.class)
public abstract class ZombieSummonAidEventMixin {
    private static final String HURT =
            "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z";

    @Shadow
    @Final
    private static ResourceLocation REINFORCEMENT_CALLER_CHARGE_ID;

    @Shadow
    @Final
    private static AttributeModifier ZOMBIE_REINFORCEMENT_CALLEE_CHARGE;

    @Inject(
            method = HURT,
            at = @At(
                    value = "JUMP",
                    opcode = Opcodes.IFNULL,
                    ordinal = 0
            ),
            cancellable = true,
            require = 1
    )
    private void legacyconnector$dispatchSummonAidEvent(
            DamageSource source,
            float amount,
            CallbackInfoReturnable<Boolean> cir,
            @Local(index = 3) ServerLevel serverLevel,
            @Local(index = 4) LivingEntity attacker
    ) {
        Zombie hurtZombie = (Zombie) (Object) this;
        int x = Mth.floor(hurtZombie.getX());
        int y = Mth.floor(hurtZombie.getY());
        int z = Mth.floor(hurtZombie.getZ());
        double summonChance =
                hurtZombie.getAttributeValue(Attributes.SPAWN_REINFORCEMENTS_CHANCE);

        ZombieEvent$SummonAidEvent event = ForgeApiTail1201.fireZombieSummonAid(
                hurtZombie,
                serverLevel,
                x,
                y,
                z,
                attacker,
                summonChance
        );

        Result result = legacyconnector.v1_20_1.ForgeApi1201.eventGetResult(event);
        if (result == Result.DENY) {
            // Damage was already accepted by Monster.hurt; only veto reinforcement.
            cir.setReturnValue(true);
        } else if (result == Result.ALLOW) {
            Zombie customAid = event.getCustomSummonedAid();
            legacyconnector$spawnForcedAid(serverLevel, hurtZombie, attacker, customAid, x, y, z);
            cir.setReturnValue(true);
        }
        // DEFAULT (and any unknown/null result) deliberately follows the original target code.
    }

    private void legacyconnector$spawnForcedAid(
            ServerLevel serverLevel,
            Zombie hurtZombie,
            LivingEntity attacker,
            Zombie customAid,
            int originX,
            int originY,
            int originZ
    ) {
        Level level = hurtZombie.level();
        Zombie aid = customAid == null ? new Zombie(level) : customAid;
        EntityType<?> type = aid.getType();

        // Preserve the target's exact 50-attempt search and random offset shape.
        for (int attempt = 0; attempt < 50; attempt++) {
            int x = originX + Mth.nextInt(hurtZombie.getRandom(), 7, 40)
                    * Mth.nextInt(hurtZombie.getRandom(), -1, 1);
            int y = originY + Mth.nextInt(hurtZombie.getRandom(), 7, 40)
                    * Mth.nextInt(hurtZombie.getRandom(), -1, 1);
            int z = originZ + Mth.nextInt(hurtZombie.getRandom(), 7, 40)
                    * Mth.nextInt(hurtZombie.getRandom(), -1, 1);
            BlockPos pos = new BlockPos(x, y, z);

            if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)) continue;
            if (!SpawnPlacements.checkSpawnRules(
                    type, serverLevel, MobSpawnType.REINFORCEMENT, pos, level.getRandom())) continue;

            aid.setPos((double) x, (double) y, (double) z);
            if (level.hasNearbyAlivePlayer((double) x, (double) y, (double) z, 7.0D)) continue;
            if (!level.isUnobstructed(aid)) continue;
            if (!level.noCollision(aid)) continue;
            if (level.containsAnyLiquid(aid.getBoundingBox())) continue;

            if (attacker != null) {
                aid.setTarget(attacker);
            }

            DifficultyInstance difficulty = level.getCurrentDifficultyAt(aid.blockPosition());
            aid.finalizeSpawn(
                    serverLevel,
                    difficulty,
                    MobSpawnType.REINFORCEMENT,
                    null
            );
            serverLevel.addFreshEntityWithPassengers(aid);

            // Match Zombie.hurt's caller/callee reinforcement charge updates.
            AttributeInstance callerChance =
                    hurtZombie.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
            AttributeModifier previous = callerChance.getModifier(REINFORCEMENT_CALLER_CHARGE_ID);
            double previousAmount = previous == null ? 0.0D : previous.amount();
            callerChance.removeModifier(REINFORCEMENT_CALLER_CHARGE_ID);
            callerChance.addPermanentModifier(new AttributeModifier(
                    REINFORCEMENT_CALLER_CHARGE_ID,
                    previousAmount - 0.05D,
                    AttributeModifier.Operation.ADD_VALUE
            ));

            aid.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)
                    .addPermanentModifier(ZOMBIE_REINFORCEMENT_CALLEE_CHARGE);
            break;
        }
    }
}
