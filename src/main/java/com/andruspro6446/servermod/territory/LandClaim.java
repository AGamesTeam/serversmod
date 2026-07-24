package com.andruspro6446.servermod.territory;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// A player's claimed land. Which cities it owes weekly rent to isn't stored here - it's whichever cities'
// regions currently overlap this claim's region, computed fresh at billing time so a city growing, shrinking,
// or dissolving is reflected automatically instead of going stale.
public class LandClaim
{
    public final UUID id;
    public UUID ownerId;
    public ClaimRegion region;
    public final Billing billing;
    public final Map<ClaimPermission, PermissionMode> rules;
    // Players individually granted access beyond the public rules above, e.g. letting a friend build even
    // though BUILD is otherwise BLOCKED for everyone else.
    public final Map<UUID, Set<ClaimPermission>> trusted = new LinkedHashMap<>();

    public LandClaim(UUID id, UUID ownerId, ClaimRegion region, Billing billing)
    {
        this(id, ownerId, region, billing, defaultRules());
    }

    public LandClaim(UUID id, UUID ownerId, ClaimRegion region, Billing billing, Map<ClaimPermission, PermissionMode> rules)
    {
        this.id = id;
        this.ownerId = ownerId;
        this.region = region;
        this.billing = billing;
        this.rules = rules;
    }

    // Protects the obvious griefing/safety vectors by default; leaves general interaction and hurting mobs
    // open, since those are far less commonly abused and locking them down by default would surprise owners.
    public static Map<ClaimPermission, PermissionMode> defaultRules()
    {
        Map<ClaimPermission, PermissionMode> rules = new EnumMap<>(ClaimPermission.class);
        rules.put(ClaimPermission.BUILD, PermissionMode.BLOCKED);
        rules.put(ClaimPermission.CONTAINER_OPEN, PermissionMode.BLOCKED);
        rules.put(ClaimPermission.CONTAINER_TAKE, PermissionMode.BLOCKED);
        rules.put(ClaimPermission.PVP, PermissionMode.BLOCKED);
        rules.put(ClaimPermission.INTERACT, PermissionMode.ALLOWED);
        rules.put(ClaimPermission.MOB_DAMAGE, PermissionMode.ALLOWED);
        return rules;
    }
}
