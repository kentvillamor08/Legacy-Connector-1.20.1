package org.kylepitcock.legacyconnector.v1_20_1.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import legacyconnector.v1_20_1.net.minecraftforge.network.NetworkDirection;
import legacyconnector.v1_20_1.net.minecraftforge.network.NetworkRegistry;
import legacyconnector.v1_20_1.net.minecraftforge.network.simple.SimpleChannel;
import org.junit.jupiter.api.Test;

class NetworkBridge1201Test {
    @Test
    void versionSupplierIsEvaluatedExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("legacyconnector_test", "version_supplier");
        NetworkRegistry.newSimpleChannel(
                id, () -> "version-" + calls.incrementAndGet(),
                NetworkRegistry.acceptMissingOr("version-1"), NetworkRegistry.acceptMissingOr("version-1"));
        assertEquals(1, calls.get());
    }

    @Test
    void missingChannelPredicateUsesExactForgeMarker() {
        assertTrue(NetworkRegistry.acceptMissingOr("1").test(NetworkRegistry.ABSENT_VERSION));
        assertTrue(NetworkRegistry.acceptMissingOr("1").test("1"));
        assertFalse(NetworkRegistry.acceptMissingOr("1").test("2"));
        assertTrue(NetworkRegistry.acceptMissingOr(value -> value.startsWith("1")).test("1.2"));
        assertFalse(NetworkRegistry.acceptMissingOr(value -> value.startsWith("1")).test("2"));
    }

    @Test
    void messageBuilderRegistersTheUnsignedByteDiscriminator() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("legacyconnector_test", "wire_id");
        SimpleChannel channel = NetworkRegistry.newSimpleChannel(
                id, () -> "1", NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1"));
        channel.messageBuilder(TestMessage.class, 257, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buffer) -> buffer.writeInt(message.value))
                .decoder(buffer -> new TestMessage(buffer.readInt()))
                .consumerMainThread((message, context) -> {})
                .add();
        assertTrue(NetworkBridge1201.hasRegisteredMessage(id, 1));
    }

    @Test
    void duplicateChannelIdsFailClosed() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("legacyconnector_test", "duplicate");
        NetworkRegistry.newSimpleChannel(
                id, () -> "1", NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1"));
        assertThrows(IllegalStateException.class, () -> NetworkRegistry.newSimpleChannel(
                id, () -> "1", NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1")));
    }

    private record TestMessage(int value) {}
}
