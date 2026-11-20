package legacyconnector.v1_20_1.net.minecraftforge.event.entity.player;

import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/**
 * Forge 1.20.1's result-only check that an entity's current sleeping position remains valid.
 * ALLOW accepts the position, DENY rejects it, and DEFAULT retains the target bed-state result.
 */
@legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$HasResult
public class SleepingLocationCheckEvent extends LivingEvent {
    private final BlockPos sleepingLocation;
    private Event$Result result = Event$Result.DEFAULT;

    public SleepingLocationCheckEvent(LivingEntity sleeper, BlockPos sleepingLocation) {
        super(sleeper);
        this.sleepingLocation = sleepingLocation;
    }

    public BlockPos getSleepingLocation() {
        return sleepingLocation;
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
}
