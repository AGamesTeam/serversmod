package com.andruspro6446.servermod.territory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

// A holding cell a city or country has designated: a point, a radius jailed players are confined within, and
// a maximum number of prisoners it can hold at once.
public class JailCell
{
    public final UUID id;
    public String name;
    public final ResourceKey<Level> dimension;
    public final BlockPos pos;
    public int radius;
    public int maxCapacity;

    public JailCell(UUID id, String name, ResourceKey<Level> dimension, BlockPos pos, int radius, int maxCapacity)
    {
        this.id = id;
        this.name = name;
        this.dimension = dimension;
        this.pos = pos;
        this.radius = radius;
        this.maxCapacity = maxCapacity;
    }
}
