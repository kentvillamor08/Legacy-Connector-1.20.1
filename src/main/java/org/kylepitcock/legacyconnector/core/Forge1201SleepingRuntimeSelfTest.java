/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.core;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.SleepingLocationCheckEvent;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in dedicated-server behavior checks for Forge 1.20.1 sleeping events.
 *
 * <p>Run only in a fresh isolated server with
 * {@code -Dlegacyconnector.sleepingBehaviorSelfTest=true}. The probe uses one
 * cached FakePlayer and a temporary two-block bed fixture. All modified block
 * states and the original day time are restored before returning.</p>
 */
public final class Forge1201SleepingRuntimeSelfTest {
    private static final Logger LOG = LoggerFactory.getLogger("LegacyConnector/SleepingProbe");
    private static final Method CHECK_BED_EXISTS = checkBedExistsMethod();

    private Forge1201SleepingRuntimeSelfTest() {}

    public static List<String> run(ServerLevel level) {
        List<String> failures = new ArrayList<>();
        long originalDayTime = level.getDayTime();
        FakePlayer player = FakePlayerFactory.get(
                level,
                new GameProfile(UUID.randomUUID(), "LegacyConnectorSleepProbe")
        );
        BedFixture bed = new BedFixture(level);
        ProbeListeners listeners = new ProbeListeners();

        try {
            bed.install();
            NeoForge.EVENT_BUS.register(listeners);
            runLocationCases(failures, listeners, player, level, bed);
            runTickCases(failures, listeners, player, level, bed);
            runSleepStartCases(failures, listeners, player, level, bed);
        } catch (RuntimeException | LinkageError ex) {
            failures.add("sleeping probe setup/execution threw: " + ex);
            LOG.error("Sleeping behavior probe threw", ex);
        } finally {
            NeoForge.EVENT_BUS.unregister(listeners);
            if (player.isSleeping()) player.stopSleeping();
            player.clearSleepingPos();
            bed.restore();
            level.setDayTime(originalDayTime);
        }

        return failures;
    }

    private static void runLocationCases(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            BedFixture bed
    ) {
        runLocationCase(failures, listeners, player, level, bed,
                "DEFAULT accepts a valid bed", bed.foot, false,
                Result.DEFAULT, null, true, null, List.of("legacy-location", "native-continue"));
        runLocationCase(failures, listeners, player, level, bed,
                "DEFAULT rejects a non-bed position", bed.nonBed, false,
                Result.DEFAULT, null, false, Player.BedSleepingProblem.NOT_POSSIBLE_HERE,
                List.of("legacy-location", "native-continue"));
        runLocationCase(failures, listeners, player, level, bed,
                "ALLOW accepts a non-bed position", bed.nonBed, false,
                Result.ALLOW, null, true, null,
                List.of("legacy-location", "native-continue"));
        runLocationCase(failures, listeners, player, level, bed,
                "DENY rejects a valid bed", bed.foot, false,
                Result.DENY, null, false, Player.BedSleepingProblem.NOT_POSSIBLE_HERE,
                List.of("legacy-location", "native-continue"));
        runLocationCase(failures, listeners, player, level, bed,
                "empty sleeping Optional skips the legacy location event", bed.foot, true,
                Result.DEFAULT, null, false, Player.BedSleepingProblem.NOT_POSSIBLE_HERE,
                List.of("native-continue"));
        runLocationCase(failures, listeners, player, level, bed,
                "native continuation listener can override legacy DENY", bed.foot, false,
                Result.DENY, true, true, Player.BedSleepingProblem.NOT_POSSIBLE_HERE,
                List.of("legacy-location", "native-continue"));
    }

    private static void runLocationCase(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            BedFixture bed,
            String name,
            BlockPos sleepingPos,
            boolean clearPosition,
            Result locationResult,
            Boolean nativeOverride,
            boolean expectedResult,
            Player.BedSleepingProblem expectedNativeProblem,
            List<String> expectedOrder
    ) {
        Scenario scenario = new Scenario();
        scenario.locationResult = locationResult;
        scenario.nativeContinueOverride = nativeOverride;
        prepare(player, bed.foot);
        player.setSleepingPos(sleepingPos);
        if (clearPosition) player.clearSleepingPos();
        listeners.arm(player, scenario);
        try {
            boolean actual = invokeCheckBedExists(player);
            require(actual == expectedResult, "checkBedExists returned " + actual + ", expected " + expectedResult);
            require(scenario.locationCount == (clearPosition ? 0 : 1),
                    "SleepingLocationCheckEvent count was " + scenario.locationCount);
            require(scenario.timeCount == 0,
                    "location-only call also dispatched SleepingTimeCheckEvent " + scenario.timeCount + " time(s)");
            require(scenario.continueCount == 1,
                    "native CanContinueSleepingEvent count was " + scenario.continueCount);
            require(scenario.nativeProblems.size() == 1
                            && scenario.nativeProblems.get(0) == expectedNativeProblem,
                    "native continuation problem was " + scenario.nativeProblems);
            require(scenario.order.equals(expectedOrder),
                    "event order was " + scenario.order + ", expected " + expectedOrder);
            if (!clearPosition) {
                require(sleepingPos.equals(scenario.locationPositions.get(0)),
                        "location event received " + scenario.locationPositions);
            }

            LOG.info("ok: sleeping-location: {}", name);
        } catch (RuntimeException | LinkageError ex) {
            failures.add("location " + name + ": " + ex);
        } finally {
            listeners.disarm(player);
            player.clearSleepingPos();
        }
    }

    private static void runTickCases(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            BedFixture bed
    ) {
        runTickCase(failures, listeners, player, level, bed,
                "daytime DEFAULT wakes the sleeper", true, Result.DEFAULT, null,
                false, 0, 1, Player.BedSleepingProblem.NOT_POSSIBLE_NOW,
                List.of("legacy-time", "native-continue"));
        runTickCase(failures, listeners, player, level, bed,
                "nighttime DEFAULT keeps the sleeper asleep", false, Result.DEFAULT, null,
                true, 1, 2, null,
                List.of("legacy-time", "native-continue", "legacy-location", "native-continue"));
        runTickCase(failures, listeners, player, level, bed,
                "daytime ALLOW keeps the sleeper asleep", true, Result.ALLOW, null,
                true, 1, 2, null,
                List.of("legacy-time", "native-continue", "legacy-location", "native-continue"));
        runTickCase(failures, listeners, player, level, bed,
                "nighttime DENY wakes the sleeper", false, Result.DENY, null,
                false, 0, 1, Player.BedSleepingProblem.NOT_POSSIBLE_NOW,
                List.of("legacy-time", "native-continue"));
        runTickCase(failures, listeners, player, level, bed,
                "native continuation listener can override legacy time DENY", false, Result.DENY, true,
                true, 1, 2, Player.BedSleepingProblem.NOT_POSSIBLE_NOW,
                List.of("legacy-time", "native-continue", "legacy-location", "native-continue"));
    }

    private static void runTickCase(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            BedFixture bed,
            String name,
            boolean daytime,
            Result timeResult,
            Boolean nativeOverride,
            boolean expectedSleeping,
            int expectedLocationCount,
            int expectedContinueCount,
            Player.BedSleepingProblem expectedFirstProblem,
            List<String> expectedOrder
    ) {
        Scenario scenario = new Scenario();
        scenario.timeResult = timeResult;
        scenario.nativeContinueOverride = nativeOverride;
        level.setDayTime(daytime ? 1000L : 13000L);
        prepare(player, bed.foot);
        player.setSleepingPos(bed.foot);
        listeners.arm(player, scenario);
        try {
            // ServerPlayer.doTick() invokes the Player.tick() implementation with invokespecial.
            player.doTick();

            require(scenario.timeCount == 1,
                    "SleepingTimeCheckEvent count was " + scenario.timeCount);
            require(scenario.locationCount == expectedLocationCount,
                    "SleepingLocationCheckEvent count was " + scenario.locationCount);
            require(scenario.continueCount == expectedContinueCount,
                    "native CanContinueSleepingEvent count was " + scenario.continueCount);
            require(player.isSleeping() == expectedSleeping,
                    "isSleeping() was " + player.isSleeping() + ", expected " + expectedSleeping);
            require(scenario.timePositions.size() == 1
                            && scenario.timePositions.get(0).equals(Optional.of(bed.foot)),
                    "time event position context was " + scenario.timePositions);
            require(scenario.nativeProblems.size() == expectedContinueCount,
                    "native problem count was " + scenario.nativeProblems);
            if (expectedContinueCount > 0 && scenario.nativeProblems.get(0) != expectedFirstProblem) {
                throw new IllegalStateException("first native problem was " + scenario.nativeProblems.get(0)
                        + ", expected " + expectedFirstProblem);
            }
            require(scenario.order.equals(expectedOrder),
                    "event order was " + scenario.order + ", expected " + expectedOrder);

            LOG.info("ok: sleeping-time-tick: {}", name);
        } catch (RuntimeException | LinkageError ex) {
            failures.add("tick " + name + ": " + ex);
        } finally {
            listeners.disarm(player);
            if (player.isSleeping()) player.stopSleeping();
            player.clearSleepingPos();
        }
    }

    private static void runSleepStartCases(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            BedFixture bed
    ) {
        runSleepStartCase(failures, listeners, player, level, bed,
                "native CanPlayerSleepEvent rejection prevents the legacy time event",
                false, Result.DEFAULT, true, true);
        runSleepStartCase(failures, listeners, player, level, bed,
                "daytime DEFAULT returns NOT_POSSIBLE_NOW",
                true, Result.DEFAULT, false, false);
        runSleepStartCase(failures, listeners, player, level, bed,
                "nighttime DEFAULT starts sleeping",
                false, Result.DEFAULT, false, true);
        runSleepStartCase(failures, listeners, player, level, bed,
                "daytime ALLOW starts sleeping",
                true, Result.ALLOW, false, true);
        runSleepStartCase(failures, listeners, player, level, bed,
                "nighttime DENY returns NOT_POSSIBLE_NOW",
                false, Result.DENY, false, false);
    }

    private static void runSleepStartCase(
            List<String> failures,
            ProbeListeners listeners,
            FakePlayer player,
            ServerLevel level,
            BedFixture bed,
            String name,
            boolean daytime,
            Result timeResult,
            boolean rejectNativePrecheck,
            boolean expectedSuccess
    ) {
        Scenario scenario = new Scenario();
        scenario.timeResult = timeResult;
        scenario.rejectNativeSleep = rejectNativePrecheck;
        level.setDayTime(daytime ? 1000L : 13000L);
        prepare(player, bed.foot);
        player.clearSleepingPos();
        listeners.arm(player, scenario);
        try {
            Either<Player.BedSleepingProblem, Unit> outcome = player.startSleepInBed(bed.foot);

            require(scenario.nativePreSleepCount == 1,
                    "native CanPlayerSleepEvent count was " + scenario.nativePreSleepCount);
            if (rejectNativePrecheck) {
                require(scenario.timeCount == 0,
                        "legacy SleepingTimeCheckEvent fired after native pre-sleep rejection");
                require(outcome.left().orElse(null) == Player.BedSleepingProblem.NOT_POSSIBLE_NOW,
                        "native precheck result was " + outcome);
                require(scenario.order.equals(List.of("native-pre-sleep")),
                        "precheck rejection order was " + scenario.order);
            } else {
                require(scenario.timeCount == 1,
                        "SleepingTimeCheckEvent count was " + scenario.timeCount);
                require(scenario.timePositions.size() == 1
                                && scenario.timePositions.get(0).equals(Optional.of(bed.foot)),
                        "sleep-start time event position was " + scenario.timePositions);
                require(scenario.order.equals(List.of("native-pre-sleep", "legacy-time")),
                        "sleep-start event order was " + scenario.order);
                if (expectedSuccess) {
                    require(outcome.right().isPresent(),
                            "expected successful sleep-start result, got " + outcome);
                    require(player.isSleeping(), "sleep-start returned success but player is not sleeping");
                    require(outcome.right().orElse(null) == Unit.INSTANCE,
                            "sleep-start right value was not Unit.INSTANCE");
                } else {
                    require(outcome.left().orElse(null) == Player.BedSleepingProblem.NOT_POSSIBLE_NOW,
                            "expected NOT_POSSIBLE_NOW, got " + outcome);
                    require(!player.isSleeping(), "rejected sleep-start left the player sleeping");
                }
            }

            LOG.info("ok: sleeping-start: {}", name);
        } catch (RuntimeException | LinkageError ex) {
            failures.add("sleep start " + name + ": " + ex);
        } finally {
            listeners.disarm(player);
            if (player.isSleeping()) player.stopSleeping();
            player.clearSleepingPos();
        }
    }

    private static void prepare(FakePlayer player, BlockPos pos) {
        if (player.isSleeping()) player.stopSleeping();
        player.clearSleepingPos();
        player.setPos(pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D);
        player.setXRot(0.0F);
        player.setYRot(0.0F);
    }

    private static boolean invokeCheckBedExists(LivingEntity entity) {
        try {
            return (boolean) CHECK_BED_EXISTS.invoke(entity);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not invoke transformed LivingEntity.checkBedExists()", ex);
        }
    }

    private static Method checkBedExistsMethod() {
        try {
            Method method = LivingEntity.class.getDeclaredMethod("checkBedExists");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class BedFixture {
        private final ServerLevel level;
        private final BlockPos foot;
        private final BlockPos head;
        private final BlockPos nonBed;
        private final Map<BlockPos, BlockState> previous = new java.util.LinkedHashMap<>();

        private BedFixture(ServerLevel level) {
            this.level = level;
            this.foot = level.getSharedSpawnPos().offset(8, 1, 8);
            this.head = foot.relative(Direction.NORTH);
            this.nonBed = foot.relative(Direction.EAST, 4);
        }

        private void install() {
            remember(foot.below());
            remember(head.below());
            remember(foot);
            remember(head);

            level.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(head.below(), Blocks.STONE.defaultBlockState(), 2);
            BlockState base = Blocks.RED_BED.defaultBlockState()
                    .setValue(BedBlock.FACING, Direction.NORTH)
                    .setValue(BedBlock.OCCUPIED, false);
            level.setBlock(foot, base.setValue(BedBlock.PART, BedPart.FOOT), 2);
            level.setBlock(head, base.setValue(BedBlock.PART, BedPart.HEAD), 2);
        }

        private void remember(BlockPos pos) {
            previous.put(pos, level.getBlockState(pos));
        }

        private void restore() {
            List<Map.Entry<BlockPos, BlockState>> entries = new ArrayList<>(previous.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<BlockPos, BlockState> entry = entries.get(i);
                level.setBlock(entry.getKey(), entry.getValue(), 2);
            }
            previous.clear();
        }
    }

    private static final class Scenario {
        private Result locationResult = Result.DEFAULT;
        private Result timeResult = Result.DEFAULT;
        private Boolean nativeContinueOverride;
        private boolean rejectNativeSleep;
        private int locationCount;
        private int timeCount;
        private int continueCount;
        private int nativePreSleepCount;
        private final List<BlockPos> locationPositions = new ArrayList<>();
        private final List<Optional<BlockPos>> timePositions = new ArrayList<>();
        private final List<Player.BedSleepingProblem> nativeProblems = new ArrayList<>();
        private final List<String> order = new ArrayList<>();
    }

    public static final class ProbeListeners {
        private final Map<Player, Scenario> scenarios = new IdentityHashMap<>();

        private void arm(Player player, Scenario scenario) {
            scenarios.put(player, scenario);
        }

        private void disarm(Player player) {
            scenarios.remove(player);
        }

        @SubscribeEvent
        public void onSleepingLocation(SleepingLocationCheckEvent event) {
            Scenario scenario = scenarios.get(event.getEntity());
            if (scenario == null) return;
            scenario.locationCount++;
            scenario.locationPositions.add(event.getSleepingLocation());
            scenario.order.add("legacy-location");
            event.setResult(scenario.locationResult);
        }

        @SubscribeEvent
        public void onSleepingTime(SleepingTimeCheckEvent event) {
            Scenario scenario = scenarios.get(event.getEntity());
            if (scenario == null) return;
            scenario.timeCount++;
            scenario.timePositions.add(event.getSleepingLocation());
            scenario.order.add("legacy-time");
            event.setResult(scenario.timeResult);
        }

        @SubscribeEvent
        public void onNativeContinuation(CanContinueSleepingEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            Scenario scenario = scenarios.get(player);
            if (scenario == null) return;
            scenario.continueCount++;
            scenario.nativeProblems.add(event.getProblem());
            scenario.order.add("native-continue");
            if (scenario.nativeContinueOverride != null) {
                event.setContinueSleeping(scenario.nativeContinueOverride);
            }
        }

        @SubscribeEvent
        public void onNativeCanSleep(CanPlayerSleepEvent event) {
            Scenario scenario = scenarios.get(event.getEntity());
            if (scenario == null) return;
            scenario.nativePreSleepCount++;
            scenario.order.add("native-pre-sleep");
            if (scenario.rejectNativeSleep) {
                event.setProblem(Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
            }
        }
    }
}
