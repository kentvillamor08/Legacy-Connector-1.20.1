/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import legacyconnector.v1_20_1.ForgeApiTail1201;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Restores Forge 1.20.1's SleepingTimeCheckEvent while a player is already
 * sleeping. Sleep-start time checks are handled separately in
 * {@link ServerPlayerSleepingTimeCheckMixin}.
 */
@Mixin(Player.class)
public abstract class PlayerSleepingTimeCheckMixin {
    private static final String TICK = "tick()V";

    private static final String CAN_ENTITY_CONTINUE_SLEEPING =
            "Lnet/neoforged/neoforge/event/EventHooks;canEntityContinueSleeping("
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;)Z";

    @WrapOperation(
            method = TICK,
            at = @At(
                    value = "INVOKE",
                    target = CAN_ENTITY_CONTINUE_SLEEPING
            ),
            require = 1
    )
    private boolean legacyconnector$dispatchSleepingTimeCheckDuringTick(
            LivingEntity sleeper,
            Player.BedSleepingProblem targetProblem,
            Operation<Boolean> original
    ) {
        Player player = (Player) (Object) this;
        boolean mayContinueSleeping =
                ForgeApiTail1201.onSleepingTimeCheck(player, player.getSleepingPos());
        Player.BedSleepingProblem projectedProblem = mayContinueSleeping
                ? null
                : Player.BedSleepingProblem.NOT_POSSIBLE_NOW;

        return original.call(sleeper, projectedProblem);
    }
}
