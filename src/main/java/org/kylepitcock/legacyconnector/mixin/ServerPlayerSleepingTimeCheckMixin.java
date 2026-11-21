/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.Optional;
import legacyconnector.v1_20_1.ForgeApiTail1201;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Restores Forge 1.20.1's SleepingTimeCheckEvent at the exact day/time
 * decision inside ServerPlayer's sleep-validation supplier.
 *
 * <p>NeoForge 1.21.1 moved the vanilla sleep validation into the synthetic
 * {@code lambda$startSleepInBed$13(BlockPos)} method. Wrapping its Level.isDay
 * predicate replaces only the time-of-day decision, while preserving every
 * preceding bed/range/obstruction check and every subsequent safety check.
 * The enclosing ServerPlayer.startSleepInBed method still posts native
 * CanPlayerSleepEvent after the supplier completes, so native listeners retain
 * their final veto/override opportunity.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSleepingTimeCheckMixin {
    private static final String SLEEP_VALIDATION =
            "lambda$startSleepInBed$13(Lnet/minecraft/core/BlockPos;)"
                    + "Lcom/mojang/datafixers/util/Either;";

    private static final String LEVEL_IS_DAY =
            "Lnet/minecraft/world/level/Level;isDay()Z";

    @WrapOperation(
            method = SLEEP_VALIDATION,
            at = @At(
                    value = "INVOKE",
                    target = LEVEL_IS_DAY
            ),
            require = 1
    )
    private boolean legacyconnector$applySleepingTimeCheck(
            Level level,
            Operation<Boolean> original,
            @Local(argsOnly = true) BlockPos bedPos
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;

        // The target branches to NOT_POSSIBLE_NOW when this predicate is true.
        // Forge's result event instead returns "may sleep", so invert it here.
        // DEFAULT is evaluated by the helper after listeners have run.
        return !ForgeApiTail1201.onSleepingTimeCheck(player, Optional.of(bedPos));
    }
}
