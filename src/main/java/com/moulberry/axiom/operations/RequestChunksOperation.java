package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.packet.impl.RequestChunkDataPacketListener;
import com.moulberry.axiom.util.ServerScheduler;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestChunksOperation implements PendingOperation {

    private static final int MAX_CHUNK_FUTURES = 256;
    private volatile boolean finished = false;

    private final ServerPlayer serverPlayer;
    private final long id;

    private final LongArrayList getChunkFutures;
    private final Long2ObjectMap<LongList> sendBlockEntityForPendingChunks;
    private final Long2ObjectMap<IntList> sendSectionsForPendingChunks;
    private final boolean sendBlockEntitiesInChunks;

    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections;
    private final Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities;
    private final ByteArrayOutputStream baos;
    private final Object resultLock = new Object();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile long nextChunkHint = Long.MIN_VALUE;

    public RequestChunksOperation(ServerPlayer serverPlayer, long id, LongSet chunkFutures, Long2ObjectMap<LongList> sendBlockEntityForPendingChunks, Long2ObjectMap<IntList> sendSectionsForPendingChunks, boolean sendBlockEntitiesInChunks, Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections, Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities, ByteArrayOutputStream baos) {
        this.serverPlayer = serverPlayer;
        this.id = id;
        this.sendBlockEntityForPendingChunks = sendBlockEntityForPendingChunks;
        this.sendSectionsForPendingChunks = sendSectionsForPendingChunks;
        this.sendBlockEntitiesInChunks = sendBlockEntitiesInChunks;
        this.sendingSections = sendingSections;
        this.sendingBlockEntities = sendingBlockEntities;
        this.baos = baos;

        LongArrayList getChunkFutures = new LongArrayList(chunkFutures);
        getChunkFutures.unstableSort(LongComparators.NATURAL_COMPARATOR);
        this.getChunkFutures = getChunkFutures;
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public ServerPlayer executor() {
        return this.serverPlayer;
    }

    @Override
    public Location nextTickLocation(ServerLevel level) {
        long hint = this.nextChunkHint;
        if (hint != Long.MIN_VALUE) {
            return new Location(level.getWorld(), ChunkPos.getX(hint) << 4, 0, ChunkPos.getZ(hint) << 4);
        }
        return PendingOperation.super.nextTickLocation(level);
    }

    @Override
    public synchronized void tick(ServerLevel level) {
        if (this.finished) {
            return;
        }

        if (this.serverPlayer.hasDisconnected()) {
            this.finished = true;
            return;
        }

        World world = level.getWorld();
        this.nextChunkHint = Long.MIN_VALUE;

        while (this.inFlight.get() < MAX_CHUNK_FUTURES && !this.getChunkFutures.isEmpty()) {
            long chunkPos = this.getChunkFutures.removeLong(0);
            int x = ChunkPos.getX(chunkPos);
            int z = ChunkPos.getZ(chunkPos);

            if (this.nextChunkHint == Long.MIN_VALUE && !ServerScheduler.isOwnedByCurrentRegion(world, x, z)) {
                this.nextChunkHint = chunkPos;
            }

            this.inFlight.incrementAndGet();
            ServerScheduler.executeNowOrAtChunk(AxiomPaper.PLUGIN, world, x, z, () -> {
                try {
                    this.readChunk(level, x, z);
                } finally {
                    this.inFlight.decrementAndGet();
                    this.maybeFinish();
                }
            });
        }

        this.maybeFinish();
    }

    private synchronized void maybeFinish() {
        if (this.finished) {
            return;
        }
        if (!this.getChunkFutures.isEmpty() || this.inFlight.get() != 0) {
            return;
        }

        RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, this.sendingBlockEntities, this.sendingSections);
        this.finished = true;
    }

    private void readChunk(ServerLevel level, int chunkX, int chunkZ) {
        ByteArrayOutputStream localBaos = new ByteArrayOutputStream();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        LevelChunk chunk = (LevelChunk) ((CraftChunk) level.getWorld().getChunkAt(chunkX, chunkZ)).getHandle(ChunkStatus.FULL);
        long chunkPosLong = ChunkPos.pack(chunk.locX, chunk.locZ);
        LongList blockEntitiesInChunk = this.sendBlockEntityForPendingChunks.get(chunkPosLong);
        if (blockEntitiesInChunk != null) {
            LongIterator iterator = blockEntitiesInChunk.longIterator();
            while (iterator.hasNext()) {
                long blockEntityPos = iterator.nextLong();
                mutableBlockPos.set(blockEntityPos);

                BlockEntity blockEntity = chunk.getBlockEntity(mutableBlockPos, LevelChunk.EntityCreationType.CHECK);
                if (blockEntity != null) {
                    CompoundTag tag = blockEntity.saveWithoutMetadata(this.serverPlayer.registryAccess());
                    CompressedBlockEntity compressed = CompressedBlockEntity.compress(tag, localBaos);
                    synchronized (this.resultLock) {
                        this.sendingBlockEntities.put(blockEntityPos, compressed);
                    }
                }
            }
        }

        IntList sendSectionsInChunk = this.sendSectionsForPendingChunks.get(chunkPosLong);
        if (sendSectionsInChunk != null) {
            boolean hasNonAirSectionInChunk = false;

            IntIterator sectionIterator = sendSectionsInChunk.intIterator();
            while (sectionIterator.hasNext()) {
                int sy = sectionIterator.nextInt();

                int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) continue;
                LevelChunkSection section = chunk.getSection(sectionIndex);

                PalettedContainer<BlockState> container;
                if (section.hasOnlyAir()) {
                    container = null;
                } else {
                    container = section.getStates();
                    hasNonAirSectionInChunk = true;
                }
                synchronized (this.resultLock) {
                    this.sendingSections.put(BlockPos.asLong(chunk.locX, sy, chunk.locZ), container);
                }
            }

            if (this.sendBlockEntitiesInChunks && hasNonAirSectionInChunk) {
                Set<Map.Entry<BlockPos, BlockEntity>> entrySet = chunk.blockEntities.entrySet();
                Iterator<Map.Entry<BlockPos, BlockEntity>> iterator;
                if (entrySet instanceof Object2ObjectMap.FastEntrySet fastEntrySet) {
                    iterator = fastEntrySet.fastIterator();
                } else {
                    iterator = entrySet.iterator();
                }

                while (iterator.hasNext()) {
                    Map.Entry<BlockPos, BlockEntity> entry = iterator.next();

                    BlockPos blockPos = entry.getKey();
                    int sectionY = blockPos.getY() >> 4;
                    if (!sendSectionsInChunk.contains(sectionY)) {
                        continue;
                    }

                    CompoundTag tag = entry.getValue().saveWithoutMetadata(this.serverPlayer.registryAccess());
                    CompressedBlockEntity compressed = CompressedBlockEntity.compress(tag, localBaos);
                    synchronized (this.resultLock) {
                        this.sendingBlockEntities.put(blockPos.asLong(), compressed);
                    }
                }
            }
        }
    }

}
