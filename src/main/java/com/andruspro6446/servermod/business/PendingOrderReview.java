package com.andruspro6446.servermod.business;

import java.util.UUID;

// A completed website purchase a buyer hasn't yet reviewed. Created right after a successful purchase
// (BusinessHandler.doPurchase) and consumed the moment that buyer submits their one review for it - see
// Business.pendingPlayerReviews / BusinessData.consumePendingOrderReview.
public class PendingOrderReview
{
    public final UUID orderId;
    public final UUID buyerId;
    public final String itemSummary;
    public final long timestampMillis;

    public PendingOrderReview(UUID orderId, UUID buyerId, String itemSummary, long timestampMillis)
    {
        this.orderId = orderId;
        this.buyerId = buyerId;
        this.itemSummary = itemSummary;
        this.timestampMillis = timestampMillis;
    }
}
