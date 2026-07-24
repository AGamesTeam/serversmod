package com.andruspro6446.servermod.territory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// A player-founded country. Charges cities within its territory a per-block weekly rate (set by whoever has
// MANAGE_RATES), enforces its own laws up to a configurable Y level (with override zones that ignore the
// cap, e.g. around a tower), and requires agreement to those laws at the border.
public class Country extends Government
{
    public int cityRatePerBlockCents;

    public int lawMaxY = 320;
    // X/Z zones (in the country's own dimension) where laws apply regardless of lawMaxY - e.g. a column
    // around a watchtower someone building above the general cap still needs to answer to.
    public final List<Rect> lawYOverrideZones = new ArrayList<>();
    public final Map<CountryLaw, LawConfig> laws = new EnumMap<>(CountryLaw.class);
    // Players exempted from specific laws (e.g. a logging permit exempting someone from NO_DESTRUCTION).
    public final Map<UUID, Set<CountryLaw>> permits = new LinkedHashMap<>();
    // Bumped whenever a law/permit/Y-setting changes, so returning (or currently present) players can be
    // told their rules are stale and need re-agreeing to.
    public int lawVersion = 1;
    public final Map<UUID, Integer> agreedLawVersions = new LinkedHashMap<>();

    public Country(UUID id, String name, UUID founderId, ClaimRegion region, int cityRatePerBlockCents, Billing billing)
    {
        super(id, name, founderId, region, billing);
        this.cityRatePerBlockCents = cityRatePerBlockCents;
        for (CountryLaw law : CountryLaw.values())
            laws.put(law, new LawConfig());
    }

    public boolean isExempt(UUID playerId, CountryLaw law)
    {
        Set<CountryLaw> granted = permits.get(playerId);
        return granted != null && granted.contains(law);
    }

    // Whether laws apply at this Y coordinate: below the general cap, or inside an override zone regardless.
    public boolean lawsApplyAt(int x, int y, int z)
    {
        if (y <= lawMaxY)
            return true;
        for (Rect zone : lawYOverrideZones)
            if (zone.contains(x, z))
                return true;
        return false;
    }
}
