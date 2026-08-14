package com.moulberry.axiom.operations;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;

public interface PendingOperation {

    boolean isFinished();
    void tick(ServerLevel level);
    ServerPlayer executor();

    default Location nextTickLocation(ServerLevel level) {
        ServerPlayer executor = this.executor();
        if (executor == null || executor.hasDisconnected()) {
            return level.getWorld().getSpawnLocation();
        }
        return executor.getBukkitEntity().getLocation();
    }

}
