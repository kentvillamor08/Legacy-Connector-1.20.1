/*
 * Copyright (c) 2026 kentvillamor08
 * Licensed under the MIT License.
 * See LICENSE in the project root.
 */
package org.kylepitcock.legacyconnector.core;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.living.ZombieEvent$SummonAidEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in dedicated-server integration checks for the real Forge 1.20.1
 * ZombieEvent.SummonAidEvent adapter.
 *
 * <p>Run only in a fresh isolated server with
 * {@code -Dlegacyconnector.zombieSummonAidBehaviorSelfTest=true}. The probe
 * drives real Zombie.hurt calls through the registered Mixin and NeoForge
 * event bus. A temporary, dark 81x81 spawn arena makes the reinforcement
 * placement loop a broad candidate region without depending on
 * the generated terrain. Original block states, difficulty, gamerule and time
 * are restored before this method returns.</p>
 */
public final class Forge1201ZombieSummonAidRuntimeSelfTest {
    private static final Logger LOG = LoggerFactory.getLogger("LegacyConnector/ZombieSummonAidProbe");
    private static final int ARENA_RADIUS = 40;

    private Forge1201ZombieSummonAidRuntimeSelfTest() {}

    public static List<String> run(ServerLevel level) {
        List<String> failures = new ArrayList<>();
        Difficulty originalDifficulty = level.getDifficulty();
        boolean originalMobSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
        long originalDayTime = level.getDayTime();
        ProbeArena arena = new ProbeArena(level);
        ProbeListeners listeners = new ProbeListeners(level);
        FakePlayer attacker = FakePlayerFactory.get(
                level,
                new GameProfile(UUID.randomUUID(), "LegacyConnectorZombieSummonAidProbe")
        );

        try {
            NeoForge.EVENT_BUS.register(listeners);
            arena.install();
            level.setDayTime(18000L);
            level.updateSkyBrightness();

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectNoSpawn("DEFAULT with null attacker still posts the event",
                            Event$Result.DEFAULT, Difficulty.HARD, true, 1.0D, true));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectNoSpawn("DEFAULT on non-HARD difficulty",
                            Event$Result.DEFAULT, Difficulty.NORMAL, true, 1.0D, false));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectNoSpawn("DEFAULT with a zero summon chance",
                            Event$Result.DEFAULT, Difficulty.HARD, true, 0.0D, false));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectNoSpawn("DEFAULT with mob spawning disabled",
                            Event$Result.DEFAULT, Difficulty.HARD, false, 1.0D, false));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectSpawn("DEFAULT eligible path spawns a normal reinforcement",
                            Event$Result.DEFAULT, Difficulty.HARD, true, 1.0D, false,
                            null, false, true));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectNoSpawn("DENY suppresses reinforcement but preserves successful hurt",
                            Event$Result.DENY, Difficulty.HARD, true, 1.0D, false));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectSpawn("ALLOW bypasses difficulty, chance and gamerule gates",
                            Event$Result.ALLOW, Difficulty.NORMAL, false, 0.0D, false,
                            MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED, false, true));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectSpawn("ALLOW uses the exact custom summoned Zombie instance",
                            Event$Result.ALLOW, Difficulty.NORMAL, false, 0.0D, false,
                            MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED, true, true));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectSpawnWithoutTarget("ALLOW with null attacker still spawns without assigning a target",
                            Event$Result.ALLOW, Difficulty.NORMAL, false, 0.0D, true,
                            MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED));

            runCase(failures, level, arena, listeners, attacker,
                    Scenario.expectNativeVeto(
                            "native SpawnPlacementCheck FAIL vetoes ALLOW after exactly 50 attempts without consuming the vanilla chance roll (same Zombie RNG instance)",
                            Event$Result.ALLOW, Difficulty.HARD, true, 0.0D, false,
                            MobSpawnEvent.SpawnPlacementCheck.Result.FAIL));

        } catch (RuntimeException | LinkageError ex) {
            failures.add("Zombie SummonAid behavior probe setup/execution threw: " + ex);
            LOG.error("Zombie SummonAid behavior probe threw", ex);
        } finally {
            listeners.disarm();
            NeoForge.EVENT_BUS.unregister(listeners);
            arena.restore();
            level.getServer().setDifficulty(originalDifficulty, true);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING)
                    .set(originalMobSpawning, level.getServer());
            level.setDayTime(originalDayTime);
            level.updateSkyBrightness();
        }

        return failures;
    }

    private static void runCase(
            List<String> failures,
            ServerLevel level,
            ProbeArena arena,
            ProbeListeners listeners,
            FakePlayer attacker,
            Scenario scenario
    ) {
        Set<UUID> before = new HashSet<>();
        for (Zombie zombie : arena.zombies()) before.add(zombie.getUUID());

        Zombie hurtZombie = EntityType.ZOMBIE.create(level);
        Zombie customAid = scenario.customAid ? EntityType.ZOMBIE.create(level) : null;
        try {
            require(hurtZombie != null, "could not construct the hurt Zombie");
            hurtZombie.setPos(arena.centerX + 0.5D, arena.entityY, arena.centerZ + 0.5D);
            hurtZombie.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)
                    .setBaseValue(scenario.summonChance);
            require(level.addFreshEntity(hurtZombie), "could not add the hurt Zombie to the test level");

            if (customAid != null) {
                customAid.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(0.5D);
            }

            level.getServer().setDifficulty(scenario.difficulty, true);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING)
                    .set(scenario.mobSpawning, level.getServer());

            listeners.arm(hurtZombie, scenario, arena);
            listeners.setCustomAid(customAid);
            if (scenario.customAid) require(customAid != null, "could not construct listener-supplied custom Zombie");
            float healthBefore = hurtZombie.getHealth();
            var damageSource = scenario.nullAttacker
                    ? level.damageSources().generic()
                    : level.damageSources().playerAttack(attacker);
            if (scenario.verifyFirstPlacementSequence) {
                long seed = 0x5EED1201L;
                RandomSource hurtZombieRandom = hurtZombie.getRandom();
                hurtZombieRandom.setSeed(seed);
                List<BlockPos> expectedCandidates = placementCandidates(
                        hurtZombie.blockPosition(), hurtZombieRandom);
                scenario.expectedFirstPlacementPos = expectedCandidates.get(0);
                arena.installPlacementPads(expectedCandidates);
                // Rewind the very same RNG implementation and instance the production Mixin samples.
                hurtZombieRandom.setSeed(seed);
            }
            boolean hurtResult = hurtZombie.hurt(damageSource, 1.0F);
            listeners.disarm();

            require(hurtResult, "Zombie.hurt returned false after accepted damage");
            require(hurtZombie.getHealth() < healthBefore,
                    "Zombie health did not decrease after the successful hurt call");
            require(scenario.eventCount == 1,
                    "SummonAidEvent count was " + scenario.eventCount + ", expected exactly one");
            require(scenario.eventEntity == hurtZombie,
                    "SummonAidEvent did not carry the exact hurt Zombie");
            require(scenario.eventLevel == level,
                    "SummonAidEvent carried a different Level");
            require(scenario.eventAttacker == (scenario.nullAttacker ? null : attacker),
                    "SummonAidEvent attacker did not match the resolved nullable attacker");
            require(scenario.eventX == (int) Math.floor(hurtZombie.getX())
                            && scenario.eventY == (int) Math.floor(hurtZombie.getY())
                            && scenario.eventZ == (int) Math.floor(hurtZombie.getZ()),
                    "SummonAidEvent coordinates did not match the hurt Zombie's floored position");
            require(Math.abs(scenario.eventSummonChance - scenario.summonChance) < 0.000001D,
                    "SummonAidEvent summon chance was " + scenario.eventSummonChance
                            + ", expected " + scenario.summonChance);
            require(scenario.eventResult == scenario.result,
                    "listener result round-trip was " + scenario.eventResult + ", expected " + scenario.result);
            require(!scenario.order.isEmpty() && "legacy-event".equals(scenario.order.get(0)),
                    "legacy SummonAidEvent was not the first recorded hook: " + scenario.order);
            if (scenario.verifyFirstPlacementSequence) {
                require(scenario.firstPlacementPos != null,
                        "ALLOW did not reach a native placement check for its first candidate");
                require(scenario.expectedFirstPlacementPos.equals(scenario.firstPlacementPos),
                        "first placement candidate was " + scenario.firstPlacementPos
                                + ", expected seeded first candidate " + scenario.expectedFirstPlacementPos
                                + "; a random value may have been consumed before forced placement sampling");
            }

            List<Zombie> addedZombies = new ArrayList<>();
            for (Zombie zombie : arena.zombies()) {
                if (!before.contains(zombie.getUUID()) && zombie != hurtZombie) {
                    addedZombies.add(zombie);
                }
            }

            if (scenario.expectSpawn) {
                require(addedZombies.size() == 1,
                        "expected exactly one reinforcement Zombie, found " + addedZombies.size()
                                + "; nativePlacementChecks=" + scenario.placementCount
                                + ", lastPlacementResult=" + scenario.lastPlacementResult
                                + ", lastPlacementPos=" + scenario.lastPlacementPos
                                + ", entityJoinEvents=" + scenario.entityJoinCount
                                + ", joinedAid=" + scenario.joinedAid
                                + ", order=" + scenario.order
                                + ", hurtZombiePos=" + hurtZombie.blockPosition()
                                + ", customAidProvided=" + (customAid != null)
                                + (customAid == null ? "" : ", customAidRemoved=" + customAid.isRemoved()
                                + ", customAidLevelMatches=" + (customAid.level() == level)));
                Zombie aid = addedZombies.get(0);
                require(scenario.customAid ? aid == customAid : aid != customAid,
                        scenario.customAid
                                ? "the exact listener-supplied custom Zombie was not added"
                                : "the default path unexpectedly used a custom Zombie");
                require(scenario.entityJoinCount == 1,
                        "EntityJoinLevelEvent count for the aid was " + scenario.entityJoinCount);
                require(scenario.joinedAid == aid,
                        "EntityJoinLevelEvent did not observe the exact reinforcement instance");
                require(scenario.placementCount > 0,
                        "native MobSpawnEvent.SpawnPlacementCheck was not observed");
                require(scenario.placementCount <= 50,
                        "native placement event count exceeded the 50-attempt search");
                require(scenario.order.indexOf("native-placement") > 0,
                        "native placement checks did not follow the legacy event: " + scenario.order);
                require(scenario.order.indexOf("entity-join") > scenario.order.indexOf("native-placement"),
                        "entity addition did not follow native placement checks: " + scenario.order);
                require(scenario.lastPlacementResult == scenario.nativePlacementResult
                                || (scenario.nativePlacementResult == null
                                && scenario.lastPlacementResult == MobSpawnEvent.SpawnPlacementCheck.Result.DEFAULT),
                        "native placement result was " + scenario.lastPlacementResult
                                + ", expected " + scenario.nativePlacementResult);

                if (scenario.expectTarget) {
                    require(aid.getTarget() == attacker,
                            "reinforcement target was not the exact resolved attacker");
                } else {
                    require(aid.getTarget() == null,
                            "reinforcement with a null attacker unexpectedly received a target");
                }

                double actualCallerCharge = callerChargeModifierAmount(hurtZombie);
                require(Math.abs(actualCallerCharge + 0.05D) < 0.0001D,
                        "caller reinforcement charge modifier amount was " + actualCallerCharge
                                + ", expected -0.05 (effective attribute value "
                                + hurtZombie.getAttributeValue(Attributes.SPAWN_REINFORCEMENTS_CHANCE) + ")");

                if (scenario.customAid) {
                    AttributeModifier calleeCharge = calleeChargeModifier(customAid);
                    require(Math.abs(calleeCharge.amount() + 0.05D) < 0.0001D,
                            "custom callee reinforcement-charge modifier amount was "
                                    + calleeCharge.amount() + ", expected -0.05 (effective chance "
                                    + customAid.getAttributeValue(Attributes.SPAWN_REINFORCEMENTS_CHANCE)
                                    + ", base chance "
                                    + customAid.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).getBaseValue()
                                    + ")");
                    require(calleeCharge.operation() == AttributeModifier.Operation.ADD_VALUE,
                            "custom callee reinforcement-charge operation was "
                                    + calleeCharge.operation() + ", expected ADD_VALUE");
                }
            } else {
                require(addedZombies.isEmpty(),
                        "unexpected reinforcement spawn(s): " + addedZombies.size());
                require(scenario.entityJoinCount == 0,
                        "an aid entity joined despite the scenario expecting no spawn");
                if (scenario.expectPlacementAttempt) {
                    require(scenario.placementCount > 0,
                            "no native placement attempt reached the event listener");
                    require(scenario.placementCount == 50,
                            "native placement veto was observed " + scenario.placementCount
                                    + " times, expected one check for each of the exact 50 placement attempts");
                    require(scenario.lastPlacementResult == MobSpawnEvent.SpawnPlacementCheck.Result.FAIL,
                            "native veto did not leave the final placement result at FAIL");
                } else {
                    require(scenario.placementCount == 0,
                            "native placement ran even though a vanilla gate should have prevented it");
                }
            }

            LOG.info("ok: zombie-summon-aid: {}", scenario.name);
        } catch (RuntimeException | LinkageError ex) {
            failures.add(scenario.name + ": " + ex);
            LOG.error("Zombie SummonAid scenario failed: {}", scenario.name, ex);
        } finally {
            listeners.disarm();
            for (Zombie zombie : arena.zombies()) {
                if (!before.contains(zombie.getUUID())) zombie.discard();
            }
            if (customAid != null && !customAid.isRemoved()) customAid.discard();
            if (hurtZombie != null && !hurtZombie.isRemoved()) hurtZombie.discard();
        }
    }

    /**
     * Reads the stored reinforcement-charge modifier, not the effective
     * attribute value. The attribute is clamped at its minimum (zero), so a
     * valid -0.05 modifier on a zero-base ALLOW scenario still reports an
     * effective value of 0.0.
     */
    private static double callerChargeModifierAmount(Zombie zombie) {
        try {
            var field = Zombie.class.getDeclaredField("REINFORCEMENT_CALLER_CHARGE_ID");
            field.setAccessible(true);
            var modifierId = (net.minecraft.resources.ResourceLocation) field.get(null);
            var modifier = zombie.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)
                    .getModifier(modifierId);
            require(modifier != null, "target caller reinforcement-charge modifier was not installed");
            return modifier.amount();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("could not read target caller reinforcement-charge modifier id", ex);
        }
    }

    /**
     * Reads the actual vanilla callee-charge modifier attached to the spawned
     * Zombie. The effective attribute value is not a reliable assertion here:
     * finalizeSpawn may leave a non-leader at zero, and the vanilla -0.05
     * modifier is then clamped by the attribute's lower bound.
     */
    private static AttributeModifier calleeChargeModifier(Zombie zombie) {
        try {
            var field = Zombie.class.getDeclaredField("ZOMBIE_REINFORCEMENT_CALLEE_CHARGE");
            field.setAccessible(true);
            var expected = (AttributeModifier) field.get(null);
            var applied = zombie.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)
                    .getModifier(expected.id());
            require(applied != null,
                    "target callee reinforcement-charge modifier was not installed on the exact aid Zombie");
            return applied;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "could not read target callee reinforcement-charge modifier", ex);
        }
    }

    /**
     * Reproduces the target's 50 candidate coordinate draws from the hurt
     * Zombie's own RandomSource instance. Using the same object is important:
     * RandomSource.create(seed) may produce a different RNG implementation
     * than Entity.getRandom() on this Minecraft version.
     */
    private static List<BlockPos> placementCandidates(BlockPos origin, RandomSource expectedRandom) {
        List<BlockPos> candidates = new ArrayList<>(50);
        for (int attempt = 0; attempt < 50; attempt++) {
            int x = origin.getX() + Mth.nextInt(expectedRandom, 7, 40)
                    * Mth.nextInt(expectedRandom, -1, 1);
            int y = origin.getY() + Mth.nextInt(expectedRandom, 7, 40)
                    * Mth.nextInt(expectedRandom, -1, 1);
            int z = origin.getZ() + Mth.nextInt(expectedRandom, 7, 40)
                    * Mth.nextInt(expectedRandom, -1, 1);
            candidates.add(new BlockPos(x, y, z));
        }
        return candidates;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Scenario {
        final String name;
        final Event$Result result;
        final Difficulty difficulty;
        final boolean mobSpawning;
        final double summonChance;
        final boolean nullAttacker;
        final boolean customAid;
        final MobSpawnEvent.SpawnPlacementCheck.Result nativePlacementResult;
        final boolean expectSpawn;
        final boolean expectTarget;
        final boolean expectPlacementAttempt;

        int eventCount;
        Zombie eventEntity;
        ServerLevel eventLevel;
        net.minecraft.world.entity.LivingEntity eventAttacker;
        int eventX;
        int eventY;
        int eventZ;
        double eventSummonChance;
        Event$Result eventResult;
        int placementCount;
        MobSpawnEvent.SpawnPlacementCheck.Result lastPlacementResult;
        BlockPos lastPlacementPos;
        boolean verifyFirstPlacementSequence;
        BlockPos expectedFirstPlacementPos;
        BlockPos firstPlacementPos;
        int entityJoinCount;
        Zombie joinedAid;
        final List<String> order = new ArrayList<>();

        private Scenario(
                String name,
                Event$Result result,
                Difficulty difficulty,
                boolean mobSpawning,
                double summonChance,
                boolean nullAttacker,
                boolean customAid,
                MobSpawnEvent.SpawnPlacementCheck.Result nativePlacementResult,
                boolean expectSpawn,
                boolean expectTarget,
                boolean expectPlacementAttempt
        ) {
            this.name = name;
            this.result = result;
            this.difficulty = difficulty;
            this.mobSpawning = mobSpawning;
            this.summonChance = summonChance;
            this.nullAttacker = nullAttacker;
            this.customAid = customAid;
            this.nativePlacementResult = nativePlacementResult;
            this.expectSpawn = expectSpawn;
            this.expectTarget = expectTarget;
            this.expectPlacementAttempt = expectPlacementAttempt;
        }

        static Scenario expectNoSpawn(
                String name, Event$Result result, Difficulty difficulty,
                boolean mobSpawning, double chance, boolean nullAttacker
        ) {
            return new Scenario(name, result, difficulty, mobSpawning, chance, nullAttacker,
                    false, null, false, false, false);
        }

        static Scenario expectSpawn(
                String name, Event$Result result, Difficulty difficulty,
                boolean mobSpawning, double chance, boolean nullAttacker,
                MobSpawnEvent.SpawnPlacementCheck.Result placementResult,
                boolean customAid, boolean expectTarget
        ) {
            return new Scenario(name, result, difficulty, mobSpawning, chance, nullAttacker,
                    customAid, placementResult, true, expectTarget, false);
        }

        static Scenario expectSpawnWithoutTarget(
                String name, Event$Result result, Difficulty difficulty,
                boolean mobSpawning, double chance, boolean nullAttacker,
                MobSpawnEvent.SpawnPlacementCheck.Result placementResult
        ) {
            return new Scenario(name, result, difficulty, mobSpawning, chance, nullAttacker,
                    false, placementResult, true, false, false);
        }

        static Scenario expectNativeVeto(
                String name, Event$Result result, Difficulty difficulty,
                boolean mobSpawning, double chance, boolean nullAttacker,
                MobSpawnEvent.SpawnPlacementCheck.Result placementResult
        ) {
            Scenario scenario = new Scenario(name, result, difficulty, mobSpawning, chance, nullAttacker,
                    false, placementResult, false, false, true);
            scenario.verifyFirstPlacementSequence = true;
            return scenario;
        }
    }

    private static final class ProbeListeners {
        private final ServerLevel level;
        private Zombie watched;
        private Scenario scenario;
        private ProbeArena arena;

        ProbeListeners(ServerLevel level) {
            this.level = level;
        }

        void arm(Zombie hurtZombie, Scenario scenario, ProbeArena arena) {
            this.watched = hurtZombie;
            this.scenario = scenario;
            this.arena = arena;
        }

        void disarm() {
            watched = null;
            scenario = null;
            arena = null;
            activeCustomAid = null;
        }

        @SubscribeEvent
        public void onSummonAid(ZombieEvent$SummonAidEvent event) {
            Scenario active = scenario;
            if (active == null || event.getEntity() != watched) return;
            active.eventCount++;
            active.eventEntity = event.getEntity();
            active.eventLevel = event.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;
            active.eventAttacker = event.getAttacker();
            active.eventX = event.getX();
            active.eventY = event.getY();
            active.eventZ = event.getZ();
            active.eventSummonChance = event.getSummonChance();
            active.order.add("legacy-event");
            if (active.customAid) {
                // The exact object is supplied by the test through the active scenario.
                event.setCustomSummonedAid(activeCustomAid);
            }
            event.setResult(active.result);
            active.eventResult = event.getResult();
        }

        private Zombie activeCustomAid;

        void setCustomAid(Zombie customAid) {
            this.activeCustomAid = customAid;
        }

        @SubscribeEvent
        public void onSpawnPlacement(MobSpawnEvent.SpawnPlacementCheck event) {
            Scenario active = scenario;
            ProbeArena activeArena = arena;
            if (active == null || activeArena == null
                    || event.getLevel() != level
                    || event.getSpawnType() != MobSpawnType.REINFORCEMENT
                    || event.getEntityType() != EntityType.ZOMBIE
                    || !activeArena.contains(event.getPos())) {
                return;
            }

            active.placementCount++;
            if (active.firstPlacementPos == null) {
                active.firstPlacementPos = event.getPos().immutable();
            }
            active.order.add("native-placement");
            if (active.nativePlacementResult != null) {
                event.setResult(active.nativePlacementResult);
            }
            active.lastPlacementResult = event.getResult();
            active.lastPlacementPos = event.getPos();
        }

        @SubscribeEvent
        public void onEntityJoin(EntityJoinLevelEvent event) {
            Scenario active = scenario;
            if (active == null || event.getLevel() != level) return;
            if (event.getEntity() instanceof Zombie zombie && zombie != watched) {
                active.entityJoinCount++;
                active.joinedAid = zombie;
                active.order.add("entity-join");
            }
        }
    }

    private static final class ProbeArena {
        private final ServerLevel level;
        private final int centerX;
        private final int centerZ;
        private final int entityY;
        private final Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        private boolean installed;

        ProbeArena(ServerLevel level) {
            this.level = level;
            BlockPos spawn = level.getSharedSpawnPos();
            this.centerX = spawn.getX();
            this.centerZ = spawn.getZ();
            int min = level.getMinBuildHeight() + 8;
            int max = level.getMaxBuildHeight() - 8;
            this.entityY = Math.max(min, Math.min(max, spawn.getY() + 8));
        }

        int centerX() { return centerX; }
        int centerZ() { return centerZ; }

        boolean contains(BlockPos pos) {
            return Math.abs(pos.getX() - centerX) <= ARENA_RADIUS
                    && Math.abs(pos.getZ() - centerZ) <= ARENA_RADIUS
                    && Math.abs(pos.getY() - entityY) <= ARENA_RADIUS;
        }

        void install() {
            if (installed) throw new IllegalStateException("arena already installed");
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();

            for (int dx = -ARENA_RADIUS; dx <= ARENA_RADIUS; dx++) {
                for (int dz = -ARENA_RADIUS; dz <= ARENA_RADIUS; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    set(new BlockPos(x, entityY - 1, z), stone);
                    set(new BlockPos(x, entityY, z), air);
                    set(new BlockPos(x, entityY + 1, z), air);
                    set(new BlockPos(x, entityY + 2, z), stone);
                }
            }
            installed = true;
        }

        List<Zombie> zombies() {
            return level.getEntitiesOfClass(
                    Zombie.class,
                    new net.minecraft.world.phys.AABB(
                            centerX - ARENA_RADIUS - 8, entityY - ARENA_RADIUS - 8, centerZ - ARENA_RADIUS - 8,
                            centerX + ARENA_RADIUS + 9, entityY + ARENA_RADIUS + 9, centerZ + ARENA_RADIUS + 9
                    )
            );
        }

        void installPlacementPads(List<BlockPos> candidates) {
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (BlockPos pos : candidates) {
                set(pos.below(), stone);
                set(pos, air);
                set(pos.above(), air);
                set(pos.above(2), stone);
            }
        }

        private void set(BlockPos pos, BlockState state) {
            previous.putIfAbsent(pos.immutable(), level.getBlockState(pos));
            level.setBlock(pos, state, 3);
        }

        void restore() {
            if (!installed && previous.isEmpty()) return;
            List<Map.Entry<BlockPos, BlockState>> entries = new ArrayList<>(previous.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<BlockPos, BlockState> entry = entries.get(i);
                level.setBlock(entry.getKey(), entry.getValue(), 3);
            }
            previous.clear();
            installed = false;
        }
    }
}
