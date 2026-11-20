package legacyconnector.v1_20_1;

import java.util.Objects;
import java.util.function.IntSupplier;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Per-invocation state for the Forge 1.20.1 to NeoForge 1.21.1 item-pickup
 * transaction bridge.
 *
 * <p>The ItemEntity.playerTouch mixin stores one instance in a MixinExtras
 * {@code @Share} / {@code LocalRef} for that invocation. Do not move this
 * state to a static field, item-keyed map, or thread-local: nested pickup
 * calls must receive independent state.</p>
 *
 * <p>This class only decides how the legacy decision event composes with
 * NeoForge's already-posted native Pre result. It deliberately does not
 * reproduce NeoForge's later live pickup-delay / owner checks; those checks
 * must remain in the target method and observe mutations made by event
 * listeners.</p>
 */
public final class Forge1201ItemPickupContext {
    private static final int CANCELLED = -1;
    private static final int VANILLA = 0;
    private static final int ALLOW = 1;

    private final boolean serverSide;
    private final int entryPickupDelay;

    private boolean nativePreHandled;
    private boolean legacyDecisionDispatched;
    private int legacyDecision = Integer.MIN_VALUE;

    /**
     * Creates state for one ItemEntity.playerTouch invocation.
     *
     * @param serverSide whether the entity is on the logical server
     * @param entryPickupDelay pickup delay captured before native Pre dispatch
     */
    public Forge1201ItemPickupContext(boolean serverSide, int entryPickupDelay) {
        this.serverSide = serverSide;
        this.entryPickupDelay = entryPickupDelay;
    }

    /**
     * Composes the already-dispatched NeoForge Pre result with Forge's older
     * integer decision event.
     *
     * <ul>
     *   <li>Client calls never post the legacy event.</li>
     *   <li>Native FALSE is a hard veto and is checked before the legacy event.</li>
     *   <li>The legacy event is posted only when the entry-time delay was zero.</li>
     *   <li>Legacy cancellation (-1) vetoes the pickup.</li>
     *   <li>Legacy DEFAULT/DENY (0) preserve the native Pre result and inventory path.</li>
     *   <li>Legacy ALLOW (1) preserves the native Pre result; the mixin separately
     *       skips only Inventory.add and suppresses native Post.</li>
     * </ul>
     *
     * <p>The supplier is invoked at most once. Exceptions are allowed to
     * propagate to the target method; the outer {@code @WrapMethod} scope
     * clears the shared reference in its {@code finally} block.</p>
     */
    public TriState afterNativePre(TriState nativePreResult, IntSupplier postLegacyDecision) {
        Objects.requireNonNull(nativePreResult, "nativePreResult");
        Objects.requireNonNull(postLegacyDecision, "postLegacyDecision");

        if (nativePreHandled) {
            throw new IllegalStateException("Native item-pickup Pre was handled more than once");
        }
        nativePreHandled = true;

        if (!serverSide || entryPickupDelay != 0 || nativePreResult == TriState.FALSE) {
            return nativePreResult;
        }

        int result = postLegacyDecision.getAsInt();
        if (result != CANCELLED && result != VANILLA && result != ALLOW) {
            throw new IllegalStateException("Unexpected Forge item-pickup decision result: " + result);
        }

        legacyDecision = result;
        legacyDecisionDispatched = true;

        // Cancellation is independent of Result and overrides even native TRUE.
        return result == CANCELLED ? TriState.FALSE : nativePreResult;
    }

    /** True only for Forge ALLOW; DEFAULT and DENY keep the real insertion. */
    public boolean shouldSkipInventoryAdd() {
        return legacyDecisionDispatched && legacyDecision == ALLOW;
    }

    /** Native ItemEntityPickupEvent.Post is suppressed only for the no-add ALLOW path. */
    public boolean shouldSuppressNativePost() {
        return shouldSkipInventoryAdd();
    }

    /**
     * Called only at the target's successful transfer-delta point. For ALLOW,
     * the delta stack is intentionally empty; for DEFAULT/DENY it carries the
     * real inserted delta. Failed inventory insertion returns before that
     * target point and therefore never calls the legacy notification helper.
     */
    public boolean shouldPostLegacyNotificationAtSuccessfulDelta() {
        return legacyDecisionDispatched && legacyDecision != CANCELLED;
    }

    public boolean wasLegacyDecisionDispatched() {
        return legacyDecisionDispatched;
    }
}
