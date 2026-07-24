package com.andruspro6446.servermod.util;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Helpers for counting/removing items from a player's real inventory (used by the Trader Block and any
// website/command action that has to take items directly out of a player's inventory) and for giving items
// back, dropping any overflow that doesn't fit.
public final class InventoryUtil
{
    private InventoryUtil()
    {
    }

    public static int countItem(Inventory inventory, Item item)
    {
        int count = 0;
        for (ItemStack stack : inventory.items)
            if (stack.is(item))
                count += stack.getCount();
        for (ItemStack stack : inventory.offhand)
            if (stack.is(item))
                count += stack.getCount();
        return count;
    }

    // Removes up to `amount` of the given item from the inventory. Returns false (removing nothing) if the
    // inventory doesn't hold at least `amount` of the item.
    public static boolean removeItem(Inventory inventory, Item item, int amount)
    {
        if (countItem(inventory, item) < amount)
            return false;

        int remaining = removeFromList(inventory.items, item, amount);
        remaining = removeFromList(inventory.offhand, item, remaining);
        inventory.setChanged();
        return remaining == 0;
    }

    private static int removeFromList(NonNullList<ItemStack> list, Item item, int remaining)
    {
        for (int i = 0; i < list.size() && remaining > 0; i++)
        {
            ItemStack stack = list.get(i);
            if (stack.is(item))
            {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
            }
        }
        return remaining;
    }

    // Gives the player as much of the stack as fits in their inventory, dropping the rest at their feet.
    public static void giveOrDrop(Player player, ItemStack stack)
    {
        if (!player.getInventory().add(stack))
            player.drop(stack, false);
    }
}
