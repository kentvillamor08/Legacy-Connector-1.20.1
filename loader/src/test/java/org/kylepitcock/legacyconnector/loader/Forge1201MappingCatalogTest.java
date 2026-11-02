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
    void rejectsBlankClassNames() {
        assertThrows(IllegalArgumentException.class, () -> Forge1201MappingCatalog.classify(" "));
    }

    @Test
    void configSpecNestedTypesAreNotMistakenForSimplePackageMoves() {
        var builder = Forge1201MappingCatalog.classify(
                "net/minecraftforge/common/ForgeConfigSpec$Builder");
        var intValue = Forge1201MappingCatalog.classify(
                "net/minecraftforge/common/ForgeConfigSpec$IntValue");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, builder.kind());
        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, intValue.kind());
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
    void eventResultAndDeferredRegisterRequireSemanticReview() {
        var result = Forge1201MappingCatalog.classify(
                "net/minecraftforge/eventbus/api/Event$Result");
        var deferred = Forge1201MappingCatalog.classify(
                "net/minecraftforge/registries/DeferredRegister");

        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, result.kind());
        assertEquals(Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED, deferred.kind());
    }

}
