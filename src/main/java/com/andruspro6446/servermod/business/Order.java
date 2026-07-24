package com.andruspro6446.servermod.business;

import com.andruspro6446.servermod.review.ReviewSource;

import java.util.UUID;

// A single completed sale from this business's storefront - who bought it (an NPC visit or a real player's
// website purchase), what, and for how much. Kept as a rolling history (see BusinessData.addOrder) so an
// owner can see what's actually been selling, and so directory badges like "Fastest Growing" have something
// to compare over time.
public class Order
{
    public final UUID id;
    public final ReviewSource source;
    public final String buyerName;
    public final String itemSummary;
    public final int totalCents;
    public final long timestampMillis;

    public Order(UUID id, ReviewSource source, String buyerName, String itemSummary, int totalCents, long timestampMillis)
    {
        this.id = id;
        this.source = source;
        this.buyerName = buyerName;
        this.itemSummary = itemSummary;
        this.totalCents = totalCents;
        this.timestampMillis = timestampMillis;
    }
}
