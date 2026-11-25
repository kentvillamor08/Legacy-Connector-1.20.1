package org.kylepitcock.legacyconnector.v1_20_1.migrate;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Preserves the Forge 1.20.1 PlayerInteractEvent cancellation and legacy result
 * accessors on NeoForge 1.21.1.
 *
 * <p>Forge exposed cancellability as a capability query on Event. NeoForge expresses
 * that capability through ICancellableEvent on each concrete event subtype.</p>
 *
 * <p>Only selected PlayerInteractEvent subtypes are cancellable, and the hierarchy
 * is not a Forge @HasResult event. Its legacy result value therefore does not steer
 * the interaction pipeline. Forge still stores that value for callers of
 * Event#getResult(), so this adapter preserves the observable state without
 * incorrectly translating DENY into an InteractionResult or TriState.</p>
 */
public final class PlayerInteractCompat {
    private static final Map<PlayerInteractEvent, Event$Result> LEGACY_RESULTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PlayerInteractCompat() {}

    public static boolean isCancelable(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");
        return event instanceof ICancellableEvent;
    }

    public static boolean isCanceled(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");
        return event instanceof ICancellableEvent cancellable && cancellable.isCanceled();
    }

    public static boolean hasResult(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");
        return false;
    }

    public static void setCanceled(PlayerInteractEvent event, boolean canceled) {
        Objects.requireNonNull(event, "event");
        if (!(event instanceof ICancellableEvent cancellable)) {
            throw new IllegalArgumentException("Attempted to cancel a uncancelable event");
        }
        cancellable.setCanceled(canceled);
    }

    /** Preserves the legacy Event#setResult value for consumers that subsequently call getResult. */
    public static void setResult(PlayerInteractEvent event, Event$Result result) {
        Objects.requireNonNull(event, "event");
        LEGACY_RESULTS.put(event, result);
    }

    /** Returns Forge's default result until a legacy setResult call stores another value. */
    public static Event$Result getResult(PlayerInteractEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (LEGACY_RESULTS) {
            return LEGACY_RESULTS.containsKey(event)
                    ? LEGACY_RESULTS.get(event)
                    : Event$Result.DEFAULT;
        }
    }
}
