package org.kylepitcock.legacyconnector.v1_20_1.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

class LegacyEnchantmentDamageBonusTest {
    @Test
    void stackAwareDefaultDelegatesToLegacyTwoArgumentOverride() {
        LegacyEnchantment enchantment = new LegacyEnchantment(LegacyEnchantment.Rarity.COMMON, null, new net.minecraft.world.entity.EquipmentSlot[0]) {
            @Override
            public float getDamageBonus(int level, LegacyMobType type) {
                return level * 0.75F;
            }
        };

        assertEquals(1.5F, enchantment.getDamageBonus(2, LegacyMobType.UNDEAD, ItemStack.EMPTY), 0.0F);
    }

    @Test
    void stackAwareOverrideReceivesTheExactEnchantedStack() {
        ItemStack expectedStack = ItemStack.EMPTY;
        ItemStack[] observedStack = new ItemStack[1];
        LegacyEnchantment enchantment = new LegacyEnchantment(LegacyEnchantment.Rarity.COMMON, null, new net.minecraft.world.entity.EquipmentSlot[0]) {
            @Override
            public float getDamageBonus(int level, LegacyMobType type, ItemStack stack) {
                observedStack[0] = stack;
                return stack == expectedStack ? 3.25F : 0.0F;
            }
        };

        assertEquals(3.25F, enchantment.getDamageBonus(4, LegacyMobType.ARTHROPOD, expectedStack), 0.0F);
        assertSame(expectedStack, observedStack[0]);
    }

    @Test
    void legacyDamageEnchantmentStillUsesItsTwoArgumentFormulaThroughTheNewHook() {
        LegacyDamageEnchantment sharpness = new LegacyDamageEnchantment(LegacyEnchantment.Rarity.COMMON, 0);

        assertEquals(2.0F, sharpness.getDamageBonus(3, LegacyMobType.UNDEFINED, ItemStack.EMPTY), 0.0F);
    }
}
