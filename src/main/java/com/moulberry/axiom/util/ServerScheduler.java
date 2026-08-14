package com.moulberry.axiom.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Paper/Folia/CanvasMC compatible scheduling helpers.
 * <p>
 * These APIs exist on Paper as well as Folia (and Folia forks such as CanvasMC).
 * On Paper they run on the main thread; on Folia they run on the owning region.
 */
public final class ServerScheduler {

    private ServerScheduler() {
    }

    public static ScheduledTask runGlobalAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, initialDelayTicks, periodTicks);
    }

    public static void executeGlobal(Plugin plugin, Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }

    public static void executeNowOrGlobal(Plugin plugin, Runnable runnable) {
        if (Bukkit.isGlobalTickThread()) {
            runnable.run();
        } else {
            executeGlobal(plugin, runnable);
        }
    }

    public static void executeAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, runnable);
    }

    public static void executeNowOrAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable runnable) {
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            runnable.run();
        } else {
            executeAtChunk(plugin, world, chunkX, chunkZ, runnable);
        }
    }

    public static void executeAtLocation(Plugin plugin, Location location, Runnable runnable) {
        Bukkit.getRegionScheduler().execute(plugin, location, runnable);
    }

    public static void executeNowOrAtLocation(Plugin plugin, Location location, Runnable runnable) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            runnable.run();
        } else {
            executeAtLocation(plugin, location, runnable);
        }
    }

    /**
     * @return {@code false} if the entity was retired before the task could be scheduled
     */
    public static boolean executeForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        return entity.getScheduler().execute(plugin, runnable, null, 1);
    }

    public static void executeNowOrForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            runnable.run();
        } else {
            executeForEntity(plugin, entity, runnable);
        }
    }

    public static @Nullable ScheduledTask runAtChunkDelayed(Plugin plugin, World world, int chunkX, int chunkZ, Consumer<ScheduledTask> task, long delayTicks) {
        return Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task, delayTicks);
    }

    public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean isOwnedByCurrentRegion(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    public static boolean isOwnedByCurrentRegion(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    public static boolean isGlobalTickThread() {
        return Bukkit.isGlobalTickThread();
    }

}
