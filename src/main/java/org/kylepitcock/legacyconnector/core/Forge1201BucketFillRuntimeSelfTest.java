/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.core;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.FillBucketEvent;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Opt-in dedicated-server checks for the real BucketItem.use mixin injection
 * and Forge 1.20.1 FillBucketEvent transaction.
 *
 * <p>The fake player looks straight up so the native bucket ray trace is a
 * deterministic MISS in the empty sky. DEFAULT and DENY must then fall
 * through to the original PASS result, while cancellation and ALLOW return
 * the Forge factory result before vanilla bucket logic runs.</p>
 *
 * <p>Run only in an isolated test server with
 * {@code -Dlegacyconnector.bucketFillBehaviorSelfTest=true}. The event is
 * observed through the real NeoForge event bus. The player's inventory and
 * temporary water-bucket drops are cleaned up before returning.</p>
 */
public final class Forge1201BucketFillRuntimeSelfTest {
    private static final int MAIN_INVENTORY_SLOTS = 36;
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;

    private Forge1201BucketFillRuntimeSelfTest() {}

    public static List<String> run(ServerLevel level) {
        List<String> failures = new ArrayList<>();
        BlockPos spawn = level.getSharedSpawnPos();
        FakePlayer player = FakePlayerFactory.get(
                level,
                new GameProfile(UUID.randomUUID(), "LegacyConnectorBucketProbe")
        );
        player.setPos(spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D);

        float previousPitch = player.getXRot();
        float previousYaw = player.getYRot();
        boolean previousInstabuild = player.getAbilities().instabuild;
        Set<UUID> preexistingWaterBucketDrops = waterBucketDropIds(level, player);
        ProbeListeners listeners = new ProbeListeners();
        NeoForge.EVENT_BUS.register(listeners);
        try {
            runCase(failures, listeners, player, "cancellation overrides ALLOW and returns FAIL", () -> {
                ItemStack held = new ItemStack(Items.BUCKET);
                ItemStack filled = new ItemStack(Items.WATER_BUCKET);
                InteractionResultHolder<ItemStack> outcome = useBucket(
                        level, player, listeners, held, new Directive(Event$Result.ALLOW, true, filled));

                requireInvocation(listeners);
                require(outcome.getResult() == InteractionResult.FAIL,
                        "canceled ALLOW result was " + outcome.getResult());
                require(outcome.getObject() == held, "cancellation did not return the original held bucket stack");
                require(held.getCount() == 1, "cancellation consumed the held bucket");
                require(count(player.getInventory(), Items.WATER_BUCKET) == 0,
                        "cancellation inserted a filled bucket into inventory");
            });

            runCase(failures, listeners, player, "creative ALLOW returns SUCCESS without consuming the bucket", () -> {
                player.getAbilities().instabuild = true;
                ItemStack held = new ItemStack(Items.BUCKET);
                ItemStack filled = new ItemStack(Items.WATER_BUCKET);
                InteractionResultHolder<ItemStack> outcome = useBucket(
                        level, player, listeners, held, new Directive(Event$Result.ALLOW, false, filled));

                requireInvocation(listeners);
                require(outcome.getResult() == InteractionResult.SUCCESS,
                        "creative ALLOW result was " + outcome.getResult());
                require(outcome.getObject() == held, "creative ALLOW did not return the held stack");
                require(held.getCount() == 1, "creative ALLOW consumed the held bucket");
                require(count(player.getInventory(), Items.WATER_BUCKET) == 0,
                        "creative ALLOW inserted the supplied filled bucket");
            });

            runCase(failures, listeners, player, "survival ALLOW replaces a single empty bucket in the hand", () -> {
                ItemStack held = new ItemStack(Items.BUCKET);
                ItemStack filled = new ItemStack(Items.WATER_BUCKET);
                InteractionResultHolder<ItemStack> outcome = useBucket(
                        level, player, listeners, held, new Directive(Event$Result.ALLOW, false, filled));

                requireInvocation(listeners);
                require(outcome.getResult() == InteractionResult.SUCCESS,
                        "single-bucket ALLOW result was " + outcome.getResult());
                require(held.isEmpty(), "single-bucket ALLOW did not consume the empty bucket");
                require(outcome.getObject() == filled, "single-bucket ALLOW did not return the listener's filled bucket");
                require(outcome.getObject().getCount() == 1, "returned filled bucket count was " + outcome.getObject().getCount());
                require(count(player.getInventory(), Items.WATER_BUCKET) == 0,
                        "single-bucket ALLOW inserted a filled bucket instead of returning it");
            });

            runCase(failures, listeners, player, "survival ALLOW inserts a filled bucket for a multi-bucket stack", () -> {
                ItemStack held = new ItemStack(Items.BUCKET, 2);
                ItemStack filled = new ItemStack(Items.WATER_BUCKET);
                InteractionResultHolder<ItemStack> outcome = useBucket(
                        level, player, listeners, held, new Directive(Event$Result.ALLOW, false, filled));

                requireInvocation(listeners);
                require(outcome.getResult() == InteractionResult.SUCCESS,
                        "multi-bucket ALLOW result was " + outcome.getResult());
                require(outcome.getObject() == held, "multi-bucket ALLOW did not return the remaining held stack");
                require(held.getCount() == 1, "multi-bucket ALLOW left " + held.getCount() + " empty buckets");
                require(count(player.getInventory(), Items.WATER_BUCKET) == 1,
                        "multi-bucket ALLOW did not insert exactly one filled bucket");
            });

            runCase(failures, listeners, player, "survival ALLOW drops the filled bucket when inventory is full", () -> {
                fillMainInventory(player.getInventory(), Items.DIAMOND, 64);
                ItemStack held = new ItemStack(Items.BUCKET, 2);
                ItemStack filled = new ItemStack(Items.WATER_BUCKET);
                InteractionResultHolder<ItemStack> outcome = useBucket(
                        level, player, listeners, held, new Directive(Event$Result.ALLOW, false, filled));

                requireInvocation(listeners);
                require(outcome.getResult() == InteractionResult.SUCCESS,
                        "full-inventory ALLOW result was " + outcome.getResult());
                require(outcome.getObject() == held && held.getCount() == 1,
                        "full-inventory ALLOW did not retain one empty bucket");
                require(count(player.getInventory(), Items.WATER_BUCKET) == 0,
                        "full-inventory ALLOW inserted a filled bucket despite no free slot");

                List<ItemEntity> newDrops = new ArrayList<>();
                for (ItemEntity item : waterBucketDrops(level, player)) {
                    if (!preexistingWaterBucketDrops.contains(item.getUUID())) newDrops.add(item);
                }
                require(newDrops.size() == 1,
                        "expected one dropped filled bucket, found " + newDrops.size());
                require(newDrops.get(0).getItem().getCount() == 1,
                        "dropped filled bucket count was " + newDrops.get(0).getItem().getCount());
            });

            for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY)) {
                runCase(failures, listeners, player, "legacy " + result + " falls through to vanilla MISS/PASS", () -> {
                    ItemStack held = new ItemStack(Items.BUCKET);
                    InteractionResultHolder<ItemStack> outcome = useBucket(
                            level, player, listeners, held, new Directive(result, false, null));

                    requireInvocation(listeners);
                    require(outcome.getResult() == InteractionResult.PASS,
                            "legacy " + result + " did not preserve native MISS/PASS: " + outcome.getResult());
                    require(outcome.getObject() == held, "legacy " + result + " replaced the held stack");
                    require(held.getCount() == 1, "legacy " + result + " changed the held bucket count");
                });
            }
        } finally {
            NeoForge.EVENT_BUS.unregister(listeners);
            player.getAbilities().instabuild = previousInstabuild;
            player.setXRot(previousPitch);
            player.setYRot(previousYaw);
            player.getInventory().clearContent();
            for (ItemEntity item : waterBucketDrops(level, player)) {
                if (!preexistingWaterBucketDrops.contains(item.getUUID())) item.discard();
            }
        }

        return failures;
    }

    private static InteractionResultHolder<ItemStack> useBucket(
            ServerLevel level,
            FakePlayer player,
            ProbeListeners listeners,
            ItemStack held,
            Directive directive
    ) {
        // Straight up ensures the vanilla ray trace returns MISS without world edits.
        player.setXRot(-90.0F);
        player.setYRot(0.0F);
        player.setItemInHand(HAND, held);
        listeners.arm(held, directive);
        return Items.BUCKET.use(level, player, HAND);
    }

    private static void requireInvocation(ProbeListeners listeners) {
        require(listeners.eventCount == 1,
                "BucketItem.use did not dispatch exactly one FillBucketEvent (count " + listeners.eventCount + ")");
        require("MISS".equals(listeners.targetType),
                "test ray trace was not MISS: " + listeners.targetType);
    }

    private static void runCase(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            String name,
            Runnable test
    ) {
        listeners.clear();
        player.getAbilities().instabuild = false;
        player.getInventory().clearContent();
        try {
            test.run();
        } catch (RuntimeException | LinkageError ex) {
            failures.add(name + ": " + ex);
        } finally {
            listeners.clear();
            player.getInventory().clearContent();
        }
    }

    private static void fillMainInventory(Inventory inventory, Item item, int count) {
        for (int slot = 0; slot < MAIN_INVENTORY_SLOTS; slot++) {
            inventory.setItem(slot, new ItemStack(item, count));
        }
    }

    private static int count(Inventory inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < MAIN_INVENTORY_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static Set<UUID> waterBucketDropIds(ServerLevel level, FakePlayer player) {
        Set<UUID> ids = new HashSet<>();
        for (ItemEntity item : waterBucketDrops(level, player)) ids.add(item.getUUID());
        return ids;
    }

    private static List<ItemEntity> waterBucketDrops(ServerLevel level, FakePlayer player) {
        AABB bounds = player.getBoundingBox().inflate(6.0D);
        List<ItemEntity> matches = new ArrayList<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (item.getItem().is(Items.WATER_BUCKET)) matches.add(item);
        }
        return matches;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Directive(Event$Result result, boolean canceled, ItemStack filledBucket) {}

    public static final class ProbeListeners {
        private ItemStack watchedStack;
        private Directive directive;
        private int eventCount;
        private String targetType;

        private void arm(ItemStack stack, Directive directive) {
            this.watchedStack = stack;
            this.directive = directive;
            this.eventCount = 0;
            this.targetType = null;
        }

        private void clear() {
            this.watchedStack = null;
            this.directive = null;
            this.eventCount = 0;
            this.targetType = null;
        }

        @SubscribeEvent
        public void onFillBucket(FillBucketEvent event) {
            if (event.getEmptyBucket() != watchedStack) return;
            eventCount++;
            targetType = event.getTarget() == null ? "null" : event.getTarget().getType().name();

            Directive active = directive;
            if (active == null) return;
            if (active.filledBucket() != null) event.setFilledBucket(active.filledBucket());
            event.setResult(active.result());
            if (active.canceled()) event.setCanceled(true);
        }
    }
}
