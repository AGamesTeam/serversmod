package com.andruspro6446.servermod.review;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

// A small fixed vocabulary shared by NPC-generated complaint/compliment tags and the good/bad checkboxes a
// player picks when reviewing their own order, so the storefront review list renders uniformly regardless of
// where a review came from.
public final class ReviewTags
{
    private ReviewTags()
    {
    }

    // Good
    public static final String FAIR_PRICES = "Fair prices";
    public static final String GREAT_VALUE = "Great value";
    public static final String FAST_SERVICE = "Fast service";
    public static final String FRIENDLY = "Friendly";
    public static final String EASY_TO_REACH = "Easy to reach";
    public static final String GOOD_SELECTION = "Good selection";
    public static final String SHOPPED_LOCAL = "Shopped local";
    public static final String FOREIGN_CUSTOMER = "Visiting from out of town";

    // Bad
    public static final String OVERPRICED = "Overpriced";
    public static final String LONG_WAIT = "Long wait";
    public static final String REJECTED = "Turned away";
    public static final String HARD_TO_REACH = "Hard to reach";
    public static final String DANGEROUS_TRIP = "Dangerous trip";
    public static final String CLOSED_WHEN_VISITED = "Closed when I visited";
    public static final String TRADE_WAR_ZONE = "Crossed into contested territory";

    public static final Set<String> GOOD = new LinkedHashSet<>(Set.of(
            FAIR_PRICES, GREAT_VALUE, FAST_SERVICE, FRIENDLY, EASY_TO_REACH, GOOD_SELECTION, SHOPPED_LOCAL, FOREIGN_CUSTOMER));
    public static final Set<String> BAD = new LinkedHashSet<>(Set.of(
            OVERPRICED, LONG_WAIT, REJECTED, HARD_TO_REACH, DANGEROUS_TRIP, CLOSED_WHEN_VISITED, TRADE_WAR_ZONE));

    public static boolean isKnown(String tag)
    {
        return GOOD.contains(tag) || BAD.contains(tag);
    }

    // A URL/HTML-attribute-safe identifier for a tag label - used as the web review form's checkbox field
    // name, so each tag gets its own uniquely-named checkbox (avoiding the "multiple checkboxes sharing one
    // name" collision that plain form parsing can't disambiguate).
    public static String slug(String label)
    {
        return label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
