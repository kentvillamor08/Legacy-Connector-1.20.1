package legacyconnector.v1_20_1.net.minecraftforge.event.entity.player;

import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$HasResult;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Forge1201CancelableEventMarker;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Forge1201EventBridge;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Forge1201EventCompat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Forge 1.20.1's cancelable, result-bearing pickup decision event.
 *
 * <p>{@code DENY} has the same consumer behavior as {@code DEFAULT} for this
 * event. Only cancellation vetoes pickup; {@code ALLOW} skips inventory
 * insertion after Forge's pickup-delay and target checks.</p>
 */
@Event$HasResult
public class EntityItemPickupEvent extends PlayerEvent
        implements ICancellableEvent, Forge1201EventCompat, Forge1201CancelableEventMarker {
    private final ItemEntity item;
    private Event$Result result = Event$Result.DEFAULT;

    public EntityItemPickupEvent(Player player, ItemEntity item) {
        super(player);
        this.item = item;
    }

    public ItemEntity getItem() {
        return item;
    }

    public Event$Result getResult() {
        return result;
    }

    public void setResult(Event$Result result) {
        this.result = result;
    }

    public boolean hasResult() {
        return true;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }

    @Override
    public boolean isCanceled() {
        return Forge1201EventBridge.baseIsCanceled(this);
    }

    @Override
    public void setCanceled(boolean canceled) {
        Forge1201EventBridge.baseSetCanceled(this, canceled);
    }
}
