package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.util.ServerScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OperationQueue {

    private final Lock queueLock = new ReentrantLock();
    private final Map<ServerLevel, List<PendingOperation>> pendingOperations = new HashMap<>();
    private final Set<ServerLevel> scheduledWorlds = ConcurrentHashMap.newKeySet();

    public void tick() {
        this.queueLock.lock();
        try {
            for (Map.Entry<ServerLevel, List<PendingOperation>> entry : this.pendingOperations.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    this.scheduleWorldLocked(entry.getKey(), entry.getValue().get(0));
                }
            }
        } finally {
            this.queueLock.unlock();
        }
    }

    public void add(ServerLevel level, PendingOperation operation) {
        boolean startNow = false;
        this.queueLock.lock();
        try {
            List<PendingOperation> operations = this.pendingOperations.computeIfAbsent(level, k -> new ArrayList<>());
            operations.add(operation);
            startNow = this.scheduledWorlds.add(level);
        } finally {
            this.queueLock.unlock();
        }

        if (startNow) {
            this.scheduleProcess(level, operation.nextTickLocation(level));
        }
    }

    private void scheduleWorldLocked(ServerLevel level, PendingOperation operation) {
        if (this.scheduledWorlds.add(level)) {
            this.scheduleProcess(level, operation.nextTickLocation(level));
        }
    }

    private void scheduleProcess(ServerLevel level, Location location) {
        ServerScheduler.executeNowOrAtLocation(AxiomPaper.PLUGIN, location, () -> this.process(level));
    }

    private void process(ServerLevel level) {
        PendingOperation operation;
        this.queueLock.lock();
        try {
            List<PendingOperation> operations = this.pendingOperations.get(level);
            if (operations == null || operations.isEmpty()) {
                this.scheduledWorlds.remove(level);
                this.pendingOperations.remove(level);
                return;
            }
            operation = operations.get(0);
        } finally {
            this.queueLock.unlock();
        }

        boolean finished = false;
        try {
            if (!operation.isFinished()) {
                operation.tick(level);
            }
            finished = operation.isFinished();
        } catch (Throwable t) {
            finished = true;
            ServerPlayer executor = operation.executor();
            if (executor != null && !executor.hasDisconnected()) {
                executor.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occurred while processing operation: " + t.getMessage()));
            }
        }

        Location nextLocation = null;
        this.queueLock.lock();
        try {
            List<PendingOperation> operations = this.pendingOperations.get(level);
            if (operations != null && !operations.isEmpty() && operations.get(0) == operation && finished) {
                operations.remove(0);
            }
            if (operations == null || operations.isEmpty()) {
                this.pendingOperations.remove(level);
                this.scheduledWorlds.remove(level);
                return;
            }
            nextLocation = operations.get(0).nextTickLocation(level);
        } finally {
            this.queueLock.unlock();
        }

        if (nextLocation != null) {
            ServerScheduler.runAtChunkDelayed(AxiomPaper.PLUGIN, nextLocation.getWorld(),
                nextLocation.getBlockX() >> 4, nextLocation.getBlockZ() >> 4,
                task -> this.process(level), 1);
        }
    }

}
