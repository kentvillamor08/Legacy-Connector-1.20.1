package legacyconnector.v1_20_1.net.minecraftforge.network;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.kylepitcock.legacyconnector.v1_20_1.bridge.NetworkBridge1201;

/**
 * Minimal Forge 1.20.1 packet targets used by the Symbiote pilot.
 * Recipients are enumerated so the optional outer PLAY carrier is checked per connection.
 */
public final class PacketDistributor<T> {
    public static final PacketDistributor<ServerPlayer> PLAYER =
            new PacketDistributor<>(Target.PLAYER, NetworkDirection.PLAY_TO_CLIENT);
    public static final PacketDistributor<Entity> TRACKING_ENTITY_AND_SELF =
            new PacketDistributor<>(Target.TRACKING_ENTITY_AND_SELF, NetworkDirection.PLAY_TO_CLIENT);

    private final Target target;
    private final NetworkDirection direction;

    private PacketDistributor(Target target, NetworkDirection direction) {
        this.target = target;
        this.direction = direction;
    }

    public PacketTarget with(Supplier<T> value) {
        return new PacketTarget(this, Objects.requireNonNull(value, "value"));
    }

    private void send(Supplier<T> value, Packet<?> packet) {
        Object supplied = Objects.requireNonNull(value.get(), "packet target supplier returned null");
        if (target == Target.PLAYER) {
            if (!(supplied instanceof ServerPlayer player)) {
                throw new IllegalArgumentException("PLAYER target requires a ServerPlayer");
            }
            NetworkBridge1201.sendToPlayerIfCarrierPresent(player, packet);
            return;
        }
        if (!(supplied instanceof Entity entity)) {
            throw new IllegalArgumentException("TRACKING_ENTITY_AND_SELF target requires an Entity");
        }
        NetworkBridge1201.sendToTrackingPlayersIfCarrierPresent(entity, packet, true);
    }

    private enum Target {
        PLAYER,
        TRACKING_ENTITY_AND_SELF
    }

    public static final class PacketTarget {
        private final PacketDistributor<?> distributor;
        private final Supplier<?> value;

        private PacketTarget(PacketDistributor<?> distributor, Supplier<?> value) {
            this.distributor = distributor;
            this.value = value;
        }

        public void send(Packet<?> packet) {
            distributor.sendUnchecked(value, Objects.requireNonNull(packet, "packet"));
        }

        public NetworkDirection getDirection() {
            return distributor.direction;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void sendUnchecked(Supplier<?> value, Packet<?> packet) {
        send((Supplier) value, packet);
    }
}
