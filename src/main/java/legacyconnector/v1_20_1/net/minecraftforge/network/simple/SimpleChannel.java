package legacyconnector.v1_20_1.net.minecraftforge.network.simple;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import legacyconnector.v1_20_1.net.minecraftforge.network.NetworkDirection;
import legacyconnector.v1_20_1.net.minecraftforge.network.NetworkEvent;
import legacyconnector.v1_20_1.net.minecraftforge.network.PacketDistributor;
import org.kylepitcock.legacyconnector.v1_20_1.bridge.NetworkBridge1201;

/** Connector-owned implementation of the Forge 1.20.1 SimpleChannel methods used by Symbiote. */
public final class SimpleChannel {
    private final NetworkBridge1201.Channel channel;

    public SimpleChannel(NetworkBridge1201.Channel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    public <M> MessageBuilder<M> messageBuilder(Class<M> messageType, int discriminator, NetworkDirection direction) {
        return new MessageBuilder<>(channel, messageType, discriminator, direction);
    }

    public <M> void send(PacketDistributor.PacketTarget target, M message) {
        Objects.requireNonNull(target, "target");
        NetworkDirection direction = target.getDirection();
        if (direction != NetworkDirection.PLAY_TO_CLIENT) {
            throw new IllegalArgumentException("PacketDistributor targets in this shim are clientbound");
        }
        NetworkBridge1201.MessageDefinition definition = NetworkBridge1201.requireMessage(channel, message);
        if (definition.direction() != direction) {
            throw new IllegalArgumentException("Message direction does not match PacketDistributor target");
        }
        target.sendWithRegistryAccess(registryAccess -> {
            CustomPacketPayload payload = NetworkBridge1201.encode(channel, message, registryAccess);
            return new ClientboundCustomPayloadPacket(payload);
        });
    }

    public <M> void sendToServer(M message) {
        NetworkBridge1201.MessageDefinition definition = NetworkBridge1201.requireMessage(channel, message);
        if (definition.direction() != NetworkDirection.PLAY_TO_SERVER) {
            throw new IllegalArgumentException("sendToServer requires a PLAY_TO_SERVER message");
        }
        NetworkBridge1201.sendToServer(channel, message);
    }

    public static final class MessageBuilder<M> {
        private final NetworkBridge1201.Channel channel;
        private final Class<M> messageType;
        private final int discriminator;
        private final NetworkDirection direction;
        private BiConsumer<M, FriendlyByteBuf> encoder;
        private Function<FriendlyByteBuf, M> decoder;
        private BiConsumer<M, Supplier<NetworkEvent.Context>> consumer;

        public MessageBuilder(
                NetworkBridge1201.Channel channel,
                Class<M> messageType,
                int discriminator,
                NetworkDirection direction) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.messageType = Objects.requireNonNull(messageType, "messageType");
            this.discriminator = discriminator;
            this.direction = Objects.requireNonNull(direction, "direction");
        }

        public MessageBuilder<M> encoder(BiConsumer<M, FriendlyByteBuf> encoder) {
            this.encoder = Objects.requireNonNull(encoder, "encoder");
            return this;
        }

        public MessageBuilder<M> decoder(Function<FriendlyByteBuf, M> decoder) {
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            return this;
        }

        public MessageBuilder<M> consumerMainThread(BiConsumer<M, Supplier<NetworkEvent.Context>> consumer) {
            this.consumer = Objects.requireNonNull(consumer, "consumer");
            return this;
        }

        public void add() {
            if (encoder == null || decoder == null || consumer == null) {
                throw new IllegalStateException("SimpleChannel message requires encoder, decoder, and consumerMainThread");
            }
            NetworkBridge1201.registerMessage(channel, discriminator, messageType, direction, encoder, decoder, consumer);
        }
    }
}
