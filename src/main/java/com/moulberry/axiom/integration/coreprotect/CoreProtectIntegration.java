package com.moulberry.axiom.integration.coreprotect;

import com.moulberry.axiom.AxiomPaper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;

public class CoreProtectIntegration {
    public static boolean isEnabled() {
        if (!isCoreProtectPluginPresent()) {
            return false;
        }

        try {
            return CoreProtectIntegrationImpl.isEnabled() && AxiomPaper.PLUGIN.logCoreProtectChanges;
        } catch (NoClassDefFoundError ignored) {
            // CoreProtect is optional (softdepend/compileOnly). Hard CoreProtect types in
            // CoreProtectIntegrationImpl can fail class loading when the plugin is absent.
            return false;
        }
    }

    public static void logPlacement(String name, BlockState blockState, CraftWorld world, BlockPos pos) {
        if (!isEnabled()) {
            return;
        }

        try {
            CoreProtectIntegrationImpl.logPlacement(name, blockState, world, pos);
        } catch (NoClassDefFoundError ignored) {
        }
    }

    public static void logRemoval(String name, BlockState blockState, CraftWorld world, BlockPos pos) {
        if (!isEnabled()) {
            return;
        }

        try {
            CoreProtectIntegrationImpl.logRemoval(name, blockState, world, pos);
        } catch (NoClassDefFoundError ignored) {
        }
    }

    private static boolean isCoreProtectPluginPresent() {
        return Bukkit.getPluginManager().isPluginEnabled("CoreProtect");
    }

}
