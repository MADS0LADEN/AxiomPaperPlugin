package com.moulberry.axiom;

import com.moulberry.axiom.annotations.ServerAnnotations;
import com.moulberry.axiom.marker.MarkerData;
import com.moulberry.axiom.paperapi.entity.ImplAxiomHiddenEntities;
import com.moulberry.axiom.util.ServerScheduler;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WorldExtension {

    private static final Map<ResourceKey<Level>, WorldExtension> extensions = new ConcurrentHashMap<>();

    public static WorldExtension get(ServerLevel serverLevel) {
        WorldExtension extension = extensions.computeIfAbsent(serverLevel.dimension(), k -> new WorldExtension());
        extension.level = serverLevel;
        return extension;
    }

    public static void onPlayerJoin(World world, Player player) {
        ServerLevel level = ((CraftWorld)world).getHandle();
        get(level).onPlayerJoin(player);

        if (AxiomPaper.PLUGIN.canUseAxiom(player)) {
            ServerAnnotations.sendAll(world, ((CraftPlayer)player).getHandle());
        }
    }

    public static void tick(MinecraftServer server, boolean sendMarkers, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        extensions.keySet().retainAll(server.levelKeys());

        for (ServerLevel level : server.getAllLevels()) {
            get(level).tick(sendMarkers, maxChunkRelightsPerTick, maxChunkSendsPerTick);
        }
    }

    private volatile ServerLevel level;

    private final LongSet pendingChunksToSend = LongSets.synchronize(new LongOpenHashSet());
    private final LongSet pendingChunksToLight = LongSets.synchronize(new LongOpenHashSet());
    private final Map<UUID, MarkerData> previousMarkerData = new ConcurrentHashMap<>();

    public void sendChunk(int cx, int cz) {
        World world = this.level.getWorld();
        ServerScheduler.executeNowOrAtChunk(AxiomPaper.PLUGIN, world, cx, cz, () -> this.sendChunkNow(cx, cz));
    }

    public void lightChunk(int cx, int cz) {
        this.pendingChunksToLight.add(ChunkPos.pack(cx, cz));
        World world = this.level.getWorld();
        ServerScheduler.executeNowOrAtChunk(AxiomPaper.PLUGIN, world, cx, cz, () -> this.relightPending());
    }

    public static void handleMarkerAdded(org.bukkit.entity.Marker marker) {
        if (!AxiomPaper.PLUGIN.isSendMarkers()) {
            return;
        }
        ServerLevel level = ((CraftWorld) marker.getWorld()).getHandle();
        WorldExtension extension = get(level);
        marker.getScheduler().runAtFixedRate(AxiomPaper.PLUGIN, task -> extension.updateMarker(marker), () -> extension.removeMarker(marker.getUniqueId()), 1, 20);
    }

    public static void handleMarkerRemoved(org.bukkit.entity.Marker marker) {
        if (!AxiomPaper.PLUGIN.isSendMarkers()) {
            return;
        }
        ServerLevel level = ((CraftWorld) marker.getWorld()).getHandle();
        get(level).removeMarker(marker.getUniqueId());
    }

    public void onPlayerJoin(Player player) {
        if (!this.previousMarkerData.isEmpty()) {
            List<MarkerData> markerData = new ArrayList<>(this.previousMarkerData.values());

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeCollection(markerData, MarkerData::write);
            buf.writeCollection(Set.<UUID>of(), (buffer, uuid) -> buffer.writeUUID(uuid));

            byte[] bytes = ByteBufUtil.getBytes(buf);
            VersionHelper.sendCustomPayload(player, "axiom:marker_data", bytes);
        }

        try {
            ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
            if (this.level.chunkPacketBlockController.shouldModify(serverPlayer, this.level.getChunkIfLoaded(serverPlayer.blockPosition()))) {
                Component text = Component.text("Axiom: Warning, anti-xray is enabled. This will cause issues when copying blocks. Please turn anti-xray off");
                player.sendMessage(text.color(NamedTextColor.RED));
            }
        } catch (Throwable ignored) {}
    }

    public void tick(boolean sendMarkers, int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        this.tickChunkRelight(maxChunkRelightsPerTick, maxChunkSendsPerTick);
    }

    private void updateMarker(org.bukkit.entity.Marker bukkitMarker) {
        Entity entity = ((org.bukkit.craftbukkit.entity.CraftEntity) bukkitMarker).getHandle();
        if (!(entity instanceof Marker marker)) {
            return;
        }
        if (ImplAxiomHiddenEntities.isMarkerHidden(bukkitMarker)) {
            return;
        }

        MarkerData currentData = MarkerData.createFrom(marker);
        MarkerData previousData = this.previousMarkerData.get(marker.getUUID());
        if (Objects.equals(currentData, previousData)) {
            return;
        }
        this.previousMarkerData.put(marker.getUUID(), currentData);
        this.broadcastMarkerUpdate(List.of(currentData), Set.of());
    }

    private void removeMarker(UUID uuid) {
        if (this.previousMarkerData.remove(uuid) != null) {
            this.broadcastMarkerUpdate(List.of(), Set.of(uuid));
        }
    }

    private void broadcastMarkerUpdate(List<MarkerData> changedData, Set<UUID> missingUuids) {
        if (changedData.isEmpty() && missingUuids.isEmpty()) {
            return;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeCollection(changedData, MarkerData::write);
        buf.writeCollection(missingUuids, (buffer, uuid) -> buffer.writeUUID(uuid));
        byte[] bytes = ByteBufUtil.getBytes(buf);

        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : this.level.players()) {
            if (AxiomPaper.PLUGIN.canUseAxiom(player.getBukkitEntity())) {
                players.add(player);
            }
        }

        VersionHelper.sendCustomPayloadToAll(players, "axiom:marker_data", bytes);
    }

    private void sendChunkNow(int cx, int cz) {
        ChunkPos chunkPos = new ChunkPos(cx, cz);
        LevelChunk chunk = this.level.getChunkIfLoaded(cx, cz);
        if (chunk == null) {
            return;
        }

        ChunkMap chunkMap = this.level.getChunkSource().chunkMap;
        List<ServerPlayer> players = chunkMap.getPlayers(chunkPos, false);
        if (players.isEmpty()) {
            return;
        }

        var packet = new ClientboundLevelChunkWithLightPacket(chunk, this.level.getLightEngine(), null, null, false);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    private void relightPending() {
        this.tickChunkRelight(AxiomPaper.PLUGIN.getMaxChunkRelightsPerTick(), AxiomPaper.PLUGIN.getMaxChunkSendsPerTick());
    }

    private void tickChunkRelight(int maxChunkRelightsPerTick, int maxChunkSendsPerTick) {
        World world = this.level.getWorld();

        boolean sendAll = maxChunkSendsPerTick <= 0;

        LongArrayList toSend = new LongArrayList(this.pendingChunksToSend);
        for (long packed : toSend) {
            ChunkPos chunkPos = ChunkPos.unpack(packed);
            if (!ServerScheduler.isOwnedByCurrentRegion(world, chunkPos.x(), chunkPos.z())) {
                ServerScheduler.executeAtChunk(AxiomPaper.PLUGIN, world, chunkPos.x(), chunkPos.z(), this::relightPending);
                continue;
            }
            this.pendingChunksToSend.remove(packed);
            this.sendChunkNow(chunkPos.x(), chunkPos.z());
            if (!sendAll) {
                maxChunkSendsPerTick -= 1;
                if (maxChunkSendsPerTick <= 0) {
                    break;
                }
            }
        }

        Set<ChunkPos> chunkSet = new HashSet<>();
        LongArrayList toLight = new LongArrayList(this.pendingChunksToLight);
        if (maxChunkRelightsPerTick <= 0) {
            for (long packed : toLight) {
                ChunkPos chunkPos = ChunkPos.unpack(packed);
                if (!ServerScheduler.isOwnedByCurrentRegion(world, chunkPos.x(), chunkPos.z())) {
                    ServerScheduler.executeAtChunk(AxiomPaper.PLUGIN, world, chunkPos.x(), chunkPos.z(), this::relightPending);
                    continue;
                }
                this.pendingChunksToLight.remove(packed);
                chunkSet.add(chunkPos);
            }
        } else {
            for (long packed : toLight) {
                ChunkPos chunkPos = ChunkPos.unpack(packed);
                if (!ServerScheduler.isOwnedByCurrentRegion(world, chunkPos.x(), chunkPos.z())) {
                    ServerScheduler.executeAtChunk(AxiomPaper.PLUGIN, world, chunkPos.x(), chunkPos.z(), this::relightPending);
                    continue;
                }
                this.pendingChunksToLight.remove(packed);
                chunkSet.add(chunkPos);
                maxChunkRelightsPerTick -= 1;
                if (maxChunkRelightsPerTick <= 0) {
                    break;
                }
            }
        }

        if (!chunkSet.isEmpty()) {
            this.level.getChunkSource().getLightEngine().starlight$serverRelightChunks(chunkSet, pos -> {}, count -> {});
        }
    }

}
