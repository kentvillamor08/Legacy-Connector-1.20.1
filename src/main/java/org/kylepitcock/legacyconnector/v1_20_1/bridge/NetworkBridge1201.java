package org.kylepitcock.legacyconnector.v1_20_1.bridge;

import io.netty.buffer.ByteBuf;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import legacyconnector.v1_20_1.net.minecraftforge.network.NetworkDirection;
import legacyconnector.v1_20_1.net.minecraftforge.network.NetworkEvent;
import org.kylepitcock.legacyconnector.core.network.LegacyBuffers;
import org.kylepitcock.legacyconnector.core.network.LegacyNetwork;
import org.kylepitcock.legacyconnector.core.network.LegacyPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PLAY-only transport for Forge 1.20.1 SimpleChannel messages.
 * The separate NeoForge CONFIGURATION handshake will snapshot this channel registry later.
 */
public final class NetworkBridge1201 {
    public static final String FAMILY = "1.20.1";
    private static final Logger LOGGER = LoggerFactory.getLogger("LegacyConnector/Network");
    private static final Map<net.minecraft.resources.ResourceLocation, Channel> CHANNELS = new ConcurrentHashMap<>();
    private static volatile boolean registrationLocked;
    private static volatile ClientTransport clientTransport;

    private NetworkBridge1201() {}

    /** Install the legacy-family PLAY receiver before legacy common-setup callbacks run. */
    public static void bootstrap() {
        LegacyNetwork.registerReceiver(FAMILY, NetworkBridge1201::receive);
    }

    public static void installClientTransport(ClientTransport transport) {
        clientTransport = Objects.requireNonNull(transport, "transport");
    }

    public interface ClientTransport {
        /** Send only when the active connection negotiated LegacyPayload.TYPE in PLAY. */
        boolean sendIfReady(LegacyPayload payload);
    }

    public static Channel createChannel(
            net.minecraft.resources.ResourceLocation id,
            String protocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        if (registrationLocked) {
            throw new IllegalStateException("Forge 1.20.1 channels are locked after the first handshake snapshot");
        }
        Channel channel = new Channel(id, protocolVersion, clientAcceptedVersions, serverAcceptedVersions);
        Channel old = CHANNELS.putIfAbsent(id, channel);
        if (old != null) {
            throw new IllegalStateException("Duplicate Forge 1.20.1 channel: " + id);
        }
        return channel;
    }

    public static void lockChannelRegistration() {
        registrationLocked = true;
    }

    public static Map<net.minecraft.resources.ResourceLocation, String> channelVersionsSnapshot() {
        Map<net.minecraft.resources.ResourceLocation, String> result = new java.util.HashMap<>();
        CHANNELS.forEach((id, channel) -> result.put(id, channel.protocolVersion));
        return Map.copyOf(result);
    }

    public static boolean hasPlayCarrier(Connection connection) {
        if (connection == null) return false;
        NetworkPayloadSetup setup = ChannelAttributes.getPayloadSetup(connection);
        return setup != null && setup.getChannels(ConnectionProtocol.PLAY).containsKey(LegacyPayload.TYPE.id());
    }

    public static void sendToServer(LegacyPayload payload) {
        ClientTransport transport = clientTransport;
        if (transport == null) throw new IllegalStateException("Client PLAY transport has not been installed");
        if (!transport.sendIfReady(payload)) {
            LOGGER.debug("Skipping Forge 1.20.1 send: server did not negotiate the Legacy Connector PLAY carrier");
        }
    }

    public static void sendToPlayerIfCarrierPresent(ServerPlayer player, Packet<?> packet) {
        if (!hasPlayCarrier(player.connection.getConnection())) {
            LOGGER.debug("Skipping Forge 1.20.1 payload for {}: Legacy Connector PLAY carrier was not negotiated",
                    player.getGameProfile().getName());
            return;
        }
        player.connection.send(packet);
    }

    public static void sendToTrackingPlayersIfCarrierPresent(Entity entity, Packet<?> packet, boolean includeSelf) {
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("TRACKING_ENTITY_AND_SELF requires a server-side entity");
        }
        Set<ServerPlayer> recipients = new LinkedHashSet<>(level.getChunkSource().chunkMap.getPlayersWatching(entity));
        if (includeSelf && entity instanceof ServerPlayer self) recipients.add(self);
        for (ServerPlayer player : recipients) sendToPlayerIfCarrierPresent(player, packet);
    }

    public static CustomPacketPayload clientboundPayload(LegacyPayload payload) {
        return new ClientboundCustomPayloadPacket(payload).payload();
    }

    public static void registerMessage(
            Channel channel,
            int discriminator,
            Class<?> messageType,
            NetworkDirection direction,
            BiConsumer<?, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, ?> decoder,
            BiConsumer<?, Supplier<NetworkEvent.Context>> consumer) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(direction, "direction");
        if (registrationLocked) throw new IllegalStateException("Forge 1.20.1 messages are locked after the first handshake snapshot");
        int wireId = discriminator & 0xFF;
        MessageDefinition definition = new MessageDefinition(messageType, wireId, direction, encoder, decoder, consumer);
        synchronized (channel) {
            channel.byDiscriminator.put(wireId, definition);
            channel.byClass.put(messageType, definition);
        }
    }

    public static MessageDefinition requireMessage(Channel channel, Object message) {
        Objects.requireNonNull(message, "message");
        MessageDefinition definition = channel.byClass.get(message.getClass());
        if (definition == null) {
            throw new IllegalArgumentException("Unregistered message type " + message.getClass().getName()
                    + " on channel " + channel.id);
        }
        return definition;
    }

    public static LegacyPayload encode(Channel channel, Object message) {
        MessageDefinition definition = requireMessage(channel, message);
        ByteBuf raw = LegacyBuffers.buffer();
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(raw);
            definition.encode(message, buffer);
            byte[] data = new byte[raw.readableBytes()];
            raw.getBytes(raw.readerIndex(), data);
            return new LegacyPayload(FAMILY, channel.id.toString(), definition.discriminator, data);
        } finally {
            raw.release();
        }
    }

    static boolean hasRegisteredMessage(net.minecraft.resources.ResourceLocation channelId, int discriminator) {
        Channel channel = CHANNELS.get(channelId);
        return channel != null && channel.byDiscriminator.containsKey(discriminator & 0xFF);
    }

    private static void receive(
            LegacyPayload payload,
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        net.minecraft.resources.ResourceLocation channelId =
                net.minecraft.resources.ResourceLocation.tryParse(payload.channel());
        Channel channel = channelId == null ? null : CHANNELS.get(channelId);
        if (channel == null) {
            LOGGER.debug("Dropping message for unregistered Forge 1.20.1 channel {}", payload.channel());
            return;
        }
        boolean serverbound = context.flow() == PacketFlow.SERVERBOUND;
        NetworkDirection expected = serverbound ? NetworkDirection.PLAY_TO_SERVER : NetworkDirection.PLAY_TO_CLIENT;
        MessageDefinition definition = channel.byDiscriminator.get(payload.discriminator() & 0xFF);
        if (definition == null || definition.direction != expected) {
            LOGGER.debug("Dropping unknown or wrong-direction message {} on channel {}",
                    payload.discriminator(), payload.channel());
            return;
        }

        ByteBuf raw = LegacyBuffers.wrap(payload.data());
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(raw);
            Object decoded = definition.decode(buffer);
            if (!definition.messageType.isInstance(decoded)) {
                throw new IllegalArgumentException("Decoder returned an unexpected message type");
            }
            NetworkEvent.Context messageContext = new NetworkEvent.Context(context, expected);
            definition.accept(decoded, () -> messageContext);
        } catch (Exception exception) {
            LOGGER.error("Failed to decode or handle Forge 1.20.1 message {} on channel {}",
                    payload.discriminator(), payload.channel(), exception);
        } finally {
            raw.release();
        }
    }

    public static final class Channel {
        private final net.minecraft.resources.ResourceLocation id;
        private final String protocolVersion;
        private final Predicate<String> clientAcceptedVersions;
        private final Predicate<String> serverAcceptedVersions;
        private final Map<Integer, MessageDefinition> byDiscriminator = new ConcurrentHashMap<>();
        private final Map<Class<?>, MessageDefinition> byClass = new ConcurrentHashMap<>();

        private Channel(
                net.minecraft.resources.ResourceLocation id,
                String protocolVersion,
                Predicate<String> clientAcceptedVersions,
                Predicate<String> serverAcceptedVersions) {
            this.id = Objects.requireNonNull(id, "id");
            this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
            this.clientAcceptedVersions = Objects.requireNonNull(clientAcceptedVersions, "clientAcceptedVersions");
            this.serverAcceptedVersions = Objects.requireNonNull(serverAcceptedVersions, "serverAcceptedVersions");
        }

        public net.minecraft.resources.ResourceLocation id() {
            return id;
        }

        public String protocolVersion() {
            return protocolVersion;
        }

        public boolean clientAccepts(String serverVersion) {
            return clientAcceptedVersions.test(serverVersion);
        }

        public boolean serverAccepts(String clientVersion) {
            return serverAcceptedVersions.test(clientVersion);
        }
    }

    public record MessageDefinition(
            Class<?> messageType,
            int discriminator,
            NetworkDirection direction,
            BiConsumer<?, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, ?> decoder,
            BiConsumer<?, Supplier<NetworkEvent.Context>> consumer) {
        @SuppressWarnings("unchecked")
        private void encode(Object message, FriendlyByteBuf buffer) {
            ((BiConsumer<Object, FriendlyByteBuf>) encoder).accept(message, buffer);
        }

        private Object decode(FriendlyByteBuf buffer) {
            return decoder.apply(buffer);
        }

        @SuppressWarnings("unchecked")
        private void accept(Object message, Supplier<NetworkEvent.Context> context) {
            ((BiConsumer<Object, Supplier<NetworkEvent.Context>>) consumer).accept(message, context);
        }
    }
}
