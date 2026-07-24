package com.andruspro6446.servermod.business;

import com.andruspro6446.servermod.Config;
import com.andruspro6446.servermod.territory.City;
import com.andruspro6446.servermod.territory.Country;
import com.andruspro6446.servermod.territory.Government;
import com.andruspro6446.servermod.territory.TerritoryData;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Sales tax: every government (City and/or Country) whose claimed territory contains at least one of a
// business's Sell Barrels takes its own cut of every completed sale - not a single combined/split rate. A
// business with barrels spread across several governments pays each of them separately, each at its own
// effective rate (base rate, adjusted per-government by residency, sanctions, and review score - see
// computeCut). Governments and land claims are independent overlapping regions (see TerritoryData), so all
// of this is computed fresh per sale rather than cached anywhere.
public final class SalesTax
{
    // Extra flat percentage points added to a government's rate when the business is resident of (owns
    // claimed land in) a government currently in a sanction relationship (either direction) with this one.
    private static final int SANCTION_SURCHARGE_PERCENT = 50;
    // Multiplicative discount applied to a government's rate when the business has a strong, well-established
    // recent review record - the Chamber of Commerce reward for quality over just raw tax avoidance.
    private static final double CHAMBER_OF_COMMERCE_DISCOUNT = 0.8;
    private static final double CHAMBER_OF_COMMERCE_MIN_SCORE = 4.0;
    private static final int CHAMBER_OF_COMMERCE_MIN_REVIEWS = 3;

    private SalesTax()
    {
    }

    // Every government (City and/or Country) with at least one of this business's Sell Barrels inside its
    // territory - the shared "who taxes/targets this business" lookup reused by licensing, festivals,
    // leaderboards, local-pride reviews, and the diplomatic discount, not just tax collection itself.
    public static Set<Government> applicableGovernments(MinecraftServer server, Business business)
    {
        Set<Government> governments = new LinkedHashSet<>();
        if (business.barrels.isEmpty())
            return governments;

        TerritoryData territory = TerritoryData.get(server);
        for (BarrelPos barrel : business.barrels)
        {
            City city = territory.findCityAt(barrel.dimension(), barrel.pos().getX(), barrel.pos().getZ());
            if (city != null)
                governments.add(city);
            Country country = territory.findCountryAt(barrel.dimension(), barrel.pos().getX(), barrel.pos().getZ());
            if (country != null)
                governments.add(country);
        }
        return governments;
    }

    // Deducts each applicable government's cut from a sale's proceeds (plus any state-affiliation profit
    // share - see Business.stateAffiliationGovernmentId), crediting each government's own treasury directly,
    // and returns the net amount that actually reaches the business's balance.
    public static int applyAndGetNet(MinecraftServer server, Business business, int saleTotalCents)
    {
        if (saleTotalCents <= 0)
            return saleTotalCents;

        TerritoryData territory = TerritoryData.get(server);
        Set<Government> governments = applicableGovernments(server, business);
        Set<Government> residentOf = territory.residentGovernments(business.ownerId);

        Map<Government, Integer> cuts = new LinkedHashMap<>();
        for (Government government : governments)
            cuts.put(government, computeCut(server, government, business, residentOf, saleTotalCents));

        // Trade treaty: if two taxing governments are BOTH allied with each other, only the larger of their
        // two cuts actually applies instead of stacking both.
        List<Government> taxingList = new ArrayList<>(cuts.keySet());
        for (int i = 0; i < taxingList.size(); i++)
        {
            for (int j = i + 1; j < taxingList.size(); j++)
            {
                Government a = taxingList.get(i);
                Government b = taxingList.get(j);
                if (a.alliedGovernmentIds.contains(b.id) && b.alliedGovernmentIds.contains(a.id))
                {
                    if (cuts.get(a) <= cuts.get(b))
                        cuts.put(a, 0);
                    else
                        cuts.put(b, 0);
                }
            }
        }

        int totalTaxCents = 0;
        for (Map.Entry<Government, Integer> entry : cuts.entrySet())
        {
            if (entry.getValue() <= 0)
                continue;
            territory.addIncome(entry.getKey(), entry.getValue());
            totalTaxCents += entry.getValue();
        }

        if (business.stateAffiliationGovernmentId != null && business.stateProfitSharePercent > 0)
        {
            Government stateGov = territory.getGovernmentById(business.stateAffiliationGovernmentId);
            if (stateGov != null)
            {
                int shareCents = (int) Math.round(saleTotalCents * business.stateProfitSharePercent / 100.0);
                if (shareCents > 0)
                {
                    territory.addIncome(stateGov, shareCents);
                    totalTaxCents += shareCents;
                }
            }
        }

        return Math.max(0, saleTotalCents - totalTaxCents);
    }

    // Read-only, for showing an owner up front which governments currently tax their transactions, at what
    // effective rate (after residency/sanctions/Chamber-of-Commerce adjustments) - so money quietly leaving
    // on every sale doesn't read as a bug. One line per government with a nonzero effective rate.
    public static List<String> describeApplicableTaxes(MinecraftServer server, Business business)
    {
        List<String> lines = new ArrayList<>();
        Set<Government> governments = applicableGovernments(server, business);
        if (governments.isEmpty())
            return lines;

        TerritoryData territory = TerritoryData.get(server);
        Set<Government> residentOf = territory.residentGovernments(business.ownerId);
        for (Government government : governments)
        {
            double rate = effectiveRatePercent(server, government, business, residentOf);
            if (rate > 0)
                lines.add(government.name + " (" + round1(rate) + "%)");
        }
        return lines;
    }

    // True if the business is resident of (owns claimed land in) a government currently sanctioned by, or
    // sanctioning, any government that actually taxes it - a heuristic "operating in contested territory"
    // signal reused by CustomerNpcManager's theft-risk multiplier and ReviewGenerator's danger flavor, since
    // both are really the same underlying signal viewed from a different angle.
    public static boolean inTradeWarZone(MinecraftServer server, Business business)
    {
        TerritoryData territory = TerritoryData.get(server);
        Set<Government> residentOf = territory.residentGovernments(business.ownerId);
        if (residentOf.isEmpty())
            return false;
        for (Government taxing : applicableGovernments(server, business))
            if (sanctionSurcharge(taxing, residentOf) > 0)
                return true;
        return false;
    }

    private static int computeCut(MinecraftServer server, Government government, Business business, Set<Government> residentOf, int saleTotalCents)
    {
        double rate = effectiveRatePercent(server, government, business, residentOf);
        return (int) Math.round(saleTotalCents * rate / 100.0);
    }

    private static double effectiveRatePercent(MinecraftServer server, Government government, Business business, Set<Government> residentOf)
    {
        double rate = government.salesTaxRatePercent;
        if (!residentOf.contains(government))
            rate += government.foreignTaxSurchargePercent;
        rate += sanctionSurcharge(government, residentOf);

        double reviewScore = BusinessData.get(server).recentReviewScore(business, Config.reviewRecencyWindowHours * 3600_000L);
        if (business.reviews.size() >= CHAMBER_OF_COMMERCE_MIN_REVIEWS && reviewScore >= CHAMBER_OF_COMMERCE_MIN_SCORE)
            rate *= CHAMBER_OF_COMMERCE_DISCOUNT;

        return Math.max(0, rate);
    }

    private static int sanctionSurcharge(Government taxing, Set<Government> residentOf)
    {
        for (Government home : residentOf)
        {
            if (home == taxing)
                continue;
            if (taxing.sanctionedGovernmentIds.contains(home.id) || home.sanctionedGovernmentIds.contains(taxing.id))
                return SANCTION_SURCHARGE_PERCENT;
        }
        return 0;
    }

    private static double round1(double value)
    {
        return Math.round(value * 10.0) / 10.0;
    }
}
