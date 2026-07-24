package com.andruspro6446.servermod.business;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// An admin-defined manufacturing recipe: consume some input items, produce an output item. Businesses run
// these on their own stock to turn cheap raw materials into a pricier finished product.
public class Recipe
{
    public final UUID id;
    public final ResourceLocation output;
    public final int outputCount;
    public final Map<ResourceLocation, Integer> inputs = new LinkedHashMap<>();

    public Recipe(UUID id, ResourceLocation output, int outputCount)
    {
        this.id = id;
        this.output = output;
        this.outputCount = outputCount;
    }
}
