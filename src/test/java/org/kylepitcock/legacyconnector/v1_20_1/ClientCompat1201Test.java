package org.kylepitcock.legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.kylepitcock.legacyconnector.v1_20_1.migrate.ClientCompat;
import org.kylepitcock.legacyconnector.v1_20_1.migrate.MiscCompat;

class ClientCompat1201Test {
    @Test
    void playerSkinModelNamesRoundTripWithForge1201Names() {
        assertEquals("default", ClientCompat.skinName(net.minecraft.client.resources.PlayerSkin.Model.WIDE));
        assertEquals("slim", ClientCompat.skinName(net.minecraft.client.resources.PlayerSkin.Model.SLIM));

        assertEquals(net.minecraft.client.resources.PlayerSkin.Model.WIDE, ClientCompat.skinModel("default"));
        assertEquals(net.minecraft.client.resources.PlayerSkin.Model.SLIM, ClientCompat.skinModel("slim"));
    }
    @Test
    void entityDimensionsFixedFlagUsesTargetRecordAccessor() {
        assertTrue(MiscCompat.isFixed(net.minecraft.world.entity.EntityDimensions.fixed(1.0F, 2.0F)));
        assertFalse(MiscCompat.isFixed(net.minecraft.world.entity.EntityDimensions.scalable(1.0F, 2.0F)));
    }
}
