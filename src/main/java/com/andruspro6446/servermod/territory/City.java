package com.andruspro6446.servermod.territory;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

// A player-founded city. Charges land claims within its territory a per-block weekly rate (set by whoever
// has MANAGE_RATES); land-specific laws are layered on in a later phase.
public class City extends Government
{
    public int landClaimRatePerBlockCents;
    // Permission categories city officials with MANAGE_LAND_RULES automatically get on every land claim
    // within the city's territory, regardless of the individual owner's own rules/trust list. Disclosed to a
    // player claiming land here as part of the terms they agree to.
    public final Set<ClaimPermission> officialAccess = EnumSet.noneOf(ClaimPermission.class);

    public City(UUID id, String name, UUID founderId, ClaimRegion region, int landClaimRatePerBlockCents, Billing billing)
    {
        super(id, name, founderId, region, billing);
        this.landClaimRatePerBlockCents = landClaimRatePerBlockCents;
    }
}
