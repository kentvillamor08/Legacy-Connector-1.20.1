package org.kylepitcock.legacyconnector.v1_20_1.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import org.kylepitcock.legacyconnector.v1_20_1.bridge.NetworkBridge1201;

/** Physical-client-only PLAY sender installation. */
public final class NetworkBridge1201Client {
    private NetworkBridge1201Client() {}

    public static void register() {
        NetworkBridge1201.installClientTransport(new NetworkBridge1201.ClientTransport() {
            private ClientPacketListener listener() {
                return Minecraft.getInstance().getConnection();
            }

            @Override
            public boolean isReady() {
                ClientPacketListener listener = listener();
                return listener != null
                        && NetworkBridge1201.hasPlayCarrier(listener.getConnection());
            }

            @Override
            public RegistryAccess registryAccess() {
                if (!isReady()) {
                    throw new IllegalStateException("Client registry access is unavailable before the Legacy Connector PLAY connection is ready");
                }
                // ClientPacketListener owns the registry view negotiated during login; it is
                // available before a ClientLevel has finished loading.
                return listener().registryAccess();
            }

            @Override
            public boolean sendIfReady(org.kylepitcock.legacyconnector.core.network.LegacyPayload payload) {
                ClientPacketListener listener = listener();
                if (listener == null || !NetworkBridge1201.hasPlayCarrier(listener.getConnection())) return false;
                listener.send(payload);
                return true;
            }
        });
    }
}
