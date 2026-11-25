package org.kylepitcock.legacyconnector.loader.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

final class Mc1201ApiMappingsTest {
    @Test
    void resolvesInheritedVirtualCallRulesUsingTheTargetHierarchy() {
        var mapping = Mc1201ApiMappings.callMapping(
                "net/minecraft/client/player/LocalPlayer",
                "getAttribute",
                "(Lnet/minecraft/world/entity/ai/attributes/Attribute;)"
                        + "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;",
                Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("net/minecraft/world/entity/LivingEntity", mapping.declaringOwner());
        assertEquals(
                "org/kylepitcock/legacyconnector/v1_20_1/migrate/HolderCompat",
                mapping.helperOwner());
        assertEquals("getAttribute", mapping.helperName());
        assertEquals(
                "(Lnet/minecraft/world/entity/LivingEntity;"
                        + "Lnet/minecraft/world/entity/ai/attributes/Attribute;)"
                        + "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;",
                mapping.helperDescriptor());
    }

    @Test
    void resolvesDirectStaticCallRules() {
        var mapping = Mc1201ApiMappings.callMapping(
                "net/minecraft/network/FriendlyByteBuf",
                "writeItem",
                "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/network/FriendlyByteBuf;",
                Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals(
                "org/kylepitcock/legacyconnector/v1_20_1/migrate/MiscCompat",
                mapping.helperOwner());
        assertEquals("writeItem", mapping.helperName());
        assertEquals(
                "(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/item/ItemStack;)"
                        + "Lnet/minecraft/network/FriendlyByteBuf;",
                mapping.helperDescriptor());
    }

    @Test
    void returnsConstructorRuleButDoesNotClaimAllocationEvidence() {
        var mapping = Mc1201ApiMappings.callMapping(
                "net/minecraft/world/effect/MobEffectInstance",
                "<init>",
                "(Lnet/minecraft/world/effect/MobEffect;II)V",
                Opcodes.INVOKESPECIAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals(
                "org/kylepitcock/legacyconnector/v1_20_1/migrate/HolderCompat",
                mapping.helperOwner());
        assertEquals("newEffect", mapping.helperName());
        assertEquals(
                "(Lnet/minecraft/world/effect/MobEffect;II)"
                        + "Lnet/minecraft/world/effect/MobEffectInstance;",
                mapping.helperDescriptor());
    }

    @Test
    void mapsResourceLocationStringConstructorToParseFactory() {
        String owner = "net/minecraft/resources/ResourceLocation";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "<init>", "(Ljava/lang/String;)V", Opcodes.INVOKESPECIAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals(owner, mapping.helperOwner());
        assertEquals("parse", mapping.helperName());
        assertEquals(
                "(Ljava/lang/String;)L" + owner + ";",
                mapping.helperDescriptor());
    }

    @Test
    void mapsPlayerInteractEventCancellationCapabilityQueryToHelper() {
        String owner = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "isCancelable", "()Z", Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("net/neoforged/neoforge/event/entity/player/PlayerInteractEvent", mapping.declaringOwner());
        assertEquals("org/kylepitcock/legacyconnector/v1_20_1/migrate/PlayerInteractCompat", mapping.helperOwner());
        assertEquals("isCancelable", mapping.helperName());
        assertEquals("(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent;)Z", mapping.helperDescriptor());
    }

    @Test
    void mapsPlayerInteractEventCancellationStateQueryToHelper() {
        String owner = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "isCanceled", "()Z", Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("org/kylepitcock/legacyconnector/v1_20_1/migrate/PlayerInteractCompat", mapping.helperOwner());
        assertEquals("isCanceled", mapping.helperName());
        assertEquals("(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent;)Z", mapping.helperDescriptor());
    }

    @Test
    void mapsPlayerInteractEventHasResultCapabilityQueryToHelper() {
        String owner = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "hasResult", "()Z", Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("org/kylepitcock/legacyconnector/v1_20_1/migrate/PlayerInteractCompat", mapping.helperOwner());
        assertEquals("hasResult", mapping.helperName());
        assertEquals("(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent;)Z", mapping.helperDescriptor());
    }

    @Test
    void mapsPlayerInteractEventCancellationMutationToHelper() {
        String owner = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "setCanceled", "(Z)V", Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("org/kylepitcock/legacyconnector/v1_20_1/migrate/PlayerInteractCompat", mapping.helperOwner());
        assertEquals("setCanceled", mapping.helperName());
        assertEquals("(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent;Z)V", mapping.helperDescriptor());
    }

    @Test
    void mapsPlayerInteractEventLegacyResultSetterToWeakSidecarState() {
        String owner = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
        String result = "legacyconnector/v1_20_1/net/minecraftforge/eventbus/api/Event$Result";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "setResult", "(L" + result + ";)V", Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("org/kylepitcock/legacyconnector/v1_20_1/migrate/PlayerInteractCompat", mapping.helperOwner());
        assertEquals("setResult", mapping.helperName());
        assertEquals("(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent;L" + result + ";)V", mapping.helperDescriptor());
    }

    @Test
    void mapsPlayerInteractEventLegacyResultGetterToWeakSidecarState() {
        String owner = "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
        String result = "legacyconnector/v1_20_1/net/minecraftforge/eventbus/api/Event$Result";
        var mapping = Mc1201ApiMappings.callMapping(
                owner, "getResult", "()L" + result + ";", Opcodes.INVOKEVIRTUAL);

        assertNotNull(mapping);
        assertTrue(mapping.usesHelper());
        assertEquals("org/kylepitcock/legacyconnector/v1_20_1/migrate/PlayerInteractCompat", mapping.helperOwner());
        assertEquals("getResult", mapping.helperName());
        assertEquals("(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent;)L" + result + ";", mapping.helperDescriptor());
    }

    @Test
    void doesNotInventCallRulesForUnmappedEventMethods() {
        assertNull(Mc1201ApiMappings.callMapping(
                "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent",
                "unknownEventAccessor", "()Z", Opcodes.INVOKEVIRTUAL));
    }

    @Test
    void retriesStaticFieldCatalogLookupWithClassMappedDescriptor() {
        String sourceDescriptor = "Lnet/minecraft/world/item/enchantment/Enchantment;";
        var mapping = Mc1201ApiMappings.staticFieldMapping(
                "net/minecraft/world/item/enchantment/Enchantments",
                "KNOCKBACK",
                sourceDescriptor);

        assertNotNull(mapping);
        assertEquals("KNOCKBACK", mapping.targetName());
        assertEquals("Lnet/minecraft/resources/ResourceKey;", mapping.targetDescriptor());
        assertEquals("HolderCompat.enchantment", mapping.postAdapter());
        assertTrue(mapping.usesHelper());
        assertEquals(
                "org/kylepitcock/legacyconnector/v1_20_1/migrate/HolderCompat",
                mapping.helperOwner());
        assertEquals("enchantment", mapping.helperName());
        assertEquals(
                "(Lnet/minecraft/resources/ResourceKey;)"
                        + "Lorg/kylepitcock/legacyconnector/v1_20_1/migrate/LegacyEnchantment;",
                mapping.helperDescriptor());
    }
}
