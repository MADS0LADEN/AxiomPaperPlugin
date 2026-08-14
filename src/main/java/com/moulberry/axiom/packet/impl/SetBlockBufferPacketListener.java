package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.BiomeBuffer;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.operations.SetBlockBufferOperation;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.util.ServerScheduler;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.*;

public class SetBlockBufferPacketListener implements PacketHandler {

    private final AxiomPaper plugin;

    public SetBlockBufferPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handleAsync() {
        return true;
    }

    public void onReceive(Player player, FriendlyByteBuf friendlyByteBuf) {
        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();

        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        friendlyByteBuf.readUUID(); // Discard, we don't need to associate buffers

        byte type = friendlyByteBuf.readByte();
        if (type == 0) {
            BlockBuffer buffer = BlockBuffer.load(friendlyByteBuf, this.plugin.getBlockRegistry(serverPlayer.getUUID()), serverPlayer.getBukkitEntity());
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBlockBuffer(serverPlayer, buffer, worldKey, clientAvailableDispatchSends);
        } else if (type == 1) {
            BiomeBuffer buffer = BiomeBuffer.load(friendlyByteBuf);
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBiomeBuffer(serverPlayer, buffer, worldKey, clientAvailableDispatchSends);
        } else {
            throw new RuntimeException("Unknown buffer type: " + type);
        }
    }

    private record BiomeEntry(int x, int y, int z, ResourceKey<Biome> biome) {}

    private void applyBlockBuffer(ServerPlayer player, BlockBuffer buffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        ServerScheduler.executeNowOrForEntity(this.plugin, player.getBukkitEntity(), () -> {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getSectionCount() + " chunk sections (blocks)");
                    if (buffer.getTotalBlockEntities() > 0) {
                        this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getTotalBlockEntities() + " block entities, compressed bytes = " +
                            buffer.getTotalBlockEntityBytes());
                    }
                }

                if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), buffer.getSectionCount(), clientAvailableDispatchSends)) {
                    return;
                }

                if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                    return;
                }

                ServerLevel world = player.level();
                if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                    return;
                }

                boolean allowNbt = this.plugin.hasPermission(player.getBukkitEntity(), AxiomPermission.BUILD_NBT);
                this.plugin.addPendingOperation(world, new SetBlockBufferOperation(player, buffer, allowNbt));
            } catch (Throwable t) {
                player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occured while processing block change: " + t.getMessage()));
            }
        });
    }

    private void applyBiomeBuffer(ServerPlayer player, BiomeBuffer biomeBuffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        ServerScheduler.executeNowOrForEntity(this.plugin, player.getBukkitEntity(), () -> {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + biomeBuffer.getSectionCount() + " chunk sections (biomes)");
                }

                if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), biomeBuffer.getSectionCount(), clientAvailableDispatchSends)) {
                    return;
                }

                if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                    return;
                }

                ServerLevel world = player.level();
                if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                    return;
                }

                Map<Long, List<BiomeEntry>> byChunk = new HashMap<>();
                biomeBuffer.forEachEntry((x, y, z, biome) -> byChunk.computeIfAbsent(ChunkPos.asLong(x >> 2, z >> 2),
                    key -> new ArrayList<>()).add(new BiomeEntry(x, y, z, biome)));

                org.bukkit.World bukkitWorld = world.getWorld();
                for (Map.Entry<Long, List<BiomeEntry>> chunkEntry : byChunk.entrySet()) {
                    int chunkX = ChunkPos.getX(chunkEntry.getKey());
                    int chunkZ = ChunkPos.getZ(chunkEntry.getKey());
                    List<BiomeEntry> entries = chunkEntry.getValue();

                    ServerScheduler.executeNowOrAtChunk(this.plugin, bukkitWorld, chunkX, chunkZ, () -> {
                        int minSection = world.getMinSectionY();
                        int maxSection = world.getMaxSectionY();

                        Optional<Registry<Biome>> registryOptional = world.registryAccess().lookup(Registries.BIOME);
                        if (registryOptional.isEmpty()) return;

                        Registry<Biome> registry = registryOptional.get();
                        LevelChunk chunk = (LevelChunk) world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                        if (chunk == null) return;

                        boolean changed = false;
                        for (BiomeEntry entry : entries) {
                            int cy = entry.y >> 2;
                            if (cy < minSection || cy > maxSection) {
                                continue;
                            }

                            var holder = registry.get(entry.biome);
                            if (holder.isEmpty()) continue;

                            if (!Integration.canPlaceBlock(player.getBukkitEntity(),
                                new Location(bukkitWorld, (entry.x << 2) + 1, (entry.y << 2) + 1, (entry.z << 2) + 1))) {
                                continue;
                            }

                            var section = chunk.getSection(cy - minSection);
                            PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) section.getBiomes();
                            container.set(entry.x & 3, entry.y & 3, entry.z & 3, holder.get());
                            changed = true;
                        }

                        if (!changed) return;

                        chunk.markUnsaved();
                        var chunkMap = world.getChunkSource().chunkMap;
                        ChunkPos chunkPos = chunk.getPos();
                        for (ServerPlayer serverPlayer2 : chunkMap.getPlayers(chunkPos, false)) {
                            serverPlayer2.connection.send(ClientboundChunksBiomesPacket.forChunks(List.of(chunk)));
                        }
                    });
                }
            } catch (Throwable t) {
                player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occured while processing biome change: " + t.getMessage()));
            }
        });
    }

}
