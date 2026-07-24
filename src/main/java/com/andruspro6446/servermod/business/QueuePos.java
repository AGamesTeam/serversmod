package com.andruspro6446.servermod.business;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// A spot a business owner has designated for customer NPCs to queue at. The line of waiting NPCs extends
// backward from `pos`, opposite `facing` (so `facing` points the way the front-of-line customer looks, e.g.
// at the Sell Barrel/counter). A business can have several of these, acting as independent queue lines.
public record QueuePos(ResourceKey<Level> dimension, BlockPos pos, Direction facing) {}
