package legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.FillBucketEvent;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.SleepingLocationCheckEvent;
import legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.neoforged.neoforge.common.util.TriState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Forge1201EventResultCompatibilityTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        var dist = net.neoforged.fml.loading.FMLLoader.class.getDeclaredField("dist");
        dist.setAccessible(true);
        dist.set(null, net.neoforged.api.distmarker.Dist.CLIENT);
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$HasResult
    private static class AnnotatedEvent extends net.neoforged.bus.api.Event {}

    private static class InheritedAnnotatedEvent extends AnnotatedEvent {}

    private static class PlainEvent extends net.neoforged.bus.api.Event {}

    @Test
    void hasResultUsesRuntimeAnnotationAndSuperclassSearch() {
        assertEquals(true, ForgeApi1201.eventHasResult(new AnnotatedEvent()));
        assertEquals(true, ForgeApi1201.eventHasResult(new InheritedAnnotatedEvent()));
        assertEquals(false, ForgeApi1201.eventHasResult(new PlainEvent()));
        assertEquals(false, ForgeApi1201.eventHasResult(new Object()));
    }

    @Test
    void mappedNativeResultEventsExposeForgeHasResultSemantics() {
        var applicable = new net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable(null, null, null);
        var placement = new net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck(
                null, null, null, BlockPos.ZERO, RandomSource.create(), true);
        var phantoms = new net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent(null, 2);
        var critical = new net.neoforged.neoforge.event.entity.player.CriticalHitEvent(null, null, 1.0F, false);
        var bonemeal = new net.neoforged.neoforge.event.entity.player.BonemealEvent(
                null, null, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), ItemStack.EMPTY);
        var sapling = new net.neoforged.neoforge.event.level.BlockGrowFeatureEvent(null, RandomSource.create(), BlockPos.ZERO, null);

        assertEquals(true, ForgeApi1201.eventHasResult(applicable));
        assertEquals(true, ForgeApi1201.eventHasResult(placement));
        assertEquals(true, ForgeApi1201.eventHasResult(phantoms));
        assertEquals(true, ForgeApi1201.eventHasResult(critical));
        assertEquals(true, ForgeApi1201.eventHasResult(bonemeal));
        assertEquals(true, ForgeApi1201.eventHasResult(sapling));
    }

    @Test
    void onItemPickupPostsLegacyEventAndReturnsForgeFactoryContract() {
        record PickupDirective(Event$Result result, boolean canceled) {}

        // A standalone, active bus makes this test independent of NeoForge's global startup lifecycle.
        var bus = net.neoforged.bus.api.BusBuilder.builder().build();
        var nextDirective = new AtomicReference<PickupDirective>();
        var eventPosts = new AtomicInteger();
        bus.addListener(EntityItemPickupEvent.class, event -> {
            eventPosts.incrementAndGet();
            PickupDirective directive = nextDirective.getAndSet(null);
            if (directive == null) return;
            event.setResult(directive.result());
            if (directive.canceled()) event.setCanceled(true);
        });

        for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY, Event$Result.ALLOW)) {
            nextDirective.set(new PickupDirective(result, false));
            int expected = result == Event$Result.ALLOW ? 1 : 0;
            assertEquals(expected, ForgeApiTail1201.onItemPickup(bus, null, null), "result=" + result);
            assertNull(nextDirective.get(), "the event bus should consume the probe directive");
        }

        // Cancellation is independent of Result and takes precedence for every Result value.
        for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY, Event$Result.ALLOW)) {
            nextDirective.set(new PickupDirective(result, true));
            assertEquals(-1, ForgeApiTail1201.onItemPickup(bus, null, null), "canceled result=" + result);
            assertNull(nextDirective.get(), "the event bus should consume the probe directive");
        }

        assertEquals(6, eventPosts.get(), "each helper call must post the legacy shim");
    }

    @Test
    void legacyPlayerItemPickupNotificationIsPostedOnTheSuppliedEventBus() {
        var bus = net.neoforged.bus.api.BusBuilder.builder().build();
        var captured = new AtomicReference<legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.PlayerEvent$ItemPickupEvent>();
        bus.addListener(
                legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.PlayerEvent$ItemPickupEvent.class,
                captured::set);

        var originalEntity = (net.minecraft.world.entity.item.ItemEntity) null;
        var zeroDeltaStack = net.minecraft.world.item.ItemStack.EMPTY;
        ForgeApiTail1201.firePlayerItemPickupEvent(bus, null, originalEntity, zeroDeltaStack);

        var posted = captured.get();
        assertNotNull(posted, "the legacy notification shim must be posted");
        assertSame(originalEntity, posted.getOriginalEntity());
        assertSame(zeroDeltaStack, posted.getStack());
        assertEquals(0, posted.getStack().getCount(), "the ALLOW notification can carry zero transferred items");
    }

    @Test
    void entityItemPickupShimPreservesIndependentForgeResultAndCancelableContracts() {
        var event = new EntityItemPickupEvent(null, null);

        assertSame(Event$Result.DEFAULT, event.getResult());
        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(event));
        assertTrue(event.hasResult());
        assertTrue(ForgeApi1201.eventHasResult(event));
        assertTrue(ForgeApi1201.eventIsCancelable(event));
        assertFalse(ForgeApi1201.eventIsCanceled(event));

        // The source Event#setResult call sites are rewritten through these helpers.
        for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY, Event$Result.ALLOW)) {
            ForgeApi1201.eventSetResult(event, result);
            assertSame(result, event.getResult());
            assertSame(result, ForgeApi1201.eventGetResult(event));
            assertFalse(ForgeApi1201.eventIsCanceled(event),
                    "Event.Result must not implicitly cancel the pickup event");
        }

        // Direct compatibility methods and rewritten helper calls share the same event state.
        event.setResult(Event$Result.DENY);
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
        event.setCanceled(true);
        assertTrue(ForgeApi1201.eventIsCanceled(event));
        assertTrue(event.isCanceled());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));

        ForgeApi1201.eventSetCanceled(event, false);
        assertFalse(ForgeApi1201.eventIsCanceled(event));
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void fillBucketShimPreservesIndependentForgeResultAndCancelableContracts() {
        var event = new FillBucketEvent(null, new ItemStack(Items.BUCKET), null, null);

        assertSame(Event$Result.DEFAULT, event.getResult());
        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(event));
        assertTrue(event.hasResult());
        assertTrue(ForgeApi1201.eventHasResult(event));
        assertTrue(ForgeApi1201.eventIsCancelable(event));
        assertFalse(ForgeApi1201.eventIsCanceled(event));

        for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY, Event$Result.ALLOW)) {
            ForgeApi1201.eventSetResult(event, result);
            assertSame(result, event.getResult());
            assertSame(result, ForgeApi1201.eventGetResult(event));
            assertFalse(ForgeApi1201.eventIsCanceled(event),
                    "FillBucketEvent.Result must not implicitly cancel the event");
        }

        ForgeApi1201.eventSetCanceled(event, true);
        assertTrue(ForgeApi1201.eventIsCanceled(event));
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
        ForgeApi1201.eventSetCanceled(event, false);
        assertFalse(ForgeApi1201.eventIsCanceled(event));
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void fillBucketFactoryPostsEventAndKeepsDefaultAndDenyOnVanillaPath() {
        record Directive(Event$Result result, boolean canceled) {}

        var bus = net.neoforged.bus.api.BusBuilder.builder().build();
        var next = new AtomicReference<Directive>();
        var posts = new AtomicInteger();
        bus.addListener(FillBucketEvent.class, event -> {
            posts.incrementAndGet();
            Directive directive = next.getAndSet(null);
            if (directive == null) return;
            event.setResult(directive.result());
            if (directive.canceled()) event.setCanceled(true);
        });

        for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY)) {
            next.set(new Directive(result, false));
            assertNull(ForgeApiTail1201.onBucketUse(
                    bus, null, null, new ItemStack(Items.BUCKET), null),
                    "result=" + result + " must defer to vanilla BucketItem behavior");
            assertNull(next.get(), "the event bus should consume the probe directive");
        }

        next.set(new Directive(Event$Result.ALLOW, true));
        ItemStack heldBucket = new ItemStack(Items.BUCKET);
        InteractionResultHolder<ItemStack> canceled = ForgeApiTail1201.onBucketUse(
                bus, null, null, heldBucket, null);
        assertNotNull(canceled);
        assertSame(net.minecraft.world.InteractionResult.FAIL, canceled.getResult());
        assertSame(heldBucket, canceled.getObject());
        assertNull(next.get(), "the event bus should consume the cancellation directive");
        assertEquals(3, posts.get(), "every invocation must post one FillBucketEvent");
    }

    @Test
    void sleepingCheckShimsPreserveForgeHasResultAndNonCancelableContracts() {
        var location = new SleepingLocationCheckEvent(null, BlockPos.ZERO);
        var time = new SleepingTimeCheckEvent(null, java.util.Optional.empty());

        assertSame(Event$Result.DEFAULT, location.getResult());
        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(location));
        assertTrue(location.hasResult());
        assertTrue(ForgeApi1201.eventHasResult(location));
        assertFalse(ForgeApi1201.eventIsCancelable(location));
        assertFalse(ForgeApi1201.eventIsCanceled(location));

        assertSame(Event$Result.DEFAULT, time.getResult());
        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(time));
        assertTrue(time.hasResult());
        assertTrue(ForgeApi1201.eventHasResult(time));
        assertFalse(ForgeApi1201.eventIsCancelable(time));
        assertFalse(ForgeApi1201.eventIsCanceled(time));

        for (Event$Result result : List.of(Event$Result.DEFAULT, Event$Result.DENY, Event$Result.ALLOW)) {
            ForgeApi1201.eventSetResult(location, result);
            assertSame(result, location.getResult());
            assertSame(result, ForgeApi1201.eventGetResult(location));
            assertFalse(ForgeApi1201.eventIsCanceled(location),
                    "SleepingLocationCheckEvent results must not imply cancellation");

            ForgeApi1201.eventSetResult(time, result);
            assertSame(result, time.getResult());
            assertSame(result, ForgeApi1201.eventGetResult(time));
            assertFalse(ForgeApi1201.eventIsCanceled(time),
                    "SleepingTimeCheckEvent results must not imply cancellation");
        }

        assertThrows(UnsupportedOperationException.class, () -> ForgeApi1201.eventSetCanceled(location, true));
        assertThrows(UnsupportedOperationException.class, () -> ForgeApi1201.eventSetCanceled(time, true));
        assertFalse(ForgeApi1201.eventIsCanceled(location));
        assertFalse(ForgeApi1201.eventIsCanceled(time));
    }

    @Test
    void mobEffectResultUsesNativeNamedResultValues() {
        var event = new net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable(null, null, null);
        ForgeApi1201.eventSetResult(event, Event$Result.DENY);
        assertEquals(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.DO_NOT_APPLY, event.getResult());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
        ForgeApi1201.eventSetResult(event, Event$Result.ALLOW);
        assertEquals(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.APPLY, event.getResult());
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void mobSpawnPlacementResultUsesNativeNamedResultValues() {
        var event = new net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck(
                null, null, null, BlockPos.ZERO, RandomSource.create(), true);
        ForgeApi1201.eventSetResult(event, Event$Result.DENY);
        assertEquals(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck.Result.FAIL, event.getResult());
        assertEquals(false, event.getPlacementCheckResult());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
        ForgeApi1201.eventSetResult(event, Event$Result.ALLOW);
        assertEquals(net.neoforged.neoforge.event.entity.living.MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED, event.getResult());
        assertEquals(true, event.getPlacementCheckResult());
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void phantomSpawnResultUsesNativeNamedResultValues() {
        var event = new net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent(null, 2);
        ForgeApi1201.eventSetResult(event, Event$Result.DENY);
        assertEquals(net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent.Result.DENY, event.getResult());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
        ForgeApi1201.eventSetResult(event, Event$Result.ALLOW);
        assertEquals(net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent.Result.ALLOW, event.getResult());
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void cropGrowResultUsesNativeNamedResultValues() {
        var event = new net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre(
                null, BlockPos.ZERO, Blocks.WHEAT.defaultBlockState());
        ForgeApi1201.eventSetResult(event, Event$Result.DENY);
        assertEquals(net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre.Result.DO_NOT_GROW, event.getResult());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
        ForgeApi1201.eventSetResult(event, Event$Result.ALLOW);
        assertEquals(net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre.Result.GROW, event.getResult());
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void fluidSourceResultProjectsToNativeBooleanWithoutLosingDefault() {
        assertEquals(true, ForgeApi1201.fluidSourceValue(true, Event$Result.ALLOW));
        assertEquals(true, ForgeApi1201.fluidSourceValue(false, Event$Result.ALLOW));
        assertEquals(false, ForgeApi1201.fluidSourceValue(true, Event$Result.DENY));
        assertEquals(false, ForgeApi1201.fluidSourceValue(false, Event$Result.DENY));
        assertEquals(true, ForgeApi1201.fluidSourceValue(true, Event$Result.DEFAULT));
        assertEquals(false, ForgeApi1201.fluidSourceValue(false, Event$Result.DEFAULT));
        assertEquals(false, ForgeApi1201.fluidSourceValue(true, null));
    }

    @Test
    void criticalHitResultProjectsToCriticalHitFlagWithoutCancellation() {
        var event = new net.neoforged.neoforge.event.entity.player.CriticalHitEvent(null, null, 1.0F, false);
        ForgeApi1201.eventSetResult(event, Event$Result.ALLOW);
        assertEquals(true, event.isCriticalHit());
        assertEquals(false, event.isVanillaCritical());
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
        ForgeApi1201.eventSetResult(event, Event$Result.DENY);
        assertEquals(false, event.isCriticalHit());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void bonemealResultIsStoredSeparatelyFromTargetCancellation() {
        var event = new net.neoforged.neoforge.event.entity.player.BonemealEvent(
                null, null, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), ItemStack.EMPTY);
        ForgeApi1201.eventSetResult(event, Event$Result.ALLOW);
        assertEquals(false, event.isCanceled());
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void saplingGrowTreeRetainsSourceNonCancelableSemantics() {
        var event = new net.neoforged.neoforge.event.level.BlockGrowFeatureEvent(
                null, RandomSource.create(), BlockPos.ZERO, null);

        assertEquals(false, ForgeApi1201.eventIsCancelable(event));
        assertEquals(false, ForgeApi1201.eventIsCanceled(event));
        assertThrows(UnsupportedOperationException.class,
                () -> ForgeApi1201.eventSetCanceled(event, true));
        assertEquals(false, event.isCanceled());
    }

    @Test
    void saplingResultIsStoredSeparatelyFromTargetCancellation() {
        var event = new net.neoforged.neoforge.event.level.BlockGrowFeatureEvent(null, RandomSource.create(), BlockPos.ZERO, null);
        ForgeApi1201.eventSetResult(event, Event$Result.DENY);
        assertEquals(false, event.isCanceled());
        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(event));
    }

    @Test
    void sourceResultFacadePreservesForgeConstantOrder() {
        assertEquals(List.of("DENY", "DEFAULT", "ALLOW"),
                Arrays.stream(Event$Result.values()).map(Event$Result::name).toList());
    }

    @Test
    void genericResultSidecarDefaultsToDefaultAndIsIsolatedPerEvent() {
        Object first = new Object();
        Object second = new Object();

        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(first));
        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(second));

        ForgeApi1201.eventSetResult(first, Event$Result.DENY);
        ForgeApi1201.eventSetResult(second, Event$Result.ALLOW);

        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(first));
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(second));
    }

    @Test
    void forgeResultMapsToTriStateByNamedValues() {
        assertSame(TriState.FALSE, ForgeApi1201.toTri(Event$Result.DENY));
        assertSame(TriState.DEFAULT, ForgeApi1201.toTri(Event$Result.DEFAULT));
        assertSame(TriState.TRUE, ForgeApi1201.toTri(Event$Result.ALLOW));

        assertSame(TriState.FALSE, ForgeApiTail1201.toTri(Event$Result.DENY));
        assertSame(TriState.DEFAULT, ForgeApiTail1201.toTri(Event$Result.DEFAULT));
        assertSame(TriState.TRUE, ForgeApiTail1201.toTri(Event$Result.ALLOW));
    }

    @Test
    void triStateMapsBackToForgeResultByNamedValues() {
        assertSame(Event$Result.DENY, ForgeApi1201.fromTri(TriState.FALSE));
        assertSame(Event$Result.DEFAULT, ForgeApi1201.fromTri(TriState.DEFAULT));
        assertSame(Event$Result.ALLOW, ForgeApi1201.fromTri(TriState.TRUE));

        assertSame(Event$Result.DENY, ForgeApiTail1201.fromTri(TriState.FALSE));
        assertSame(Event$Result.DEFAULT, ForgeApiTail1201.fromTri(TriState.DEFAULT));
        assertSame(Event$Result.ALLOW, ForgeApiTail1201.fromTri(TriState.TRUE));
    }
}
