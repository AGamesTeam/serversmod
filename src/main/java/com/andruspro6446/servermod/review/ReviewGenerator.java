package com.andruspro6446.servermod.review;

import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.customer.CustomerTrait;
import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.market.MarketEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// Turns a finished customer NPC visit into a Review: a 0.0-5.0 star rating plus a written complaint or
// compliment covering price fairness (compared to other sellers of the same item, or the market base price if
// this is the only seller), wait time, rejection, being turned away by closed hours, and how dangerous/damaging
// the trip there was. Called once per visit outcome by customer.CustomerNpcManager. Text is assembled from
// randomized phrase pools, with sentence order shuffled and an optional closing remark, so two five-star
// reviews rarely read identically.
public final class ReviewGenerator
{
    private static final Random RANDOM = new Random();

    public enum Outcome { COMPLETED, DECLINED, TIMED_OUT, CLOSED }

    // Extra, situational modifiers beyond a customer's own trait - a Loyalty Card bonus, how many times
    // they've shopped here before as a tracked regular, etc. (see business.BusinessData's upgrade/regulars
    // methods). Only applied on a COMPLETED sale - the other outcomes don't involve any of this.
    public record ReviewModifiers(double extraLeniency, double priceSensitivityScale)
    {
        public static final ReviewModifiers NONE = new ReviewModifiers(0.0, 1.0);
    }

    private ReviewGenerator()
    {
    }

    public static Review generate(MinecraftServer server, Business business, String npcName, Outcome outcome,
                                   Map<ResourceLocation, Integer> basket, int totalCentsPaid,
                                   long waitTicksAtFront, float damageTakenEnRoute, int hostilesSeenEnRoute)
    {
        return generate(server, business, npcName, outcome, basket, totalCentsPaid, waitTicksAtFront,
                damageTakenEnRoute, hostilesSeenEnRoute, CustomerTrait.NONE);
    }

    // `trait` colors the outcome: impatient/picky customers notice wait time and price more, generous ones
    // give a bit of benefit of the doubt regardless of what happened - see CustomerTrait's per-trait factors.
    public static Review generate(MinecraftServer server, Business business, String npcName, Outcome outcome,
                                   Map<ResourceLocation, Integer> basket, int totalCentsPaid,
                                   long waitTicksAtFront, float damageTakenEnRoute, int hostilesSeenEnRoute, CustomerTrait trait)
    {
        return generate(server, business, npcName, outcome, basket, totalCentsPaid, waitTicksAtFront,
                damageTakenEnRoute, hostilesSeenEnRoute, trait, ReviewModifiers.NONE);
    }

    public static Review generate(MinecraftServer server, Business business, String npcName, Outcome outcome,
                                   Map<ResourceLocation, Integer> basket, int totalCentsPaid, long waitTicksAtFront,
                                   float damageTakenEnRoute, int hostilesSeenEnRoute, CustomerTrait trait, ReviewModifiers extra)
    {
        return generate(server, business, npcName, outcome, basket, totalCentsPaid, waitTicksAtFront,
                damageTakenEnRoute, hostilesSeenEnRoute, trait, extra, null, false);
    }

    // `isLocalCustomer` is null if the NPC has no home government at all, true if its home is one of the
    // governments actually taxing this business (see business.SalesTax.applicableGovernments), false
    // otherwise - only meaningful for a COMPLETED sale. `crossedTradeWarZone` mirrors
    // business.SalesTax.inTradeWarZone (also only meaningful for COMPLETED).
    public static Review generate(MinecraftServer server, Business business, String npcName, Outcome outcome,
                                   Map<ResourceLocation, Integer> basket, int totalCentsPaid, long waitTicksAtFront,
                                   float damageTakenEnRoute, int hostilesSeenEnRoute, CustomerTrait trait, ReviewModifiers extra,
                                   Boolean isLocalCustomer, boolean crossedTradeWarZone)
    {
        long now = System.currentTimeMillis();

        if (outcome == Outcome.DECLINED)
        {
            double stars = round1(clamp(RANDOM.nextDouble() + trait.reviewLeniencyBonus, 0.0, 5.0));
            Review review = new Review(UUID.randomUUID(), ReviewSource.NPC, "Karen", null, stars, pick(REJECTED_TEMPLATES).formatted(business.name), now);
            review.tags.add(ReviewTags.REJECTED);
            return review;
        }

        if (outcome == Outcome.TIMED_OUT)
        {
            double stars = round1(clamp(1.5 + RANDOM.nextDouble() + trait.reviewLeniencyBonus, 0.0, 5.0));
            Review review = new Review(UUID.randomUUID(), ReviewSource.NPC, npcName, null, stars, pick(TIMEOUT_TEMPLATES).formatted(business.name), now);
            review.tags.add(ReviewTags.LONG_WAIT);
            return review;
        }

        if (outcome == Outcome.CLOSED)
        {
            double stars = round1(clamp(1.0 + RANDOM.nextDouble() + trait.reviewLeniencyBonus, 0.0, 5.0));
            Review review = new Review(UUID.randomUUID(), ReviewSource.NPC, npcName, null, stars, pick(CLOSED_TEMPLATES).formatted(business.name), now);
            review.tags.add(ReviewTags.CLOSED_WHEN_VISITED);
            return review;
        }

        // COMPLETED
        PriceVerdict price = evaluatePrice(server, business, basket);
        double effectivePctOver = price.weightedPctOver * trait.priceSensitivityMultiplier * extra.priceSensitivityScale();
        long effectiveWaitTicks = (long) (waitTicksAtFront * trait.impatienceMultiplier);
        boolean dangerous = damageTakenEnRoute > 0 || hostilesSeenEnRoute >= 2 || crossedTradeWarZone;
        boolean severeDanger = damageTakenEnRoute >= 6 || hostilesSeenEnRoute >= 4;

        double priceDeduction;
        if (effectivePctOver <= 0.05) priceDeduction = 0;
        else if (effectivePctOver <= 0.15) priceDeduction = 0.3;
        else if (effectivePctOver <= 0.35) priceDeduction = 0.8;
        else if (effectivePctOver <= 0.6) priceDeduction = 1.4;
        else priceDeduction = 2.0;

        double waitDeduction;
        if (effectiveWaitTicks <= 200) waitDeduction = 0;
        else if (effectiveWaitTicks <= 1200) waitDeduction = 0.3;
        else if (effectiveWaitTicks <= 3600) waitDeduction = 0.8;
        else waitDeduction = 1.3;

        double dangerDeduction = !dangerous ? 0 : severeDanger ? 1.5 : 0.7;

        double stars = round1(clamp(5.0 - priceDeduction - waitDeduction - dangerDeduction + trait.reviewLeniencyBonus + extra.extraLeniency(), 0.0, 5.0));

        Set<String> tags = new LinkedHashSet<>();
        List<String> fragments = new ArrayList<>();

        if (priceDeduction >= 1.4)
        {
            fragments.add(pick(price.cheaperStoreNames.isEmpty() ? OVERPRICED_TEMPLATES_NO_NAME : OVERPRICED_TEMPLATES_WITH_NAME)
                    .formatted(price.cheaperStoreNames.isEmpty() ? "" : String.join(" or ", price.cheaperStoreNames)));
            tags.add(ReviewTags.OVERPRICED);
        }
        else if (priceDeduction > 0)
        {
            fragments.add(pick(BIT_PRICEY_TEMPLATES));
            tags.add(ReviewTags.OVERPRICED);
        }
        else
        {
            fragments.add(pick(price.onlySeller ? GOOD_PRICE_TEMPLATES_ONLY_SELLER : GOOD_PRICE_TEMPLATES));
            tags.add(price.weightedPctOver < -0.05 ? ReviewTags.GREAT_VALUE : ReviewTags.FAIR_PRICES);
        }

        if (waitDeduction >= 0.8)
        {
            fragments.add(pick(LONG_WAIT_TEMPLATES));
            tags.add(ReviewTags.LONG_WAIT);
        }
        else if (waitDeduction == 0)
        {
            fragments.add(pick(FAST_SERVICE_TEMPLATES));
            tags.add(ReviewTags.FAST_SERVICE);
        }

        if (dangerous)
        {
            if (crossedTradeWarZone)
            {
                fragments.add(pick(TRADE_WAR_ZONE_TEMPLATES));
                tags.add(ReviewTags.TRADE_WAR_ZONE);
            }
            else
            {
                fragments.add(pick(severeDanger ? DANGEROUS_TEMPLATES_SEVERE : DANGEROUS_TEMPLATES_MILD));
            }
            tags.add(ReviewTags.DANGEROUS_TRIP);
        }
        else
        {
            fragments.add(pick(SAFE_TRIP_TEMPLATES));
            tags.add(ReviewTags.EASY_TO_REACH);
        }

        if (isLocalCustomer != null)
        {
            if (isLocalCustomer)
            {
                fragments.add(pick(SHOPPED_LOCAL_TEMPLATES));
                tags.add(ReviewTags.SHOPPED_LOCAL);
            }
            else
            {
                fragments.add(pick(FOREIGN_CUSTOMER_TEMPLATES));
                tags.add(ReviewTags.FOREIGN_CUSTOMER);
            }
        }

        // Shuffling which complaint/compliment comes first - not just the wording within each - is what
        // actually keeps two 5-star reviews from reading like the same template every time.
        Collections.shuffle(fragments, RANDOM);

        StringBuilder text = new StringBuilder(pick(OPENER_TEMPLATES).formatted(business.name));
        for (String fragment : fragments)
            text.append(' ').append(fragment);

        if (RANDOM.nextDouble() < 0.55)
        {
            String[] closerPool = stars >= 4.0 ? CLOSER_TEMPLATES_POSITIVE : stars <= 2.0 ? CLOSER_TEMPLATES_NEGATIVE : CLOSER_TEMPLATES_NEUTRAL;
            text.append(' ').append(pick(closerPool));
        }

        Review review = new Review(UUID.randomUUID(), ReviewSource.NPC, npcName, null, stars, text.toString(), now);
        review.tags.addAll(tags);
        return review;
    }

    private record PriceVerdict(double weightedPctOver, List<String> cheaperStoreNames, boolean onlySeller) {}

    private static PriceVerdict evaluatePrice(MinecraftServer server, Business business, Map<ResourceLocation, Integer> basket)
    {
        BusinessData data = BusinessData.get(server);
        MarketData market = MarketData.get(server);
        List<Business> others = data.getAll();

        double weightedNumerator = 0;
        long totalCentsSpent = 0;
        List<String> cheaperStoreNames = new ArrayList<>();
        boolean onlySeller = true;

        for (Map.Entry<ResourceLocation, Integer> entry : basket.entrySet())
        {
            ResourceLocation item = entry.getKey();
            int qty = entry.getValue();
            Integer unitPriceCents = business.listingPricesCents.get(item);
            if (unitPriceCents == null || unitPriceCents <= 0)
                continue;

            long itemCents = (long) unitPriceCents * qty;
            totalCentsSpent += itemCents;

            List<Map.Entry<String, Integer>> alternatives = new ArrayList<>();
            for (Business other : others)
            {
                if (other.ownerId.equals(business.ownerId) || !other.listed || other.status == Business.Status.SUSPENDED)
                    continue;
                Integer otherPrice = other.listingPricesCents.get(item);
                if (otherPrice != null && otherPrice > 0)
                    alternatives.add(Map.entry(other.name, otherPrice));
            }

            double baselineCents;
            if (!alternatives.isEmpty())
            {
                onlySeller = false;
                alternatives.sort(Comparator.comparing(Map.Entry::getValue));
                baselineCents = alternatives.stream().mapToInt(Map.Entry::getValue).average().orElse(unitPriceCents);
                for (Map.Entry<String, Integer> alt : alternatives)
                    if (alt.getValue() < unitPriceCents && cheaperStoreNames.size() < 2 && !cheaperStoreNames.contains(alt.getKey()))
                        cheaperStoreNames.add(alt.getKey());
            }
            else
            {
                MarketEntry marketEntry = market.getEntry(item);
                baselineCents = marketEntry != null ? marketEntry.basePrice * 100.0 : unitPriceCents;
            }

            double pct = baselineCents > 0 ? (unitPriceCents - baselineCents) / baselineCents : 0;
            weightedNumerator += pct * itemCents;
        }

        double weightedPct = totalCentsSpent > 0 ? weightedNumerator / totalCentsSpent : 0;
        return new PriceVerdict(weightedPct, cheaperStoreNames, onlySeller);
    }

    private static String pick(String[] templates)
    {
        return templates[RANDOM.nextInt(templates.length)];
    }

    private static double round1(double value)
    {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double clamp(double value, double min, double max)
    {
        return Math.min(max, Math.max(min, value));
    }

    private static final String[] OPENER_TEMPLATES = {
            "Visited %s today.",
            "Just bought from %s.",
            "Stopped by %s for some shopping.",
            "Went to %s to pick up a few things.",
            "Popped into %s earlier.",
            "Finally checked out %s.",
            "Made the trip out to %s.",
            "Swung by %s this morning.",
            "Been meaning to try %s, so I did.",
            "%s was my stop today.",
    };

    private static final String[] GOOD_PRICE_TEMPLATES = {
            "Prices were fair compared to other shops.",
            "Reasonably priced, no complaints there.",
            "Good prices for what you get.",
            "Prices felt about right, honestly.",
            "Not the cheapest, not the priciest - right in the middle.",
            "Can't argue with the prices.",
            "Paid a fair amount for what I got.",
            "Prices were exactly what I expected.",
    };

    private static final String[] GOOD_PRICE_TEMPLATES_ONLY_SELLER = {
            "Prices matched the going rate.",
            "Fairly priced given nobody else sells this stuff nearby.",
            "Only place around selling this, and they didn't gouge me for it.",
            "Priced about what I'd expect, seeing as there's no competition.",
    };

    private static final String[] BIT_PRICEY_TEMPLATES = {
            "A little pricier than I'd like, but not unreasonable.",
            "Prices were a touch high, though still okay.",
            "Paid a bit more than I wanted to.",
            "Slightly overpriced, but I needed the stuff anyway.",
            "Could be cheaper, but it's not a dealbreaker.",
    };

    private static final String[] OVERPRICED_TEMPLATES_WITH_NAME = {
            "Way pricier than %s.",
            "You can get this cheaper at %s.",
            "Overpriced compared to %s.",
            "Paid way more here than I would've at %s.",
            "Should've just gone to %s instead.",
            "Not sure why I didn't just buy from %s - would've saved a bunch.",
    };

    private static final String[] OVERPRICED_TEMPLATES_NO_NAME = {
            "Way overpriced compared to the going rate.",
            "Charging way more than this stuff is worth.",
            "Prices were honestly kind of ridiculous.",
            "Felt like I got ripped off a little.",
            "Way more expensive than it should be.",
    };

    private static final String[] FAST_SERVICE_TEMPLATES = {
            "Served quickly, no complaints.",
            "Didn't have to wait long at all.",
            "Fast, friendly service.",
            "Barely had to wait before getting helped.",
            "In and out in no time.",
            "Quick service, no messing around.",
    };

    private static final String[] LONG_WAIT_TEMPLATES = {
            "Had to wait forever before anyone helped me.",
            "The wait was way too long.",
            "Stood around for ages before getting served.",
            "Waited so long I almost left.",
            "Took way too long to actually get helped.",
            "Line moved at a snail's pace.",
    };

    private static final String[] SAFE_TRIP_TEMPLATES = {
            "Easy walk to get here.",
            "No trouble getting to the shop.",
            "Nice and easy trip over.",
            "Getting here was no hassle at all.",
    };

    private static final String[] DANGEROUS_TEMPLATES_MILD = {
            "Ran into some trouble on the way here.",
            "Had a bit of a scare getting to the store.",
            "Took a few hits just getting here.",
            "The walk over wasn't exactly peaceful.",
    };

    private static final String[] DANGEROUS_TEMPLATES_SEVERE = {
            "Got attacked multiple times just walking here - store is in a dangerous spot!",
            "Nearly didn't make it here alive, had to fight through danger the whole way.",
            "Had to fight off mobs just to reach this place. Not worth the risk.",
            "Barely survived the trip over. Someone needs to deal with what's out there.",
            "Genuinely scary walk. Almost turned back twice.",
    };

    private static final String[] TRADE_WAR_ZONE_TEMPLATES = {
            "Had to cross into contested territory just to get here - tensions between the local governments made it a nervy trip.",
            "This place sits right in the middle of a trade dispute - didn't feel entirely safe getting here.",
            "Getting here meant passing through territory the local government's at odds with. Not exactly relaxing.",
            "Had to get through some tense, contested ground on the way. Hope things settle down out there.",
    };

    private static final String[] SHOPPED_LOCAL_TEMPLATES = {
            "Nice shopping local for once - felt good keeping it close to home.",
            "Glad to support a shop right here in my own territory.",
            "Always prefer buying local when I can.",
            "Kept it local this time - good to see a shop doing well nearby.",
    };

    private static final String[] FOREIGN_CUSTOMER_TEMPLATES = {
            "Wasn't from around here, but wanted to check this place out.",
            "Came from out of town just to see what the fuss was about.",
            "Bit of a trip from my own territory, but worth seeing what's on offer here.",
            "Visiting from elsewhere - always fun trying shops outside my usual haunts.",
    };

    private static final String[] REJECTED_TEMPLATES = {
            "Waited in line at %s and got turned away for NO reason. Unbelievable.",
            "Went to %s ready to buy and they just refused to serve me?! Rude.",
            "Absolutely will not be returning to %s after being flat-out rejected.",
            "Excuse me?! %s just turned me away like my money wasn't good enough.",
            "Stood in line, got to the front, and %s just said no. No explanation, nothing.",
            "Never been so disrespected in my life. %s can forget about my business.",
            "Zero stars if I could. %s rejected me for absolutely no reason.",
    };

    private static final String[] TIMEOUT_TEMPLATES = {
            "Waited forever at %s and nobody ever showed up. Left empty-handed.",
            "Stood in line at %s for the longest time - guess nobody works there.",
            "Gave up waiting at %s. Left without buying anything.",
            "Is anyone even running %s? Waited and waited, nothing.",
            "Left %s without buying a thing - nobody ever came to help.",
            "Wasted my whole trip waiting at %s. Not doing that again.",
    };

    private static final String[] CLOSED_TEMPLATES = {
            "Walked all the way to %s just to find it closed. What a waste of time.",
            "%s was closed when I got there. Check your hours!",
            "Nobody told me %s would be closed. Left without buying anything.",
            "Showed up to %s and the sign said closed. Bit annoying honestly.",
            "Made the trip to %s for nothing - they weren't even open.",
            "%s should really post their hours better. Came all this way for a closed shop.",
    };

    private static final String[] CLOSER_TEMPLATES_POSITIVE = {
            "Would come back.",
            "Recommended.",
            "Solid shop overall.",
            "Can't complain.",
            "Will definitely be back.",
    };

    private static final String[] CLOSER_TEMPLATES_NEUTRAL = {
            "Might come back, might not.",
            "It was fine, nothing special.",
            "Does the job.",
            "Average experience overall.",
    };

    private static final String[] CLOSER_TEMPLATES_NEGATIVE = {
            "Not sure I'll be back.",
            "Wouldn't really recommend it.",
            "Left disappointed.",
            "Expected better, honestly.",
    };
}
