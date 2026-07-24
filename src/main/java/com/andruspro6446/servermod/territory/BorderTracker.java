package com.andruspro6446.servermod.territory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Live, in-memory (not persisted) tracking of which country each online player is currently standing inside,
// and where they were last seen outside of any country - the fallback teleport target if they decline a
// border's terms. Mirrors ClaimRequests/PendingLogin: session-scoped state, not world data.
public final class BorderTracker
{
    public record Position(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {}

    private static final Map<UUID, UUID> CURRENT_COUNTRY = new ConcurrentHashMap<>();
    private static final Map<UUID, Position> LAST_OUTSIDE = new ConcurrentHashMap<>();
    // Players who've already been shown a border/re-agree prompt they haven't answered yet - stops the
    // once-a-second border tick from spamming chat with the same prompt every cycle.
    private static final Set<UUID> AWAITING_CONSENT = ConcurrentHashMap.newKeySet();

    private BorderTracker()
    {
    }

    public static UUID getCurrentCountry(UUID playerId)
    {
        return CURRENT_COUNTRY.get(playerId);
    }

    public static void setCurrentCountry(UUID playerId, UUID countryId)
    {
        if (countryId == null)
            CURRENT_COUNTRY.remove(playerId);
        else
            CURRENT_COUNTRY.put(playerId, countryId);
    }

    public static Position getLastOutside(UUID playerId)
    {
        return LAST_OUTSIDE.get(playerId);
    }

    public static void setLastOutside(UUID playerId, Position position)
    {
        LAST_OUTSIDE.put(playerId, position);
    }

    public static void clearPlayer(UUID playerId)
    {
        CURRENT_COUNTRY.remove(playerId);
        LAST_OUTSIDE.remove(playerId);
        AWAITING_CONSENT.remove(playerId);
    }

    public static boolean isCurrentlyInside(UUID playerId, UUID countryId)
    {
        return countryId.equals(CURRENT_COUNTRY.get(playerId));
    }

    public static boolean isAwaitingConsent(UUID playerId)
    {
        return AWAITING_CONSENT.contains(playerId);
    }

    public static void setAwaitingConsent(UUID playerId, boolean awaiting)
    {
        if (awaiting)
            AWAITING_CONSENT.add(playerId);
        else
            AWAITING_CONSENT.remove(playerId);
    }
}
