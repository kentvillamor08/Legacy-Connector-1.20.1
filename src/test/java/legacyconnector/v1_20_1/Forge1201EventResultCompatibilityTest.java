package legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
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
