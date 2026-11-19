package org.kylepitcock.legacyconnector.v1_20_1.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FireProtectionDurationCompatTest {
    @Test
    void appliesForgeFireProtectionReductionAtLevelOne() {
        assertEquals(85, MiscCompat.fireProtectionDampenedTicks(100, 1));
    }

    @Test
    void appliesForgeFireProtectionReductionAtHigherLevels() {
        assertEquals(40, MiscCompat.fireProtectionDampenedTicks(100, 4));
        assertEquals(8, MiscCompat.fireProtectionDampenedTicks(19, 4));
    }

    @Test
    void floorsTheFloatReductionRatherThanTruncatingIt() {
        assertEquals(86, MiscCompat.fireProtectionDampenedTicks(101, 1));
    }

    @Test
    void leavesTicksUnchangedWhenFireProtectionLevelIsNotPositive() {
        assertEquals(101, MiscCompat.fireProtectionDampenedTicks(101, 0));
        assertEquals(101, MiscCompat.fireProtectionDampenedTicks(101, -1));
    }

    @Test
    void preservesMthFloorBehaviorForNegativeRequestedTicks() {
        assertEquals(0, MiscCompat.fireProtectionDampenedTicks(-1, 1));
    }
}
