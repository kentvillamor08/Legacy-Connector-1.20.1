/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.core;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.PlayerEvent$ItemPickupEvent;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre;

/**
 * Opt-in dedicated-server integration checks for the real ItemEntity.playerTouch
 * transaction. This deliberately exercises the registered mixin and real
 * NeoForge event bus; it is not a substitute for the focused policy unit tests.
 *
 * <p>Run only in an isolated test server with
 * {@code -Dlegacyconnector.itemPickupBehaviorSelfTest=true}. One cached fake
 * player is used for the test run and its inventory is cleared before and after
 * every case. The test does not unload the world's FakePlayerFactory cache,
 * which could invalidate fake players owned by other mods.</p>
 */
public final class Forge1201ItemPickupRuntimeSelfTest {
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private Forge1201ItemPickupRuntimeSelfTest() {}

    public static List<String> run(ServerLevel level) {
        List<String> failures = new ArrayList<>();
        BlockPos spawn = level.getSharedSpawnPos();
        FakePlayer player = FakePlayerFactory.get(
                level,
                new GameProfile(UUID.randomUUID(), "LegacyConnectorPickupProbe")
        );
        player.setPos(spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D);

        ProbeListeners listeners = new ProbeListeners();
        NeoForge.EVENT_BUS.register(listeners);
        try {
            runCase(failures, listeners, player, "native Pre FALSE vetoes before the legacy decision", () -> {
                Scenario scenario = new Scenario();
                scenario.nativePre = TriState.FALSE;
                scenario.legacyResult = Result.ALLOW;
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 2), 0);
                item.playerTouch(player);

                require(scenario.nativePreCount == 1, "native Pre count was " + scenario.nativePreCount);
                require(scenario.legacyDecisionCount == 0, "legacy decision fired after native FALSE");
                require(count(player.getInventory(), Items.APPLE) == 0, "inventory changed after native FALSE");
                require(item.getItem().getCount() == 2, "item stack changed after native FALSE");
                require(scenario.nativePostCount == 0, "native Post fired after native FALSE");
                require(scenario.legacyNotificationCount == 0, "legacy notification fired after native FALSE");
                require(scenario.order.equals(List.of("native-pre")), "unexpected order " + scenario.order);
            });

            runCase(failures, listeners, player, "native Pre TRUE preserves target override with positive entry delay", () -> {
                Scenario scenario = new Scenario();
                scenario.nativePre = TriState.TRUE;
                scenario.legacyResult = Result.ALLOW;
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 1), 20);
                item.playerTouch(player);

                require(scenario.nativePreCount == 1, "native Pre count was " + scenario.nativePreCount);
                require(scenario.legacyDecisionCount == 0, "positive entry delay dispatched the legacy event");
                require(count(player.getInventory(), Items.APPLE) == 1, "native TRUE did not insert the item");
                require(item.isRemoved() || item.getItem().isEmpty(), "fully picked-up item entity remained");
                require(scenario.nativePostCount == 1, "native Post count was " + scenario.nativePostCount);
                require(scenario.legacyNotificationCount == 0, "legacy event fired despite positive entry delay");
            });

            runCase(failures, listeners, player, "legacy cancellation overrides native Pre TRUE", () -> {
                Scenario scenario = new Scenario();
                scenario.nativePre = TriState.TRUE;
                scenario.cancelLegacyDecision = true;
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 1), 0);
                item.playerTouch(player);

                require(scenario.legacyDecisionCount == 1, "legacy decision count was " + scenario.legacyDecisionCount);
                require(count(player.getInventory(), Items.APPLE) == 0, "canceled pickup reached inventory");
                require(item.getItem().getCount() == 1, "canceled item stack changed");
                require(scenario.nativePostCount == 0, "native Post fired after legacy cancellation");
                require(scenario.legacyNotificationCount == 0, "legacy notification fired after cancellation");
            });

            verifyOrdinaryInsertion(failures, listeners, player, level, Result.DEFAULT, "legacy DEFAULT retains real insertion");
            verifyOrdinaryInsertion(failures, listeners, player, level, Result.DENY, "legacy DENY retains real insertion");

            runCase(failures, listeners, player, "legacy ALLOW skips insertion and emits zero-delta notification", () -> {
                Scenario scenario = new Scenario();
                scenario.legacyResult = Result.ALLOW;
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 2), 0);
                item.playerTouch(player);

                require(count(player.getInventory(), Items.APPLE) == 0, "ALLOW inserted into inventory");
                require(item.getItem().getCount() == 2, "ALLOW changed the live item stack");
                require(scenario.nativePostCount == 0, "native Post was not suppressed for ALLOW");
                require(scenario.legacyNotificationCount == 1, "legacy notification count was " + scenario.legacyNotificationCount);
                require(scenario.notificationStackCount == 0, "ALLOW notification delta was " + scenario.notificationStackCount);
                require(scenario.order.equals(List.of("native-pre", "legacy-decision", "legacy-notification")),
                        "unexpected ALLOW order " + scenario.order);
            });

            runCase(failures, listeners, player, "failed inventory insertion emits neither Post nor legacy notification", () -> {
                fillMainInventory(player.getInventory(), Items.DIAMOND, 64);
                Scenario scenario = new Scenario();
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 1), 0);
                item.playerTouch(player);

                require(count(player.getInventory(), Items.APPLE) == 0, "failed insertion changed inventory");
                require(item.getItem().getCount() == 1, "failed insertion changed item remainder");
                require(scenario.nativePostCount == 0, "native Post fired after failed insertion");
                require(scenario.legacyNotificationCount == 0, "legacy notification fired after failed insertion");
                require(scenario.legacyDecisionCount == 1, "legacy decision count was " + scenario.legacyDecisionCount);
            });

            runCase(failures, listeners, player, "partial insertion that returns false retains remainder and skips success notifications", () -> {
                fillMainInventory(player.getInventory(), Items.DIAMOND, 64);
                player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 63));
                Scenario scenario = new Scenario();
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.GOLD_INGOT, 10), 0);
                item.playerTouch(player);

                require(count(player.getInventory(), Items.GOLD_INGOT) == 64, "inventory gold count was " + count(player.getInventory(), Items.GOLD_INGOT));
                require(item.getItem().getCount() == 9, "item remainder was " + item.getItem().getCount());
                require(scenario.nativePostCount == 0, "native Post fired after partial insertion returned false");
                require(scenario.legacyNotificationCount == 0, "legacy notification fired after partial insertion returned false");
                require(scenario.order.equals(List.of("native-pre", "legacy-decision")),
                        "unexpected partial-failure order " + scenario.order);
            });

            runCase(failures, listeners, player, "legacy listener pickup-delay mutation remains visible to native gate", () -> {
                Scenario scenario = new Scenario();
                scenario.mutatePickupDelay = 5;
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 1), 0);
                item.playerTouch(player);

                require(scenario.legacyDecisionCount == 1, "legacy decision did not run");
                require(item.hasPickUpDelay(), "listener pickup-delay mutation was lost");
                require(count(player.getInventory(), Items.APPLE) == 0, "native live delay gate was bypassed");
                require(scenario.nativePostCount == 0, "native Post fired after live delay veto");
                require(scenario.legacyNotificationCount == 0, "legacy notification fired after live delay veto");
            });

            runCase(failures, listeners, player, "legacy listener target mutation remains visible to native gate", () -> {
                Scenario scenario = new Scenario();
                scenario.mutateTarget = UUID.randomUUID();
                ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 1), 0);
                item.playerTouch(player);

                require(scenario.legacyDecisionCount == 1, "legacy decision did not run");
                require(scenario.mutateTarget.equals(item.getTarget()), "listener target mutation was lost");
                require(count(player.getInventory(), Items.APPLE) == 0, "native live target gate was bypassed");
                require(scenario.nativePostCount == 0, "native Post fired after live target veto");
                require(scenario.legacyNotificationCount == 0, "legacy notification fired after live target veto");
            });

            runCase(failures, listeners, player, "nested pickup invocations keep independent contexts", () -> {
                Scenario outer = new Scenario();
                Scenario inner = new Scenario();
                inner.legacyResult = Result.ALLOW;
                ItemEntity outerItem = createItem(level, player, listeners, outer, new ItemStack(Items.APPLE, 1), 0);
                ItemEntity innerItem = createItem(level, player, listeners, inner, new ItemStack(Items.APPLE, 1), 0);
                outer.nestedItem = innerItem;
                outer.nestedPlayer = player;

                outerItem.playerTouch(player);

                require(outer.legacyDecisionCount == 1, "outer legacy decision count was " + outer.legacyDecisionCount);
                require(inner.legacyDecisionCount == 1, "inner legacy decision count was " + inner.legacyDecisionCount);
                require(count(player.getInventory(), Items.APPLE) == 1, "outer inventory insertion was lost or inner ALLOW inserted");
                require(outerItem.isRemoved() || outerItem.getItem().isEmpty(), "outer pickup did not finish");
                require(innerItem.getItem().getCount() == 1, "inner ALLOW changed the item stack");
                require(outer.nativePostCount == 1 && outer.legacyNotificationCount == 1,
                        "outer Post/notification mismatch: " + outer.nativePostCount + "/" + outer.legacyNotificationCount);
                require(inner.nativePostCount == 0, "inner ALLOW emitted native Post");
                require(inner.legacyNotificationCount == 1 && inner.notificationStackCount == 0,
                        "inner ALLOW did not emit a zero-delta legacy notification");
            });
        } finally {
            NeoForge.EVENT_BUS.unregister(listeners);
            for (ItemEntity item : new ArrayList<>(listeners.scenarios.keySet())) {
                if (!item.isRemoved()) item.discard();
            }
            clearInventory(player.getInventory());
        }

        return failures;
    }

    private static void verifyOrdinaryInsertion(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            Result result,
            String name
    ) {
        runCase(failures, listeners, player, name, () -> {
            Scenario scenario = new Scenario();
            scenario.legacyResult = result;
            ItemEntity item = createItem(level, player, listeners, scenario, new ItemStack(Items.APPLE, 2), 0);
            item.playerTouch(player);

            require(count(player.getInventory(), Items.APPLE) == 2, "inventory did not receive the full stack");
            require(item.isRemoved() || item.getItem().isEmpty(), "fully picked-up item entity remained");
            require(scenario.nativePostCount == 1, "native Post count was " + scenario.nativePostCount);
            require(scenario.legacyNotificationCount == 1, "legacy notification count was " + scenario.legacyNotificationCount);
            require(scenario.notificationStackCount == 2, "notification delta was " + scenario.notificationStackCount);
            require(scenario.order.equals(List.of("native-pre", "legacy-decision", "native-post", "legacy-notification")),
                    "unexpected insertion order " + scenario.order);
        });
    }

    private static void runCase(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            String name,
            Runnable test
    ) {
        List<ItemEntity> before = new ArrayList<>(listeners.scenarios.keySet());
        clearInventory(player.getInventory());
        try {
            test.run();
        } catch (RuntimeException | LinkageError ex) {
            failures.add(name + ": " + ex);
        } finally {
            for (ItemEntity item : new ArrayList<>(listeners.scenarios.keySet())) {
                if (before.contains(item)) continue;
                listeners.scenarios.remove(item);
                if (!item.isRemoved()) item.discard();
            }
            clearInventory(player.getInventory());
        }
    }

    private static ItemEntity createItem(
            ServerLevel level,
            FakePlayer player,
            ProbeListeners listeners,
            Scenario scenario,
            ItemStack stack,
            int pickupDelay
    ) {
        ItemEntity item = new ItemEntity(level, player.getX(), player.getY(), player.getZ(), stack);
        item.setPickUpDelay(pickupDelay);
        listeners.scenarios.put(item, scenario);
        if (!level.addFreshEntity(item)) {
            listeners.scenarios.remove(item);
            throw new IllegalStateException("Could not add pickup probe item entity to the test level");
        }
        return item;
    }

    private static void fillMainInventory(Inventory inventory, Item item, int count) {
        for (int slot = 0; slot < MAIN_INVENTORY_SLOTS; slot++) {
            inventory.setItem(slot, new ItemStack(item, count));
        }
    }

    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < MAIN_INVENTORY_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void clearInventory(Inventory inventory) {
        inventory.clearContent();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Scenario {
        private TriState nativePre;
        private Result legacyResult = Result.DEFAULT;
        private boolean cancelLegacyDecision;
        private Integer mutatePickupDelay;
        private UUID mutateTarget;
        private ItemEntity nestedItem;
        private FakePlayer nestedPlayer;
        private int nativePreCount;
        private int legacyDecisionCount;
        private int nativePostCount;
        private int legacyNotificationCount;
        private int notificationStackCount = -1;
        private final List<String> order = new ArrayList<>();
    }

    public static final class ProbeListeners {
        private final Map<ItemEntity, Scenario> scenarios = new IdentityHashMap<>();

        @SubscribeEvent
        public void onNativePre(Pre event) {
            Scenario scenario = scenarios.get(event.getItemEntity());
            if (scenario == null) return;
            scenario.nativePreCount++;
            scenario.order.add("native-pre");
            if (scenario.nativePre != null) event.setCanPickup(scenario.nativePre);
        }

        @SubscribeEvent
        public void onLegacyDecision(EntityItemPickupEvent event) {
            Scenario scenario = scenarios.get(event.getItem());
            if (scenario == null) return;
            scenario.legacyDecisionCount++;
            scenario.order.add("legacy-decision");
            if (scenario.mutatePickupDelay != null) {
                event.getItem().setPickUpDelay(scenario.mutatePickupDelay);
            }
            if (scenario.mutateTarget != null) {
                event.getItem().setTarget(scenario.mutateTarget);
            }
            if (scenario.cancelLegacyDecision) {
                event.setCanceled(true);
            } else {
                event.setResult(scenario.legacyResult);
            }

            ItemEntity nested = scenario.nestedItem;
            if (nested != null) {
                scenario.nestedItem = null;
                nested.playerTouch(scenario.nestedPlayer);
            }
        }

        @SubscribeEvent
        public void onNativePost(Post event) {
            Scenario scenario = scenarios.get(event.getItemEntity());
            if (scenario == null) return;
            scenario.nativePostCount++;
            scenario.order.add("native-post");
        }

        @SubscribeEvent
        public void onLegacyNotification(PlayerEvent$ItemPickupEvent event) {
            Scenario scenario = scenarios.get(event.getOriginalEntity());
            if (scenario == null) return;
            scenario.legacyNotificationCount++;
            scenario.notificationStackCount = event.getStack().getCount();
            scenario.order.add("legacy-notification");
        }
    }
}
