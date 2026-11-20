/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import legacyconnector.v1_20_1.Forge1201ItemPickupContext;
import legacyconnector.v1_20_1.ForgeApiTail1201;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores Forge 1.20.1's pickup decision/notification contract while
 * retaining NeoForge 1.21.1's native Pre/Post events and live target gates.
 *
 * <p>Call-site/local anchors were inspected in the exact NeoForge 21.1.258
 * artifact (SHA-256
 * E51E65665D27F394077C02A5E0551A0CEEEBC419AEC44C05777F585F70585007).
 * The target is {@code ItemEntity.playerTouch(Player)}. Its Pre result is
 * produced by {@code ItemEntityPickupEvent.Pre.canPickup()}, its only
 * {@code Inventory.add(ItemStack)} call is the real insertion point, and
 * native Post occurs only after that insertion reports success.</p>
 *
 * <p>The legacy decision state is stored in a MixinExtras {@code @Share}
 * LocalRef for this exact method invocation. No static flag, item-keyed map,
 * or ThreadLocal is used; nested/reentrant pickups get independent state.</p>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityForge1201PickupMixin {
    private static final String PLAYER_TOUCH =
            "playerTouch(Lnet/minecraft/world/entity/player/Player;)V";
    private static final String PICKUP_CONTEXT = "legacyconnector$pickupContext";

    @Shadow
    private int pickupDelay;

    /**
     * Capture the source Forge outer gate before NeoForge Pre listeners run.
     * Forge posts its decision event when entry pickupDelay <= 0; the native
     * DEFAULT gate later still requires the live value to equal exactly zero.
     */
    @WrapMethod(method = PLAYER_TOUCH, require = 1)
    private void legacyconnector$wrapPlayerTouch(
            Player player,
            Operation<Void> original,
            @Share(PICKUP_CONTEXT) LocalRef<Forge1201ItemPickupContext> contextRef
    ) {
        Forge1201ItemPickupContext context =
                new Forge1201ItemPickupContext(!((ItemEntity) (Object) this).level().isClientSide, this.pickupDelay);
        contextRef.set(context);
        try {
            original.call(player);
        } finally {
            // Clear even when a listener or another wrapped operation throws.
            contextRef.set(null);
        }
    }

    /**
     * The target has already posted native Pre before calling canPickup().
     * Compose its result with the old Forge factory event here, before the
     * target copies the live stack and evaluates its live delay/owner gates.
     */
    @WrapOperation(
            method = PLAYER_TOUCH,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/event/entity/player/ItemEntityPickupEvent$Pre;"
                            + "canPickup()Lnet/neoforged/neoforge/common/util/TriState;"
            ),
            require = 1
    )
    private TriState legacyconnector$dispatchForgeDecisionAfterNativePre(
            ItemEntityPickupEvent.Pre event,
            Operation<TriState> original,
            Player player,
            @Share(PICKUP_CONTEXT) LocalRef<Forge1201ItemPickupContext> contextRef
    ) {
        TriState nativeResult = original.call(event);
        Forge1201ItemPickupContext context = contextRef.get();
        if (context == null) return nativeResult;

        ItemEntity self = (ItemEntity) (Object) this;
        return context.afterNativePre(
                nativeResult,
                () -> ForgeApiTail1201.onItemPickup(self, player)
        );
    }

    /**
     * Forge ALLOW skips only Inventory.add; it does not bypass the native
     * DEFAULT delay/target gate or alter native Pre. Returning true here lets
     * NeoForge proceed to the ordinary delta/notification tail with delta 0.
     */
    @WrapOperation(
            method = PLAYER_TOUCH,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"
            ),
            require = 1
    )
    private boolean legacyconnector$skipInventoryAddForForgeAllow(
            Inventory inventory,
            ItemStack stack,
            Operation<Boolean> original,
            Player player,
            @Share(PICKUP_CONTEXT) LocalRef<Forge1201ItemPickupContext> contextRef
    ) {
        Forge1201ItemPickupContext context = contextRef.get();
        if (context != null && context.shouldSkipInventoryAdd()) {
            return true;
        }
        return original.call(inventory, stack);
    }

    /**
     * ALLOW did not actually insert anything, so NeoForge's successful-add
     * Post event must not be fabricated for that path. All real insertions
     * retain the original target Post event unchanged.
     */
    @WrapOperation(
            method = PLAYER_TOUCH,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/event/EventHooks;fireItemPickupPost("
                            + "Lnet/minecraft/world/entity/item/ItemEntity;"
                            + "Lnet/minecraft/world/entity/player/Player;"
                            + "Lnet/minecraft/world/item/ItemStack;)V"
            ),
            require = 1
    )
    private void legacyconnector$suppressNativePostForForgeAllow(
            ItemEntity itemEntity,
            Player pickupPlayer,
            ItemStack copy,
            Operation<Void> original,
            Player touchingPlayer,
            @Share(PICKUP_CONTEXT) LocalRef<Forge1201ItemPickupContext> contextRef
    ) {
        Forge1201ItemPickupContext context = contextRef.get();
        if (context != null && context.shouldSuppressNativePost()) return;
        original.call(itemEntity, pickupPlayer, copy);
    }

    /**
     * The pinned target computes the transfer delta into local slot 4 and
     * keeps the pre-add stack copy in slot 6 immediately before Player.take.
     * Clone before setting the delta: native Post handlers have already seen
     * the original copy and must not observe it being mutated afterward.
     *
     * This injection is naturally skipped when Inventory.add fails, because
     * the target returns before reaching Player.take. Under legacy ALLOW the
     * wrapped add returns true, so the delta is exactly zero and the original
     * Forge PlayerEvent.ItemPickupEvent notification is still posted.
     */
    @Inject(
            method = PLAYER_TOUCH,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;take("
                            + "Lnet/minecraft/world/entity/Entity;I)V"
            ),
            require = 1
    )
    private void legacyconnector$postLegacyNotificationAtTransferDelta(
            Player player,
            CallbackInfo ci,
            @Local(index = 6) ItemStack preAddCopy,
            @Local(index = 4) int transferred,
            @Share(PICKUP_CONTEXT) LocalRef<Forge1201ItemPickupContext> contextRef
    ) {
        Forge1201ItemPickupContext context = contextRef.get();
        if (context == null || !context.shouldPostLegacyNotificationAtSuccessfulDelta()) return;

        ItemStack deltaStack = preAddCopy.copy();
        deltaStack.setCount(transferred);
        ForgeApiTail1201.firePlayerItemPickupEvent(
                player,
                (ItemEntity) (Object) this,
                deltaStack
        );
    }
}
