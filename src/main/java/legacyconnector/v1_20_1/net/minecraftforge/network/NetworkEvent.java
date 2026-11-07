package legacyconnector.v1_20_1.net.minecraftforge.network;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Connector-owned compatibility view of Forge 1.20.1's NetworkEvent types. */
public final class NetworkEvent {
    private NetworkEvent() {}

    /** Context exposed to legacy SimpleChannel message consumers. */
    public static final class Context {
        private final IPayloadContext delegate;
        private final NetworkDirection direction;
        private volatile boolean packetHandled;

        public Context(IPayloadContext delegate, NetworkDirection direction) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.direction = Objects.requireNonNull(direction, "direction");
        }

        public NetworkDirection getDirection() {
            return direction;
        }

        public void setPacketHandled(boolean handled) {
            packetHandled = handled;
        }

        public boolean getPacketHandled() {
            return packetHandled;
        }

        public CompletableFuture<Void> enqueueWork(Runnable work) {
            return delegate.enqueueWork(Objects.requireNonNull(work, "work"));
        }

        public ServerPlayer getSender() {
            return delegate.flow() == PacketFlow.SERVERBOUND ? (ServerPlayer) delegate.player() : null;
        }

        public Connection getNetworkManager() {
            return delegate.connection();
        }
    }
}
