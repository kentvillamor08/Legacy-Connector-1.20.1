/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Either;
import java.util.Optional;
import legacyconnector.v1_20_1.ForgeApiTail1201;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores the two distinct Forge 1.20.1 SleepingTimeCheckEvent consumers
 * on the NeoForge 1.21.1 Player path.
 *
 * <p>Player.tick() retains its original server-side sleeping branch and
 * continues through the native CanContinueSleepingEvent hook exactly once.
 * The sleep-start injection targets Player.startSleepInBed(BlockPos), which
 * ServerPlayer reaches only after its native CanPlayerSleepEvent eligibility
 * check has passed.</p>
 */
@Mixin(Player.class)
public abstract class PlayerSleepingTimeCheckMixin {
    private static final String TICK = "tick()V";

    private static final String START_SLEEP_IN_BED =
            "startSleepInBed(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;";

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

    @Inject(
            method = START_SLEEP_IN_BED,
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void legacyconnector$checkSleepingTimeBeforeSleepStarts(
            BlockPos bedPos,
            CallbackInfoReturnable<Either<Player.BedSleepingProblem, Unit>> cir
    ) {
        Player player = (Player) (Object) this;
        if (player.level().isClientSide) {
            return;
        }

        if (!ForgeApiTail1201.onSleepingTimeCheck(player, Optional.of(bedPos))) {
            cir.setReturnValue(Either.left(Player.BedSleepingProblem.NOT_POSSIBLE_NOW));
        }
    }
}
