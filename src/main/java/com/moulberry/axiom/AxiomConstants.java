package com.moulberry.axiom;

import net.minecraft.core.BlockPos;
import org.bukkit.NamespacedKey;

public class AxiomConstants {

    public static final long MIN_POSITION_LONG = BlockPos.asLong(-33554432, -2048, -33554432);
    static {
        if (MIN_POSITION_LONG != 0b1000000000000000000000000010000000000000000000000000100000000000L) {
            throw new Error("BlockPos representation changed!");
        }
    }

    // Latest public Axiom client (5.5.0) still speaks API 9.
    // API 10 is the protocol-rework format used by 6.x; accept both.
    public static final int MIN_API_VERSION = 9;
    public static final int API_VERSION = 10;

    public static final byte TUNNEL_PACKET_FLAG_ZSTD_COMPRESSED = 1;

    public static final byte TUNNEL_BUFFER_FLAG_FIRST = 1;
    public static final byte TUNNEL_BUFFER_FLAG_LAST = 2;

}
