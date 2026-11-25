package org.kylepitcock.legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import legacyconnector.v1_20_1.net.minecraftforge.event.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.LogicalSide;
import org.junit.jupiter.api.Test;

class Forge1201ServerTickEventTest {
    @Test
    void serverGetterUsesTheSuppliedNativeServerAccessorLazily() {
        AtomicInteger serverReads = new AtomicInteger();
        TickEvent.ServerTickEvent legacy = new TickEvent.ServerTickEvent(
                TickEvent.Phase.END,
                () -> true,
                () -> {
                    serverReads.incrementAndGet();
                    return (MinecraftServer) null;
                });

        assertEquals(TickEvent.Type.SERVER, legacy.type);
        assertEquals(LogicalSide.SERVER, legacy.side);
        assertEquals(TickEvent.Phase.END, legacy.phase);
        assertEquals(0, serverReads.get());

        assertNull(legacy.getServer());
        assertEquals(1, serverReads.get());
    }

    @Test
    void haveTimeStillDelegatesToItsOriginalSupplier() {
        AtomicInteger haveTimeReads = new AtomicInteger();
        TickEvent.ServerTickEvent legacy = new TickEvent.ServerTickEvent(
                TickEvent.Phase.START,
                () -> {
                    haveTimeReads.incrementAndGet();
                    return true;
                },
                () -> null);

        assertEquals(0, haveTimeReads.get());
        assertTrue(legacy.haveTime());
        assertEquals(1, haveTimeReads.get());
    }
}
