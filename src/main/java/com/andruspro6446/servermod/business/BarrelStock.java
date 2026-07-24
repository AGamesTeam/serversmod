package com.andruspro6446.servermod.business;

import com.andruspro6446.servermod.block.SellBarrelBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

// Reads and removes stock from a business's real, physical Sell Barrels - the live contents its storefront
// actually sells from (as opposed to Business.inventory, the virtual stock). Shared by the web storefront
// (web.BusinessHandler) and the in-game customer NPC purchase flow (customer.CustomerNpcManager) so both go
// through the exact same barrel-scanning logic. Must only be called from the main server thread.
public final class BarrelStock
{
    private BarrelStock()
    {
    }

    public static Map<ResourceLocation, Integer> barrelStock(MinecraftServer server, Business business)
    {
        Map<ResourceLocation, Integer> stock = new LinkedHashMap<>();
        for (BarrelPos barrelPos : business.barrels)
        {
            SellBarrelBlockEntity barrel = findBarrel(server, barrelPos);
            if (barrel == null)
                continue;
            for (int slot = 0; slot < barrel.getContainerSize(); slot++)
            {
                ItemStack stack = barrel.getItem(slot);
                if (stack.isEmpty())
                    continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null)
                    stock.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return stock;
    }

    public static SellBarrelBlockEntity findBarrel(MinecraftServer server, BarrelPos barrelPos)
    {
        ServerLevel level = server.getLevel(barrelPos.dimension());
        if (level == null)
            return null;
        return level.getBlockEntity(barrelPos.pos()) instanceof SellBarrelBlockEntity barrel ? barrel : null;
    }

    // Physically removes up to `amount` of an item across a business's barrels. Returns false (removing
    // nothing) if the combined real stock is short.
    public static boolean removeFromBarrels(MinecraftServer server, Business business, ResourceLocation item, int amount)
    {
        if (barrelStock(server, business).getOrDefault(item, 0) < amount)
            return false;

        int remaining = amount;
        for (BarrelPos barrelPos : business.barrels)
        {
            if (remaining <= 0)
                break;
            SellBarrelBlockEntity barrel = findBarrel(server, barrelPos);
            if (barrel == null)
                continue;
            for (int slot = 0; slot < barrel.getContainerSize() && remaining > 0; slot++)
            {
                ItemStack stack = barrel.getItem(slot);
                if (stack.isEmpty())
                    continue;
                if (!item.equals(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                    continue;
                int take = Math.min(stack.getCount(), remaining);
                barrel.removeItem(slot, take);
                remaining -= take;
            }
        }
        return remaining == 0;
    }
}
