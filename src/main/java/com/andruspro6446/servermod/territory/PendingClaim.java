package com.andruspro6446.servermod.territory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

// A claim a player has selected (two corners chosen, molded around other players' claims) but hasn't
// confirmed yet - waiting on their chat Accept/Decline click. Mirrors web.PendingLogin's role: short-lived,
// single-use, expires if never answered.
public class PendingClaim
{
    private static final long DURATION_MILLIS = 2 * 60 * 1000;

    public final String requestId;
    public final UUID playerId;
    public final ClaimType type;
    public final ResourceKey<Level> dimension;
    public final List<Rect> moldedPieces;
    public final String name;
    public final long expiresAtMillis;

    public PendingClaim(String requestId, UUID playerId, ClaimType type, ResourceKey<Level> dimension,
            List<Rect> moldedPieces, String name)
    {
        this.requestId = requestId;
        this.playerId = playerId;
        this.type = type;
        this.dimension = dimension;
        this.moldedPieces = moldedPieces;
        this.name = name;
        this.expiresAtMillis = System.currentTimeMillis() + DURATION_MILLIS;
    }

    public boolean isExpired()
    {
        return System.currentTimeMillis() > expiresAtMillis;
    }

    public long blockCount()
    {
        long total = 0;
        for (Rect r : moldedPieces)
            total += r.area();
        return total;
    }
}
