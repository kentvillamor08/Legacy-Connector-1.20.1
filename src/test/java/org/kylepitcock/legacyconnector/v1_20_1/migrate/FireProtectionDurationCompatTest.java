package org.kylepitcock.legacyconnector.v1_20_1.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FireProtectionDurationCompatTest {
    @Test
    void appliesForgeFireProtectionReductionAtLevelOne() {
        assertEquals(85, LegacyProtectionEnchantment.dampenedFireTicks(100, 1));
    }

    @Test
    void appliesForgeFireProtectionReductionAtHigherLevels() {
        assertEquals(40, LegacyProtectionEnchantment.dampenedFireTicks(100, 4));
        assertEquals(8, LegacyProtectionEnchantment.dampenedFireTicks(19, 4));
    }

    @Test
    void floorsTheFloatReductionRatherThanTruncatingIt() {
        assertEquals(86, LegacyProtectionEnchantment.dampenedFireTicks(101, 1));
    }

    @Test
    void preservesForgeFloatMultiplicationOrderAtRoundingBoundary() {
        // Source bytecode evaluates (level * 0.15F) before multiplying by ticks.
        assertEquals(-24030, LegacyProtectionEnchantment.dampenedFireTicks(480599, 7));
    }

    @Test
    void leavesTicksUnchangedWhenFireProtectionLevelIsNotPositive() {
        assertEquals(101, LegacyProtectionEnchantment.dampenedFireTicks(101, 0));
        assertEquals(101, LegacyProtectionEnchantment.dampenedFireTicks(101, -1));
    }

    @Test
    void preservesMthFloorBehaviorForNegativeRequestedTicks() {
        assertEquals(0, LegacyProtectionEnchantment.dampenedFireTicks(-1, 1));
    }
}
