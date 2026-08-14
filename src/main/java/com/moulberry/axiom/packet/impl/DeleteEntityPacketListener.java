package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.event.AxiomRemoveEntityEvent;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.util.ServerScheduler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DeleteEntityPacketListener implements PacketHandler {

    private final AxiomPaper plugin;
    public DeleteEntityPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReceive(Player player, FriendlyByteBuf friendlyByteBuf) {
        if (!this.plugin.canUseAxiom(player, AxiomPermission.ENTITY_DELETE)) {
            return;
        }

        if (!this.plugin.canModifyWorld(player, player.getWorld())) {
            return;
        }

        List<UUID> delete = friendlyByteBuf.readCollection(this.plugin.limitCollection(ArrayList::new), buf -> buf.readUUID());

        for (UUID uuid : delete) {
            org.bukkit.entity.Entity bukkitEntity = Bukkit.getEntity(uuid);
            if (bukkitEntity == null) continue;

            ServerScheduler.executeNowOrForEntity(this.plugin, bukkitEntity, () -> {
                Entity entity = ((CraftEntity)bukkitEntity).getHandle();
                if (entity instanceof net.minecraft.world.entity.player.Player || entity.hasPassenger(e -> e instanceof net.minecraft.world.entity.player.Player)) {
                    return;
                }

                if (!this.plugin.canEntityBeManipulated(entity.getType())) {
                    return;
                }

                if (!Integration.canBreakBlock(player,
                        player.getWorld().getBlockAt(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()))) {
                    return;
                }

                AxiomRemoveEntityEvent removeEntityEvent = new AxiomRemoveEntityEvent(player, bukkitEntity);
                Bukkit.getPluginManager().callEvent(removeEntityEvent);

                if (!removeEntityEvent.isCancelled()) {
                    entity.remove(Entity.RemovalReason.DISCARDED);
                }
            });
        }
    }

}
