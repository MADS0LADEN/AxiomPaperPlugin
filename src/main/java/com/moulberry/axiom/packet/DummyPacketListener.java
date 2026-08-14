package com.moulberry.axiom.packet;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * Incoming axiom channels that are handled by {@link AxiomBigPayloadHandler}
 * still need to be registered with Bukkit so clients see them.
 */
public class DummyPacketListener implements PluginMessageListener {

    @Override
    public void onPluginMessageReceived(@NotNull String s, @NotNull Player player, @NotNull byte[] bytes) {
    }

}
