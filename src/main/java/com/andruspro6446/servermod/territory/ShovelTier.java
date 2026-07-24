package com.andruspro6446.servermod.territory;

import java.util.UUID;

// An admin-defined tier of claim shovel: how much it costs, how many claims one shovel can make before it's
// used up, and the largest single selection it's allowed to claim.
public class ShovelTier
{
    public final UUID id;
    public String name;
    public int priceCents;
    public int maxCharges;
    public long maxAreaBlocks;

    public ShovelTier(UUID id, String name, int priceCents, int maxCharges, long maxAreaBlocks)
    {
        this.id = id;
        this.name = name;
        this.priceCents = priceCents;
        this.maxCharges = maxCharges;
        this.maxAreaBlocks = maxAreaBlocks;
    }
}
