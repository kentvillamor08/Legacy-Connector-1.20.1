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
    void doesNotInventCallRulesForUnmappedConstructorOrEventMethods() {
        assertNull(Mc1201ApiMappings.callMapping(
                "net/minecraft/resources/ResourceLocation",
                "<init>", "(Ljava/lang/String;)V", Opcodes.INVOKESPECIAL));
        assertNull(Mc1201ApiMappings.callMapping(
                "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent",
                "isCancelable", "()Z", Opcodes.INVOKEVIRTUAL));
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
