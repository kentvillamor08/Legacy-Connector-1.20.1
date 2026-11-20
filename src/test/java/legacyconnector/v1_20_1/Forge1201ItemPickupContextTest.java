package legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.common.util.TriState;
import org.junit.jupiter.api.Test;

class Forge1201ItemPickupContextTest {
    @Test
    void clientCallsNeverPostTheLegacyDecisionOrChangeNativePre() {
        for (TriState nativePre : new TriState[] {TriState.DEFAULT, TriState.TRUE}) {
            var context = new Forge1201ItemPickupContext(false, 0);
            var calls = new AtomicInteger();

            assertSame(nativePre, context.afterNativePre(nativePre, () -> {
                calls.incrementAndGet();
                return 1;
            }));
            assertEquals(0, calls.get());
            assertFalse(context.wasLegacyDecisionDispatched());
            assertFalse(context.shouldSkipInventoryAdd());
            assertFalse(context.shouldSuppressNativePost());
            assertFalse(context.shouldPostLegacyNotificationAtSuccessfulDelta());
        }
    }

    @Test
    void nativePreFalseVetoesBeforeTheLegacyDecisionEvent() {
        var context = new Forge1201ItemPickupContext(true, 0);
        var calls = new AtomicInteger();

        assertSame(TriState.FALSE, context.afterNativePre(TriState.FALSE, () -> {
            calls.incrementAndGet();
            return 1;
        }));
        assertEquals(0, calls.get());
        assertFalse(context.wasLegacyDecisionDispatched());
        assertFalse(context.shouldSkipInventoryAdd());
        assertFalse(context.shouldSuppressNativePost());
        assertFalse(context.shouldPostLegacyNotificationAtSuccessfulDelta());
    }

    @Test
    void positiveEntryDelaySuppressesLegacyPreEvenWhenNativeTrueAllowsPickup() {
        for (TriState nativePre : new TriState[] {TriState.DEFAULT, TriState.TRUE}) {
            var context = new Forge1201ItemPickupContext(true, 10);
            var calls = new AtomicInteger();

            assertSame(nativePre, context.afterNativePre(nativePre, () -> {
                calls.incrementAndGet();
                return 1;
            }));
            assertEquals(0, calls.get());
            assertFalse(context.wasLegacyDecisionDispatched());
            assertFalse(context.shouldSkipInventoryAdd());
            assertFalse(context.shouldSuppressNativePost());
            assertFalse(context.shouldPostLegacyNotificationAtSuccessfulDelta());
        }
    }

    @Test
    void negativeEntryDelayStillDispatchesLegacyDecisionLikeForge() {
        var context = new Forge1201ItemPickupContext(true, -1);
        var calls = new AtomicInteger();

        // Forge's outer source gate returns only when pickupDelay > 0.
        // NeoForge's later DEFAULT gate still requires exactly zero; that gate
        // remains in ItemEntity.playerTouch and is deliberately not rewritten here.
        assertSame(TriState.DEFAULT, context.afterNativePre(TriState.DEFAULT, () -> {
            calls.incrementAndGet();
            return 0;
        }));

        assertEquals(1, calls.get());
        assertTrue(context.wasLegacyDecisionDispatched());
        assertFalse(context.shouldSkipInventoryAdd());
        assertFalse(context.shouldSuppressNativePost());
    }

    @Test
    void legacyCancellationVetoesBothNativeDefaultAndNativeTrue() {
        for (TriState nativePre : new TriState[] {TriState.DEFAULT, TriState.TRUE}) {
            var context = new Forge1201ItemPickupContext(true, 0);

            assertSame(TriState.FALSE, context.afterNativePre(nativePre, () -> -1));
            assertTrue(context.wasLegacyDecisionDispatched());
            assertFalse(context.shouldSkipInventoryAdd());
            assertFalse(context.shouldSuppressNativePost());
            assertFalse(context.shouldPostLegacyNotificationAtSuccessfulDelta());
        }
    }

    @Test
    void legacyDefaultAndDenyKeepTheNativePreResultAndRealInventoryPath() {
        // Forge's factory helper intentionally collapses both DEFAULT and DENY to 0.
        for (TriState nativePre : new TriState[] {TriState.DEFAULT, TriState.TRUE}) {
            var context = new Forge1201ItemPickupContext(true, 0);

            assertSame(nativePre, context.afterNativePre(nativePre, () -> 0));
            assertTrue(context.wasLegacyDecisionDispatched());
            assertFalse(context.shouldSkipInventoryAdd());
            assertFalse(context.shouldSuppressNativePost());
            assertTrue(context.shouldPostLegacyNotificationAtSuccessfulDelta());
        }
    }

    @Test
    void legacyAllowSkipsOnlyInventoryAddAndSuppressesNativePostWithoutChangingPre() {
        for (TriState nativePre : new TriState[] {TriState.DEFAULT, TriState.TRUE}) {
            var context = new Forge1201ItemPickupContext(true, 0);

            assertSame(nativePre, context.afterNativePre(nativePre, () -> 1));
            assertTrue(context.wasLegacyDecisionDispatched());
            assertTrue(context.shouldSkipInventoryAdd());
            assertTrue(context.shouldSuppressNativePost());
            assertTrue(context.shouldPostLegacyNotificationAtSuccessfulDelta());
        }
    }

    @Test
    void defaultNativePreRemainsInControlOfTheLaterLiveDelayAndOwnerGate() {
        var context = new Forge1201ItemPickupContext(true, 0);
        var currentDelay = new AtomicInteger(0);
        var ownerMatchesPlayer = new java.util.concurrent.atomic.AtomicBoolean(true);

        TriState afterPre = context.afterNativePre(TriState.DEFAULT, () -> {
            // Simulate legacy listeners changing live target state during the event.
            currentDelay.set(7);
            ownerMatchesPlayer.set(false);
            return 0;
        });

        // The compatibility context must not freeze/recompute the native gate.
        assertSame(TriState.DEFAULT, afterPre);
        assertEquals(7, currentDelay.get());
        assertFalse(ownerMatchesPlayer.get());
        assertFalse(currentDelay.get() == 0 && ownerMatchesPlayer.get());
        assertFalse(context.shouldSkipInventoryAdd());
    }

    @Test
    void nestedPickupInvocationsKeepIndependentDecisionState() {
        var outer = new Forge1201ItemPickupContext(true, 0);
        var inner = new Forge1201ItemPickupContext(true, 0);

        assertSame(TriState.DEFAULT, outer.afterNativePre(TriState.DEFAULT, () -> {
            assertSame(TriState.TRUE, inner.afterNativePre(TriState.TRUE, () -> 1));
            assertTrue(inner.shouldSkipInventoryAdd());
            return 0;
        }));

        assertFalse(outer.shouldSkipInventoryAdd());
        assertFalse(outer.shouldSuppressNativePost());
        assertTrue(outer.shouldPostLegacyNotificationAtSuccessfulDelta());
        assertTrue(inner.shouldSkipInventoryAdd());
        assertTrue(inner.shouldSuppressNativePost());
    }

    @Test
    void legacyDecisionSupplierIsInvokedOnlyOnce() {
        var context = new Forge1201ItemPickupContext(true, 0);

        context.afterNativePre(TriState.DEFAULT, () -> 0);
        assertThrows(IllegalStateException.class,
                () -> context.afterNativePre(TriState.DEFAULT, () -> 0));
    }

    @Test
    void invalidLegacyDecisionResultIsRejected() {
        var context = new Forge1201ItemPickupContext(true, 0);

        assertThrows(IllegalStateException.class,
                () -> context.afterNativePre(TriState.DEFAULT, () -> 2));
        assertFalse(context.wasLegacyDecisionDispatched());
        assertFalse(context.shouldSkipInventoryAdd());
        assertFalse(context.shouldPostLegacyNotificationAtSuccessfulDelta());
    }

    @Test
    void eventDispatchExceptionDoesNotCreateACompletedLegacyDecision() {
        var context = new Forge1201ItemPickupContext(true, 0);

        assertThrows(IllegalArgumentException.class,
                () -> context.afterNativePre(TriState.DEFAULT, () -> {
                    throw new IllegalArgumentException("listener failure");
                }));
        assertFalse(context.wasLegacyDecisionDispatched());
        assertFalse(context.shouldSkipInventoryAdd());
        assertFalse(context.shouldSuppressNativePost());
        assertFalse(context.shouldPostLegacyNotificationAtSuccessfulDelta());
    }
}
