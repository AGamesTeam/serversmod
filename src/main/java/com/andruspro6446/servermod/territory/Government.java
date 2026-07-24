package com.andruspro6446.servermod.territory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Shared state for City and Country: a claimed region with a treasury, a founder who can delegate
// permissions to officials, weekly billing state, and the jails/reports machinery.
public abstract class Government
{
    public final UUID id;
    public String name;
    public UUID founderId;
    public int balanceCents;
    public ClaimRegion region;
    public final Billing billing;
    public final Map<UUID, Set<GovPermission>> officials = new LinkedHashMap<>();
    public final List<JailCell> jailCells = new ArrayList<>();
    // What share (0-100) of a report's fine goes to the reporter instead of this government's treasury.
    public int victimSharePercent = 50;
    // Percentage (0-100) of every business transaction taken as sales tax, for any business with a Sell
    // Barrel physically inside this government's territory - see business.SalesTax. Zero by default.
    public int salesTaxRatePercent = 0;
    // Extra percentage points of sales tax charged on top of salesTaxRatePercent for a business whose owner
    // isn't personally a resident here (doesn't hold a LandClaim overlapping this government's territory) -
    // see business.SalesTax and TerritoryData.residentGovernments. Zero by default (no distinction).
    public int foreignTaxSurchargePercent = 0;
    // If nonzero, a business must buy a license here (see business.Business.licensedGovernmentIds) before
    // customer NPCs will spawn for it while it has a Sell Barrel in this government's territory.
    public int licenseFeeCents = 0;
    // Unilateral diplomacy: this government's own declarations, not requiring the other side's agreement.
    // Sanctioning another government heavily surcharges (business.SalesTax) and raises theft/danger risk
    // (customer.CustomerNpcManager) for businesses resident there. Allying only actually waives double
    // taxation (SalesTax) or grants the diplomatic discount (CustomerNpcManager) once BOTH sides list each
    // other - checked live at the point of use, never stored as a separate "confirmed" flag.
    public final Set<UUID> sanctionedGovernmentIds = new HashSet<>();
    public final Set<UUID> alliedGovernmentIds = new HashSet<>();
    // Government-funded public plazas - see PublicSquare.
    public final List<PublicSquare> publicSquares = new ArrayList<>();

    protected Government(UUID id, String name, UUID founderId, ClaimRegion region, Billing billing)
    {
        this.id = id;
        this.name = name;
        this.founderId = founderId;
        this.region = region;
        this.billing = billing;
    }

    public boolean hasPermission(UUID playerId, GovPermission permission)
    {
        if (playerId.equals(founderId))
            return true;
        Set<GovPermission> granted = officials.get(playerId);
        return granted != null && granted.contains(permission);
    }

    public JailCell findJailCell(UUID cellId)
    {
        return jailCells.stream().filter(c -> c.id.equals(cellId)).findFirst().orElse(null);
    }

    public JailCell findJailCellByName(String name)
    {
        return jailCells.stream().filter(c -> c.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }
}
