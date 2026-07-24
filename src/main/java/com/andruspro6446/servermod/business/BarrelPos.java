package com.andruspro6446.servermod.business;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// A sell barrel's location: which dimension and where in it. Used to find a business's live storefront stock
// and to bill it, without needing the world loaded until the moment it's actually queried.
public record BarrelPos(ResourceKey<Level> dimension, BlockPos pos) {}
