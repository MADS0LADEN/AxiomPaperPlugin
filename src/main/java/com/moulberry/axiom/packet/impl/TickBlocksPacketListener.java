package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.PositionSet;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.util.ServerScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class TickBlocksPacketListener implements PacketHandler {

    private final AxiomPaper plugin;
    public TickBlocksPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onReceive(Player bukkitPlayer, FriendlyByteBuf friendlyByteBuf) {
        var player = ((CraftPlayer)bukkitPlayer).getHandle();

        var level = player.level();
        if (level == null) {
            return;
        }

        var server = level.getServer();

        if (!this.plugin.canUseAxiom(bukkitPlayer, AxiomPermission.BUILD_DANGEROUS_TICK)) {
            return;
        }

        var world = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        PositionSet positionSet;
        BlockPos aabbMin;
        BlockPos aabbMax;

        byte type = friendlyByteBuf.readByte();
        if (type == 0) {
            positionSet = PositionSet.read(friendlyByteBuf);
            aabbMin = null;
            aabbMax = null;
        } else if (type == 1) {
            positionSet = null;
            aabbMin = friendlyByteBuf.readBlockPos();
            aabbMax = friendlyByteBuf.readBlockPos();
        } else {
            throw new RuntimeException("Unknown type: " + type);
        }

        if (level.dimension() != world) {
            return;
        }

        int count;
        if (positionSet != null) {
            count = positionSet.count();
        } else {
            int sizeX = Math.abs(aabbMax.getX() - aabbMin.getX()) + 1;
            int sizeY = Math.abs(aabbMax.getY() - aabbMin.getY()) + 1;
            int sizeZ = Math.abs(aabbMax.getZ() - aabbMin.getZ()) + 1;
            count = sizeX * sizeY * sizeZ;
        }
        boolean showMessage = count > 1048576;

        if (showMessage) {
            Component msg = Component.literal(player.getScoreboardName() + " updated & ticked " + count + " blocks using Axiom. The server may lag...");
            server.getPlayerList().broadcastSystemMessage(msg, false);

            long estimatedTime = Math.max(1, count / 2097152);
            msg = Component.literal("Estimated Time (varies depending on server hardware): " + estimatedTime + "s");
            server.getPlayerList().broadcastSystemMessage(msg, false);

            if (estimatedTime > 30) {
                msg = Component.literal("Estimated time is >30s, expect to be kicked from the server");
                server.getPlayerList().broadcastSystemMessage(msg, false);
            }
        }

        long start = System.currentTimeMillis();
        ServerLevel serverLevel = level;
        org.bukkit.World bukkitWorld = serverLevel.getWorld();

        AtomicInteger remainingChunks = new AtomicInteger(0);
        Runnable onComplete = () -> {
            if (showMessage) {
                long end = System.currentTimeMillis();
                long seconds = (end - start + 500) / 1000;
                Component msg = Component.literal("Done updating & ticking blocks (took " + seconds + "s)");
                server.getPlayerList().broadcastSystemMessage(msg, false);
            }
        };

        if (positionSet != null) {
            Map<Long, List<int[]>> byChunk = new HashMap<>();
            positionSet.forEach((x, y, z) -> byChunk.computeIfAbsent(ChunkPos.pack(x >> 4, z >> 4),
                key -> new ArrayList<>()).add(new int[]{x, y, z}));

            remainingChunks.set(byChunk.size());
            if (remainingChunks.get() == 0) {
                onComplete.run();
                return;
            }

            for (Map.Entry<Long, List<int[]>> chunkEntry : byChunk.entrySet()) {
                int chunkX = ChunkPos.getX(chunkEntry.getKey());
                int chunkZ = ChunkPos.getZ(chunkEntry.getKey());
                List<int[]> positions = chunkEntry.getValue();

                ServerScheduler.executeNowOrAtChunk(this.plugin, bukkitWorld, chunkX, chunkZ, () -> {
                    BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
                    for (int[] pos : positions) {
                        tickBlock(serverLevel, blockPos, pos[0], pos[1], pos[2]);
                    }

                    if (remainingChunks.decrementAndGet() == 0) {
                        onComplete.run();
                    }
                });
            }
        } else {
            int minX = Math.min(aabbMin.getX(), aabbMax.getX());
            int minY = Math.min(aabbMin.getY(), aabbMax.getY());
            int minZ = Math.min(aabbMin.getZ(), aabbMax.getZ());
            int maxX = Math.max(aabbMin.getX(), aabbMax.getX());
            int maxY = Math.max(aabbMin.getY(), aabbMax.getY());
            int maxZ = Math.max(aabbMin.getZ(), aabbMax.getZ());

            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;

            int chunkCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
            remainingChunks.set(chunkCount);
            if (chunkCount == 0) {
                onComplete.run();
                return;
            }

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    int finalChunkX = chunkX;
                    int finalChunkZ = chunkZ;

                    ServerScheduler.executeNowOrAtChunk(this.plugin, bukkitWorld, finalChunkX, finalChunkZ, () -> {
                        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
                        int chunkMinX = Math.max(minX, finalChunkX << 4);
                        int chunkMaxX = Math.min(maxX, (finalChunkX << 4) + 15);
                        int chunkMinZ = Math.max(minZ, finalChunkZ << 4);
                        int chunkMaxZ = Math.min(maxZ, (finalChunkZ << 4) + 15);

                        for (int x = chunkMinX; x <= chunkMaxX; x++) {
                            for (int y = minY; y <= maxY; y++) {
                                for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                                    tickBlock(serverLevel, blockPos, x, y, z);
                                }
                            }
                        }

                        if (remainingChunks.decrementAndGet() == 0) {
                            onComplete.run();
                        }
                    });
                }
            }
        }
    }

    private static void tickBlock(ServerLevel serverLevel, BlockPos.MutableBlockPos blockPos, int x, int y, int z) {
        blockPos.set(x, y, z);

        BlockState blockState = serverLevel.getBlockState(blockPos);
        if (blockState.isAir()) {
            return;
        }

        FluidState fluidState = blockState.getFluidState();
        if (!fluidState.isEmpty()) {
            fluidState.tick(serverLevel, blockPos, blockState);
        }

        if (blockState.getBlock() instanceof LiquidBlock) {
            blockState.tick(serverLevel, blockPos, serverLevel.getRandom());
        } else {
            BlockState blockStateNew = Block.updateFromNeighbourShapes(blockState, serverLevel, blockPos);
            if (blockStateNew != blockState) {
                serverLevel.setBlock(blockPos, blockStateNew, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
            }
        }
    }

}
