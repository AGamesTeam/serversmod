package com.andruspro6446.servermod.territory;

import java.util.ArrayList;
import java.util.List;

// An axis-aligned X/Z rectangle (inclusive bounds), full world height - the basic unit every claim's region
// is built from. Immutable; "molding" a claim around another one works by repeatedly cutting rectangles
// apart with minus().
public record Rect(int minX, int minZ, int maxX, int maxZ)
{
    public Rect
    {
        if (minX > maxX || minZ > maxZ)
            throw new IllegalArgumentException("Invalid rect: (%d,%d)-(%d,%d)".formatted(minX, minZ, maxX, maxZ));
    }

    public static Rect ofCorners(int x1, int z1, int x2, int z2)
    {
        return new Rect(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public boolean intersects(Rect other)
    {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public boolean contains(int x, int z)
    {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public long area()
    {
        return (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
    }

    // This rectangle with `other`'s overlap cut out, as up to 4 non-overlapping rectangles. Returns this
    // rectangle unchanged (as a single-element list) if they don't overlap at all.
    public List<Rect> minus(Rect other)
    {
        if (!intersects(other))
            return List.of(this);

        List<Rect> pieces = new ArrayList<>(4);
        if (minZ < other.minZ)
            pieces.add(new Rect(minX, minZ, maxX, other.minZ - 1));
        if (maxZ > other.maxZ)
            pieces.add(new Rect(minX, other.maxZ + 1, maxX, maxZ));

        int midMinZ = Math.max(minZ, other.minZ);
        int midMaxZ = Math.min(maxZ, other.maxZ);
        if (minX < other.minX)
            pieces.add(new Rect(minX, midMinZ, other.minX - 1, midMaxZ));
        if (maxX > other.maxX)
            pieces.add(new Rect(other.maxX + 1, midMinZ, maxX, midMaxZ));

        return pieces;
    }
}
