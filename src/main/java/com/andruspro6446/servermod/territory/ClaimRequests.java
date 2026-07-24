package com.andruspro6446.servermod.territory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Short-lived, in-memory holding pen for claims awaiting a chat Accept/Decline click - mirrors
// web.LoginManager's PENDING map. Not persisted; a pending claim just expires if the server restarts.
public final class ClaimRequests
{
    private static final Map<String, PendingClaim> PENDING = new ConcurrentHashMap<>();

    private ClaimRequests()
    {
    }

    public static String create(UUID playerId, ClaimType type, ResourceKey<Level> dim, List<Rect> moldedPieces, String name)
    {
        String requestId = UUID.randomUUID().toString();
        PENDING.put(requestId, new PendingClaim(requestId, playerId, type, dim, moldedPieces, name));
        return requestId;
    }

    // Looks up a pending claim without consuming it. Returns null (and forgets it) if unknown or expired.
    public static PendingClaim get(String requestId)
    {
        PendingClaim claim = PENDING.get(requestId);
        if (claim == null)
            return null;
        if (claim.isExpired())
        {
            PENDING.remove(requestId);
            return null;
        }
        return claim;
    }

    public static void remove(String requestId)
    {
        PENDING.remove(requestId);
    }
}
