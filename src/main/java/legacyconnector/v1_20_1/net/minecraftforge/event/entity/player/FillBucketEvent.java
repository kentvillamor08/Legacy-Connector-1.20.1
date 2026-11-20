package legacyconnector.v1_20_1.net.minecraftforge.event.entity.player;

import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$HasResult;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Forge1201CancelableEventMarker;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Forge1201EventBridge;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Forge1201EventCompat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Forge 1.20.1's cancelable, result-bearing bucket fill event.
 *
 * <p>Cancellation returns FAIL. ALLOW uses the filled stack supplied by a
 * listener and completes Forge's held-bucket/inventory/drop transaction.
 * DEFAULT and DENY both defer to the normal BucketItem use path.</p>
 */
@Event$HasResult
public class FillBucketEvent extends PlayerEvent
        implements ICancellableEvent, Forge1201EventCompat, Forge1201CancelableEventMarker {
    private final ItemStack current;
    private final HitResult target;
    private final Level level;
    private ItemStack result;

    public FillBucketEvent(Player player, ItemStack current, Level level, HitResult target) {
        super(player);
        this.current = current;
        this.level = level;
        this.target = target;
    }

    public ItemStack getEmptyBucket() {
        return current;
    }

    public HitResult getTarget() {
        return target;
    }

    public Level getLevel() {
        return level;
    }

    public ItemStack getFilledBucket() {
        return result;
    }

    public void setFilledBucket(ItemStack bucket) {
        this.result = bucket;
    }

    public Event$Result getResult() {
        return resultState;
    }

    public void setResult(Event$Result result) {
        this.resultState = result;
    }

    public boolean hasResult() {
        return true;
    }

    private Event$Result resultState = Event$Result.DEFAULT;

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
