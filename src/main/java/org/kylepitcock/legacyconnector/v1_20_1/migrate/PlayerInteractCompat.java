package org.kylepitcock.legacyconnector.v1_20_1.migrate;

import java.util.Objects;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Preserves the Forge 1.20.1 PlayerInteractEvent cancellation API on NeoForge 1.21.1.
 *
 * <p>Forge exposed cancellability as a capability query on Event. NeoForge expresses
 * that capability through ICancellableEvent on each concrete event subtype.</p>
 *
 * <p>Forge Event.Result is not a gameplay result for PlayerInteractEvent: this event
 * hierarchy is cancellable but is not annotated with @HasResult. Its old setResult
 * call only wrote an otherwise-unused Event field, so the result bridge deliberately
 * consumes that value without inventing a TriState or InteractionResult.</p>
 */
public final class PlayerInteractCompat {
    private PlayerInteractCompat() {}

    public static boolean isCancelable(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");
        return event instanceof ICancellableEvent;
    }

    public static void setCanceled(PlayerInteractEvent event, boolean canceled) {
        Objects.requireNonNull(event, "event");
        if (!(event instanceof ICancellableEvent cancellable)) {
            throw new UnsupportedOperationException(
                    "This PlayerInteractEvent subtype is not cancellable");
        }
        cancellable.setCanceled(canceled);
    }

    /**
     * Consumes Forge's legacy Event.Result write for PlayerInteractEvent.
     * The legacy event type is not @HasResult, so the stored value is not consulted
     * by Forge's event dispatch or the interaction pipeline.
     */
    public static void ignoreResult(PlayerInteractEvent event, Object result) {
        Objects.requireNonNull(event, "event");
    }
}
