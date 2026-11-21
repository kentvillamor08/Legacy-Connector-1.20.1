package legacyconnector.v1_20_1.net.minecraftforge.event.entity.living;

import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$HasResult;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * Forge 1.20.1's result-bearing, non-cancelable zombie reinforcement event.
 * ALLOW forces an aid spawn, DEFAULT preserves the vanilla gates, and DENY suppresses the aid.
 */
@Event$HasResult
public class ZombieEvent$SummonAidEvent extends ZombieEvent {
    private Zombie customSummonedAid;
    private final Level level;
    private final int x;
    private final int y;
    private final int z;
    private final LivingEntity attacker;
    private final double summonChance;
    private Result result = Result.DEFAULT;

    public ZombieEvent$SummonAidEvent(
            Zombie zombie,
            Level level,
            int x,
            int y,
            int z,
            LivingEntity attacker,
            double summonChance) {
        super(zombie);
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.attacker = attacker;
        this.summonChance = summonChance;
    }

    public Zombie getCustomSummonedAid() {
        return customSummonedAid;
    }

    public void setCustomSummonedAid(Zombie customSummonedAid) {
        this.customSummonedAid = customSummonedAid;
    }

    public Level getLevel() {
        return level;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public LivingEntity getAttacker() {
        return attacker;
    }

    public double getSummonChance() {
        return summonChance;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public boolean hasResult() {
        return true;
    }
}
