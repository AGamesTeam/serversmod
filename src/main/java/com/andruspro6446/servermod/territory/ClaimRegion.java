package com.andruspro6446.servermod.territory;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

// A claim's total area within one dimension: a set of non-overlapping rectangles rather than a single one,
// since claims can end up irregularly shaped after molding around other players' claims or merging with the
// owner's own adjacent ones.
public class ClaimRegion
{
    public final ResourceKey<Level> dimension;
    public final List<Rect> pieces;

    public ClaimRegion(ResourceKey<Level> dimension, List<Rect> pieces)
    {
        this.dimension = dimension;
        this.pieces = new ArrayList<>(pieces);
    }

    public boolean contains(ResourceKey<Level> dim, int x, int z)
    {
        if (!dim.equals(dimension))
            return false;
        for (Rect r : pieces)
            if (r.contains(x, z))
                return true;
        return false;
    }

    public boolean intersects(ResourceKey<Level> dim, Rect rect)
    {
        if (!dim.equals(dimension))
            return false;
        for (Rect r : pieces)
            if (r.intersects(rect))
                return true;
        return false;
    }

    public long blockCount()
    {
        long total = 0;
        for (Rect r : pieces)
            total += r.area();
        return total;
    }

    public void addAll(List<Rect> more)
    {
        pieces.addAll(more);
    }

    // How many blocks of `a` and `b` actually overlap - used to prorate rent when a claim only partially
    // overlaps a city/country rather than charging the claim's full size regardless of how much is inside.
    public static long overlapBlockCount(ClaimRegion a, ClaimRegion b)
    {
        if (!a.dimension.equals(b.dimension))
            return 0;
        long total = 0;
        for (Rect ra : a.pieces)
        {
            for (Rect rb : b.pieces)
            {
                if (!ra.intersects(rb))
                    continue;
                int minX = Math.max(ra.minX(), rb.minX());
                int minZ = Math.max(ra.minZ(), rb.minZ());
                int maxX = Math.min(ra.maxX(), rb.maxX());
                int maxZ = Math.min(ra.maxZ(), rb.maxZ());
                total += (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
            }
        }
        return total;
    }
}
