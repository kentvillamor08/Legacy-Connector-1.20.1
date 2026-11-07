package legacyconnector.v1_20_1.net.minecraftforge.network;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import legacyconnector.v1_20_1.net.minecraftforge.network.simple.SimpleChannel;
import org.kylepitcock.legacyconnector.v1_20_1.bridge.NetworkBridge1201;

/** The Forge 1.20.1 SimpleChannel registration surface required by the Symbiote pilot. */
public final class NetworkRegistry {
    /** Exact per-channel marker used by Forge 47.4.28 when a peer omits a channel. */
    public static final String ABSENT_VERSION = "ABSENT ?";
    /** Separate vanilla-acceptance marker; it is not the missing-channel map value. */
    public static final String ALLOW_VANILLA_VERSION = "ALLOWVANILLA ???";

    public NetworkRegistry() {}

    public static Predicate<String> acceptMissingOr(String version) {
        Objects.requireNonNull(version, "version");
        return remote -> ABSENT_VERSION.equals(remote) || version.equals(remote);
    }

    public static Predicate<String> acceptMissingOr(Predicate<String> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return remote -> ABSENT_VERSION.equals(remote) || predicate.test(remote);
    }

    public static SimpleChannel newSimpleChannel(
            ResourceLocation channelName,
            Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        Objects.requireNonNull(networkProtocolVersion, "networkProtocolVersion");
        // Forge 47.4.28 evaluates this supplier once when its NetworkInstance is constructed.
        String version = Objects.requireNonNull(networkProtocolVersion.get(), "network protocol version");
        return new SimpleChannel(NetworkBridge1201.createChannel(
                Objects.requireNonNull(channelName, "channelName"),
                version,
                Objects.requireNonNull(clientAcceptedVersions, "clientAcceptedVersions"),
                Objects.requireNonNull(serverAcceptedVersions, "serverAcceptedVersions")));
    }
}
