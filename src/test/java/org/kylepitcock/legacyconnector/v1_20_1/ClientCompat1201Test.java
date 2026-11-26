package org.kylepitcock.legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.kylepitcock.legacyconnector.v1_20_1.migrate.ClientCompat;

class ClientCompat1201Test {
    @Test
    void playerSkinModelNamesRoundTripWithForge1201Names() {
        assertEquals("default", ClientCompat.skinName(net.minecraft.client.resources.PlayerSkin.Model.WIDE));
        assertEquals("slim", ClientCompat.skinName(net.minecraft.client.resources.PlayerSkin.Model.SLIM));

        assertEquals(net.minecraft.client.resources.PlayerSkin.Model.WIDE, ClientCompat.skinModel("default"));
        assertEquals(net.minecraft.client.resources.PlayerSkin.Model.SLIM, ClientCompat.skinModel("slim"));
    }
}
