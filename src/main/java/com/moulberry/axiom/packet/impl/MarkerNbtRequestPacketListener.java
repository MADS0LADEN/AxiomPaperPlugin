package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.marker.MarkerData;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.util.ServerScheduler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

public class MarkerNbtRequestPacketListener implements PacketHandler {

    private final AxiomPaper plugin;
    public MarkerNbtRequestPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReceive(Player player, FriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.ENTITY_REQUESTDATA)) {
            return;
        }

        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            return;
        }

        UUID uuid = friendlyByteBuf.readUUID();
        friendlyByteBuf.readVarInt();

        org.bukkit.entity.Entity bukkitEntity = Bukkit.getEntity(uuid);
        if (bukkitEntity == null) return;

        ServerScheduler.executeNowOrForEntity(this.plugin, bukkitEntity, () -> {
            Entity entity = ((CraftEntity)bukkitEntity).getHandle();
            if (!(entity instanceof Marker marker)) return;

            CompoundTag data = MarkerData.getData(marker);

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUUID(uuid);
            buf.writeNbt(data);

            byte[] bytes = ByteBufUtil.getBytes(buf);
            ServerScheduler.executeNowOrForEntity(this.plugin, player, () ->
                VersionHelper.sendCustomPayload(player, "axiom:marker_nbt_response", bytes));
        });
    }

}
