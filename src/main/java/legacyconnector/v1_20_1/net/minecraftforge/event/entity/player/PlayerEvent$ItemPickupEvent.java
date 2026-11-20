package legacyconnector.v1_20_1.net.minecraftforge.event.entity.player;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Forge 1.20.1's {@code PlayerEvent.ItemPickupEvent} notification.
 *
 * <p>This remains distinct from NeoForge's {@code ItemEntityPickupEvent.Post}:
 * Forge also posts it when {@code EntityItemPickupEvent} has result ALLOW and
 * the inventory transfer amount is zero. The supplied stack is the amount
 * reported as picked up by the Forge caller, not necessarily the live remainder
 * of the item entity.</p>
 */
public class PlayerEvent$ItemPickupEvent extends PlayerEvent {
    private final ItemEntity originalEntity;
    private final ItemStack stack;

    public PlayerEvent$ItemPickupEvent(Player player, ItemEntity originalEntity, ItemStack stack) {
        super(player);
        this.originalEntity = originalEntity;
        this.stack = stack;
    }

    public ItemEntity getOriginalEntity() {
        return originalEntity;
    }

    public ItemStack getStack() {
        return stack;
    }
}
