/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import legacyconnector.v1_20_1.ForgeApiTail1201;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.sugar.Local;

/**
 * Restores Forge 1.20.1's FillBucketEvent call site on the NeoForge 1.21.1
 * BucketItem path. The injection is after the POV ray trace and before the
 * target branches on MISS/BLOCK, matching the exact Forge factory placement.
 *
 * <p>NeoForge 21.1.258 does not provide this bucket event hook. A null result
 * leaves the target vanilla path untouched; canceled/ALLOW results return
 * directly from use() using Forge's event transaction helper.</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketItemForge1201FillBucketMixin {
    private static final String USE =
            "use(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;)"
                    + "Lnet/minecraft/world/InteractionResultHolder;";

    @Inject(
            method = USE,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/BlockHitResult;getType()"
                            + "Lnet/minecraft/world/phys/HitResult$Type;",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            ),
            cancellable = true,
            require = 1
    )
    private void legacyconnector$fireFillBucketEvent(
            Level level,
            Player player,
            net.minecraft.world.InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir,
            @Local(index = 4) ItemStack current,
            @Local(index = 5) BlockHitResult target
    ) {
        InteractionResultHolder<ItemStack> result =
                ForgeApiTail1201.onBucketUse(player, level, current, target);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
