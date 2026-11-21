/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import legacyconnector.v1_20_1.ForgeApiTail1201;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Restores Forge 1.20.1's SleepingLocationCheckEvent at the precise location
 * validation call site in LivingEntity.checkBedExists().
 *
 * <p>The target method invokes EventHooks.canEntityContinueSleeping from
 * inside its sleeping-position Optional.map callback. The explicit Optional
 * guard is retained so a missing sleeping position never fabricates a
 * legacy event. The legacy result is projected to a target problem and the
 * original NeoForge hook is then called exactly once, preserving native
 * CanContinueSleepingEvent listeners.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySleepingLocationCheckMixin {
    private static final String CHECK_BED_EXISTS = "checkBedExists()Z";

    private static final String CAN_ENTITY_CONTINUE_SLEEPING =
            "Lnet/neoforged/neoforge/event/EventHooks;canEntityContinueSleeping("
                    + "Lnet/minecraft/world/entity/LivingEntity;"
                    + "Lnet/minecraft/world/entity/player/Player$BedSleepingProblem;)Z";

    @WrapOperation(
            method = CHECK_BED_EXISTS,
            at = @At(
                    value = "INVOKE",
                    target = CAN_ENTITY_CONTINUE_SLEEPING
            ),
            require = 1
    )
    private boolean legacyconnector$dispatchSleepingLocationCheck(
            LivingEntity sleeper,
            Player.BedSleepingProblem targetProblem,
            Operation<Boolean> original
    ) {
        Optional<BlockPos> sleepingPos = sleeper.getSleepingPos();
        if (sleepingPos.isEmpty()) {
            return original.call(sleeper, targetProblem);
        }

        boolean maySleepAtLocation =
                ForgeApiTail1201.onSleepingLocationCheck(sleeper, sleepingPos.get());
        Player.BedSleepingProblem projectedProblem = maySleepAtLocation
                ? null
                : Player.BedSleepingProblem.NOT_POSSIBLE_HERE;

        return original.call(sleeper, projectedProblem);
    }
}
