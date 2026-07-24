package com.andruspro6446.servermod.territory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// A government-funded public plaza: any business with a queue point inside its radius counts as
// discoverable (see business.BusinessData.isDiscoverable) regardless of whether it has its own sign or
// advert running - a civic amenity funded once out of the treasury (see TerritoryData.publicSquareCostCents).
public class PublicSquare
{
    public final ResourceKey<Level> dimension;
    public final BlockPos pos;
    public final int radius;

    public PublicSquare(ResourceKey<Level> dimension, BlockPos pos, int radius)
    {
        this.dimension = dimension;
        this.pos = pos;
        this.radius = radius;
    }

    public boolean contains(ResourceKey<Level> dim, BlockPos other)
    {
        return dim.equals(dimension) && pos.distSqr(other) <= (double) radius * radius;
    }
}
