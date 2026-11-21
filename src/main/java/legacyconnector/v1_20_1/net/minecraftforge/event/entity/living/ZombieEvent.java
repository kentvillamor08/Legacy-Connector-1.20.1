package legacyconnector.v1_20_1.net.minecraftforge.event.entity.living;

import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.event.entity.EntityEvent;

/** Base Forge 1.20.1 event for zombie reinforcement summons. */
public class ZombieEvent extends EntityEvent {
    private final Zombie zombie;

    public ZombieEvent(Zombie zombie) {
        super(zombie);
        this.zombie = zombie;
    }

    @Override
    public Zombie getEntity() {
        return zombie;
    }
}
