package com.andruspro6446.servermod.business;

// A customer NPC this business has served before, tracked by name so future visits can be recognized as the
// same "regular" - see BusinessData.recordRegularVisit/pickRegularName and how visitCount softens their
// eventual review (review.ReviewGenerator). Capped per-business (see BusinessData.MAX_REGULARS_TRACKED),
// evicting whoever's been away longest when a new regular needs the space.
public class RegularCustomer
{
    public final String name;
    public int visitCount;
    public long lastVisitMillis;

    public RegularCustomer(String name, int visitCount, long lastVisitMillis)
    {
        this.name = name;
        this.visitCount = visitCount;
        this.lastVisitMillis = lastVisitMillis;
    }
}
