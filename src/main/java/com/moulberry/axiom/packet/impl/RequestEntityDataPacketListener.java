package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.util.ServerScheduler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestEntityDataPacketListener implements PacketHandler {

    public static final Identifier RESPONSE_ID = VersionHelper.createIdentifier("axiom:response_entity_data");

    private final AxiomPaper plugin;
    public RequestEntityDataPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReceive(org.bukkit.entity.Player bukkitPlayer, FriendlyByteBuf friendlyByteBuf) {
        ServerPlayer player = ((CraftPlayer)bukkitPlayer).getHandle();
        long id = friendlyByteBuf.readLong();

        if (!this.plugin.canUseAxiom(bukkitPlayer, AxiomPermission.ENTITY_REQUESTDATA) || this.plugin.isMismatchedDataVersion(bukkitPlayer.getUniqueId())) {
            sendResponse(player, id, true, Map.of());
            return;
        }

        if (!this.plugin.canModifyWorld(bukkitPlayer, bukkitPlayer.getWorld())) {
            sendResponse(player, id, true, Map.of());
            return;
        }

        List<UUID> request = friendlyByteBuf.readCollection(this.plugin.limitCollection(ArrayList::new), buf -> buf.readUUID());

        final int maxPacketSize = 0x100000;

        Set<UUID> visitedEntities = new HashSet<>();
        List<org.bukkit.entity.Entity> entitiesToQuery = new ArrayList<>();

        for (UUID uuid : request) {
            if (!visitedEntities.add(uuid)) {
                continue;
            }

            org.bukkit.entity.Entity bukkitEntity = Bukkit.getEntity(uuid);
            if (bukkitEntity == null || bukkitEntity instanceof org.bukkit.entity.Player) {
                continue;
            }

            entitiesToQuery.add(bukkitEntity);
        }

        if (entitiesToQuery.isEmpty()) {
            sendResponse(player, id, true, Map.of());
            return;
        }

        ConcurrentHashMap<UUID, CompoundTag> entityData = new ConcurrentHashMap<>();
        AtomicInteger remaining = new AtomicInteger(entitiesToQuery.size());

        for (org.bukkit.entity.Entity bukkitEntity : entitiesToQuery) {
            ServerScheduler.executeNowOrForEntity(this.plugin, bukkitEntity, () -> {
                try {
                    Entity entity = ((CraftEntity)bukkitEntity).getHandle();
                    if (entity instanceof Player) {
                        return;
                    }

                    if (!this.plugin.canEntityBeManipulated(entity.getType())) {
                        return;
                    }

                    if (!Integration.canPlaceBlock(bukkitPlayer, new Location(bukkitEntity.getWorld(),
                            entity.getBlockX(), entity.getBlockY(), entity.getBlockZ()))) {
                        return;
                    }

                    UUID uuid = entity.getUUID();
                    var valueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
                    var entityTag = entity.save(valueOutput) ? valueOutput.buildResult() : null;
                    if (entityTag != null) {
                        int size = entityTag.sizeInBytes();
                        if (size >= maxPacketSize) {
                            ServerScheduler.executeNowOrForEntity(this.plugin, bukkitPlayer, () ->
                                sendResponse(player, id, false, Map.of(uuid, entityTag)));
                        } else {
                            entityData.put(uuid, entityTag);
                        }
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        ServerScheduler.executeNowOrForEntity(this.plugin, bukkitPlayer, () ->
                            sendBatchedResponse(player, id, entityData, maxPacketSize));
                    }
                }
            });
        }
    }

    private static void sendBatchedResponse(ServerPlayer player, long id, Map<UUID, CompoundTag> collected, int maxPacketSize) {
        int remainingBytes = maxPacketSize;
        Map<UUID, CompoundTag> batch = new HashMap<>();

        for (Map.Entry<UUID, CompoundTag> entry : collected.entrySet()) {
            CompoundTag entityTag = entry.getValue();
            int size = entityTag.sizeInBytes();

            if (remainingBytes - size < 0 && !batch.isEmpty()) {
                sendResponse(player, id, false, batch);
                batch.clear();
                remainingBytes = maxPacketSize;
            }

            batch.put(entry.getKey(), entityTag);
            remainingBytes -= size;
        }

        sendResponse(player, id, true, batch);
    }

    private static void sendResponse(ServerPlayer player, long id, boolean finished, Map<UUID, CompoundTag> map) {
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(Unpooled.buffer());
        friendlyByteBuf.writeLong(id);
        friendlyByteBuf.writeBoolean(finished);
        friendlyByteBuf.writeMap(map, (buf, uuid) -> buf.writeUUID(uuid), (buf, nbt) -> buf.writeNbt(nbt));

        byte[] bytes = ByteBufUtil.getBytes(friendlyByteBuf);
        VersionHelper.sendCustomPayload(player, RESPONSE_ID, bytes);
    }

}
