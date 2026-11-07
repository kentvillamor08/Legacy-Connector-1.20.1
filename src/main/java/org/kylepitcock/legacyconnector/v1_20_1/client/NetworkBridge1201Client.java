package org.kylepitcock.legacyconnector.v1_20_1.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.kylepitcock.legacyconnector.v1_20_1.bridge.NetworkBridge1201;

/** Physical-client-only PLAY sender installation. */
public final class NetworkBridge1201Client {
    private NetworkBridge1201Client() {}

    public static void register() {
        NetworkBridge1201.installClientTransport(payload -> {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null || !NetworkBridge1201.hasPlayCarrier(listener.getConnection())) return false;
            listener.send(payload);
            return true;
        });
    }
}
