package com.andruspro6446.servermod.territory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// An active punishment against a player: jail time and/or a mining quota, either of which may be absent (a
// fine-only resolution never creates one of these at all). Released once every assigned condition is met.
public class Sentence
{
    public final UUID playerId;
    public final UUID jailCellId; // null if this is a quota-only sentence with no confinement
    public final long releaseAtMillis; // 0 if there's no time component
    public final int miningQuotaCents; // 0 if there's no mining quota
    public int miningProgressCents;
    public final List<ItemStack> confiscatedItems = new ArrayList<>();
    public boolean applied; // whether confiscation/teleport has actually happened yet (deferred for offline offenders)

    public ResourceKey<Level> returnDimension;
    public double returnX;
    public double returnY;
    public double returnZ;

    public Sentence(UUID playerId, UUID jailCellId, long releaseAtMillis, int miningQuotaCents)
    {
        this.playerId = playerId;
        this.jailCellId = jailCellId;
        this.releaseAtMillis = releaseAtMillis;
        this.miningQuotaCents = miningQuotaCents;
    }

    public boolean isComplete()
    {
        boolean timeServed = releaseAtMillis == 0 || System.currentTimeMillis() >= releaseAtMillis;
        boolean quotaMet = miningQuotaCents == 0 || miningProgressCents >= miningQuotaCents;
        return timeServed && quotaMet;
    }
}
