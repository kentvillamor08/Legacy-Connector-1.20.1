package org.kylepitcock.legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import legacyconnector.v1_20_1.net.minecraftforge.event.TickEvent;
import net.minecraft.client.DeltaTracker;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.junit.jupiter.api.Test;

class Forge1201ClientRenderTickTest {
    @Test
    void renderFramePreBecomesLegacyStartEventWithPartialTick() {
        TestDeltaTracker tracker = new TestDeltaTracker(0.375F);

        TickEvent.RenderTickEvent legacy =
                Forge1201Client.toLegacyRenderTick(new RenderFrameEvent.Pre(tracker));

        assertEquals(TickEvent.Type.RENDER, legacy.type);
        assertEquals(LogicalSide.CLIENT, legacy.side);
        assertEquals(TickEvent.Phase.START, legacy.phase);
        assertEquals(0.375F, legacy.renderTickTime, 0.0F);
        assertFalse(tracker.runsNormally);
    }

    @Test
    void renderFramePostBecomesLegacyEndEventWithPartialTick() {
        TestDeltaTracker tracker = new TestDeltaTracker(0.8125F);

        TickEvent.RenderTickEvent legacy =
                Forge1201Client.toLegacyRenderTick(new RenderFrameEvent.Post(tracker));

        assertEquals(TickEvent.Type.RENDER, legacy.type);
        assertEquals(LogicalSide.CLIENT, legacy.side);
        assertEquals(TickEvent.Phase.END, legacy.phase);
        assertEquals(0.8125F, legacy.renderTickTime, 0.0F);
        assertFalse(tracker.runsNormally);
    }

    private static final class TestDeltaTracker implements DeltaTracker {
        private final float partialTick;
        private boolean runsNormally = true;

        private TestDeltaTracker(float partialTick) {
            this.partialTick = partialTick;
        }

        @Override
        public float getGameTimeDeltaPartialTick(boolean runsNormally) {
            this.runsNormally = runsNormally;
            return partialTick;
        }

        @Override
        public float getGameTimeDeltaTicks() {
            return 1.0F;
        }

        @Override
        public float getRealtimeDeltaTicks() {
            return 1.0F;
        }
    }
}
