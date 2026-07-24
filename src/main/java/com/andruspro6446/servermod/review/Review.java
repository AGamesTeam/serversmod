package com.andruspro6446.servermod.review;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

// A single review left on a business's storefront - either auto-generated from a customer NPC's visit, or
// submitted by a real player for one of their own completed orders. Stored on Business.reviews (newest first)
// and rendered on the public storefront page; only an admin can delete one (see BusinessData.removeReview).
public class Review
{
    public final UUID id;
    public final ReviewSource source;
    public String authorName;
    public final UUID authorPlayerId; // null for NPC reviews
    public double stars; // 0.0-5.0, one decimal place
    public String text; // freeform, only ever set for NPC reviews - player reviews are stars + tags only
    public final Set<String> tags = new LinkedHashSet<>();
    public final long timestampMillis;
    // A single public reply from the business owner, shown right under the review on the storefront page -
    // see web.BusinessHandler.handleReplyToReview. Null until the owner replies; overwritable, but there's
    // only ever one.
    public String ownerReply;

    public Review(UUID id, ReviewSource source, String authorName, UUID authorPlayerId, double stars, String text, long timestampMillis)
    {
        this.id = id;
        this.source = source;
        this.authorName = authorName;
        this.authorPlayerId = authorPlayerId;
        this.stars = stars;
        this.text = text;
        this.timestampMillis = timestampMillis;
    }
}
