package org.kylepitcock.legacyconnector.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class Forge1201MappingCatalogTest {
    @Test
    void identifiesFmlNamespaceRelocationCandidate() {
        var decision = Forge1201MappingCatalog.classify("net/minecraftforge/fml/common/Mod");

        assertEquals(Forge1201MappingCatalog.Kind.PACKAGE_RELOCATION_CANDIDATE, decision.kind());
        assertEquals("net/neoforged/fml/common/Mod", decision.targetInternalName());
    }

    @Test
    void identifiesEventBusNamespaceRelocationCandidate() {
        var decision = Forge1201MappingCatalog.classify("net/minecraftforge/eventbus/api/IEventBus");

        assertEquals(Forge1201MappingCatalog.Kind.PACKAGE_RELOCATION_CANDIDATE, decision.kind());
        assertEquals("net/neoforged/bus/api/IEventBus", decision.targetInternalName());
    }

    @Test
    void semanticApiDecisionsTakePrecedenceOverBroadPackageMoves() {
        var decision = Forge1201MappingCatalog.classify("net/minecraftforge/common/MinecraftForge");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, decision.kind());
        assertNull(decision.targetInternalName());
        assertNotNull(decision.reason());
    }

    @Test
    void identifiesNetworkingAndDamageTypesAsManualAdapters() {
        var channel = Forge1201MappingCatalog.classify("net/minecraftforge/network/simple/SimpleChannel");
        var damage = Forge1201MappingCatalog.classify(
                "net/minecraftforge/event/entity/living/LivingHurtEvent");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, channel.kind());
        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, damage.kind());
        assertNull(channel.targetInternalName());
        assertNull(damage.targetInternalName());
    }

    @Test
    void pilotTypesMissingFromNeoForgeTargetRequireManualMigration() {
        for (String type : java.util.List.of(
                "net/minecraftforge/fml/common/Mod$EventBusSubscriber",
                "net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus",
                "net/minecraftforge/event/entity/living/LivingAttackEvent",
                "net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn",
                "net/minecraftforge/network/NetworkHooks",
                "net/minecraftforge/registries/IForgeRegistry")) {
            var decision = Forge1201MappingCatalog.classify(type);
            assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, decision.kind(), type);
            assertNull(decision.targetInternalName(), type);
            assertNotNull(decision.reason(), type);
        }
    }

    @Test
    void leavesUnreviewedForgeNamespacesUnmapped() {
        var decision = Forge1201MappingCatalog.classify("net/minecraftforge/example/UnknownApi");

        assertEquals(Forge1201MappingCatalog.Kind.UNMAPPED_FORGE_API, decision.kind());
        assertNull(decision.targetInternalName());
    }

    @Test
    void doesNotTreatMinecraftClassesAsForgePackageMoves() {
        var decision = Forge1201MappingCatalog.classify("net/minecraft/world/entity/Entity");

        assertEquals(Forge1201MappingCatalog.Kind.NOT_FORGE_API, decision.kind());
        assertEquals("net/minecraft/world/entity/Entity", decision.targetInternalName());
    }

    @Test
    void recognizesReviewedNeoForgeTargetNamespaces() {
        org.junit.jupiter.api.Assertions.assertTrue(
                Forge1201MappingCatalog.isNeoForgeRelocationTarget("net/neoforged/fml/ModList"));
        org.junit.jupiter.api.Assertions.assertTrue(
                Forge1201MappingCatalog.isNeoForgeRelocationTarget("net/neoforged/neoforge/event/Event"));
        org.junit.jupiter.api.Assertions.assertFalse(
                Forge1201MappingCatalog.isNeoForgeRelocationTarget("net/minecraft/world/entity/Entity"));
        org.junit.jupiter.api.Assertions.assertFalse(
                Forge1201MappingCatalog.isNeoForgeRelocationTarget("net/neoforged/other/Unreviewed"));
    }

    @Test
    void rejectsBlankClassNames() {
        assertThrows(IllegalArgumentException.class, () -> Forge1201MappingCatalog.classify(" "));
    }

    @Test
    void configSpecAndRegistryObjectUseOnlyExplicitlyReviewedTypeReplacements() {
        var replacements = java.util.Map.of(
                "net/minecraftforge/common/ForgeConfigSpec",
                "net/neoforged/neoforge/common/ModConfigSpec",
                "net/minecraftforge/common/ForgeConfigSpec$Builder",
                "net/neoforged/neoforge/common/ModConfigSpec$Builder",
                "net/minecraftforge/common/ForgeConfigSpec$BooleanValue",
                "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue",
                "net/minecraftforge/common/ForgeConfigSpec$IntValue",
                "net/neoforged/neoforge/common/ModConfigSpec$IntValue",
                "net/minecraftforge/common/ForgeConfigSpec$DoubleValue",
                "net/neoforged/neoforge/common/ModConfigSpec$DoubleValue",
                "net/minecraftforge/registries/RegistryObject",
                "java/util/function/Supplier");

        assertEquals(replacements, Forge1201MappingCatalog.explicitTypeReplacements());
        replacements.forEach((source, target) -> {
            var decision = Forge1201MappingCatalog.classify(source);
            assertEquals(Forge1201MappingCatalog.Kind.EXPLICIT_TYPE_REPLACEMENT, decision.kind(), source);
            assertEquals(target, decision.targetInternalName(), source);
            org.junit.jupiter.api.Assertions.assertTrue(
                    Forge1201MappingCatalog.isNeoForgeRelocationTarget(target), target);
            assertNull(Forge1201MappingCatalog.manualAdapterReason(source), source);
        });

        assertEquals(6, replacements.size());
    }

    @Test
    void tickEventFamilyRequiresPrePostMigration() {
        var client = Forge1201MappingCatalog.classify(
                "net/minecraftforge/event/TickEvent$ClientTickEvent");
        var player = Forge1201MappingCatalog.classify(
                "net/minecraftforge/event/TickEvent$PlayerTickEvent");
        var render = Forge1201MappingCatalog.classify(
                "net/minecraftforge/event/TickEvent$RenderTickEvent");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, client.kind());
        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, player.kind());
        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, render.kind());
    }

    @Test
    void configScreenFactoryRequiresExtensionPointMigration() {
        var factory = Forge1201MappingCatalog.classify(
                "net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, factory.kind());
    }

    @Test
    void forgeModAttributesRequireFieldLevelMigration() {
        var decision = Forge1201MappingCatalog.classify("net/minecraftforge/common/ForgeMod");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, decision.kind());
    }

    @Test
    void eventResultRemainsSemanticButDeferredRegisterUsesReviewedPackageMove() {
        var result = Forge1201MappingCatalog.classify(
                "net/minecraftforge/eventbus/api/Event$Result");
        var deferred = Forge1201MappingCatalog.classify(
                "net/minecraftforge/registries/DeferredRegister");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, result.kind());
        assertEquals(Forge1201MappingCatalog.Kind.PACKAGE_RELOCATION_CANDIDATE, deferred.kind());
        assertEquals("net/neoforged/neoforge/registries/DeferredRegister",
                deferred.targetInternalName());
        assertNull(Forge1201MappingCatalog.manualAdapterReason(
                "net/minecraftforge/registries/DeferredRegister"));
    }

    @Test
    void recognizesReviewedVanillaRegistryTargetTypes() {
        org.junit.jupiter.api.Assertions.assertTrue(Forge1201MappingCatalog.isReviewedTargetApiType(
                "net/minecraft/core/registries/BuiltInRegistries"));
        org.junit.jupiter.api.Assertions.assertTrue(Forge1201MappingCatalog.isReviewedTargetApiType(
                "net/minecraft/core/Registry"));
        org.junit.jupiter.api.Assertions.assertTrue(Forge1201MappingCatalog.isReviewedTargetApiType(
                "net/minecraft/core/DefaultedRegistry"));
        org.junit.jupiter.api.Assertions.assertFalse(Forge1201MappingCatalog.isReviewedTargetApiType(
                "net/minecraft/world/entity/Entity"));
    }

}
