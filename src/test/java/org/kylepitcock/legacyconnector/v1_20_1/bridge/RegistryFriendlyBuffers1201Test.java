package org.kylepitcock.legacyconnector.v1_20_1.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.kylepitcock.legacyconnector.core.network.LegacyBuffers;

class RegistryFriendlyBuffers1201Test {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void heapBufferCarriesRegistryAndNeoForgeConnectionContext() {
        ByteBuf raw = LegacyBuffers.buffer();
        try {
            RegistryFriendlyByteBuf buffer = RegistryFriendlyBuffers1201.wrap(raw, RegistryAccess.EMPTY);
            assertTrue(buffer.hasArray(), "legacy packet serializers require an accessible heap array");
            assertEquals(ConnectionType.NEOFORGE, buffer.getConnectionType());
            assertNotNull(buffer.registryAccess());
            assertEquals(RegistryAccess.EMPTY, buffer.registryAccess());
        } finally {
            raw.release();
        }
    }

    @Test
    void optionalItemStackCodecRoundTripsEmptyStack() {
        ByteBuf raw = LegacyBuffers.buffer();
        try {
            RegistryFriendlyByteBuf buffer = RegistryFriendlyBuffers1201.wrap(raw, RegistryAccess.EMPTY);
            ItemStackCodec1201.writeOptional(buffer, ItemStack.EMPTY);
            ItemStack decoded = ItemStackCodec1201.readOptional(buffer);
            assertTrue(decoded.isEmpty());
        } finally {
            raw.release();
        }
    }

    @Test
    void nullLegacySlotEncodesAsEmptyStack() {
        ByteBuf raw = LegacyBuffers.buffer();
        try {
            RegistryFriendlyByteBuf buffer = RegistryFriendlyBuffers1201.wrap(raw, RegistryAccess.EMPTY);
            ItemStackCodec1201.writeOptional(buffer, null);
            assertTrue(ItemStackCodec1201.readOptional(buffer).isEmpty());
        } finally {
            raw.release();
        }
    }
}
