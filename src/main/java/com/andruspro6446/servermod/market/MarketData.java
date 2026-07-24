package com.andruspro6446.servermod.market;

import com.andruspro6446.servermod.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

// Tracks every tradeable item's base price and its live, fluctuating trading price - like a simple crypto market
// where each item is its own "coin". Buying pushes the price up, selling pushes it down, and prices slowly drift
// back toward the admin-set base price over time. All access must happen on the main server thread.
public class MarketData extends SavedData
{
    private static final String DATA_NAME = "servermod_market";

    private final Map<ResourceLocation, MarketEntry> entries = new LinkedHashMap<>();

    public static MarketData create()
    {
        return new MarketData();
    }

    public static MarketData load(CompoundTag tag)
    {
        MarketData data = new MarketData();
        CompoundTag entriesTag = tag.getCompound("entries");
        for (String key : entriesTag.getAllKeys())
        {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null)
                continue;

            CompoundTag entryTag = entriesTag.getCompound(key);
            data.entries.put(id, new MarketEntry(entryTag.getDouble("base"), entryTag.getDouble("current")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        CompoundTag entriesTag = new CompoundTag();
        entries.forEach((id, entry) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putDouble("base", entry.basePrice);
            entryTag.putDouble("current", entry.currentPrice);
            entriesTag.put(id.toString(), entryTag);
        });
        tag.put("entries", entriesTag);
        return tag;
    }

    public static MarketData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(MarketData::load, MarketData::create, DATA_NAME);
    }

    // Populates the market from the config seed list, but only for items that aren't already tracked,
    // so restarting the server never overwrites prices the admin panel has already changed.
    public void seedIfAbsent(ResourceLocation id, double basePrice)
    {
        entries.computeIfAbsent(id, key -> {
            setDirty();
            return new MarketEntry(basePrice, basePrice);
        });
    }

    public MarketEntry getEntry(ResourceLocation id)
    {
        return entries.get(id);
    }

    public Map<ResourceLocation, MarketEntry> getAllEntries()
    {
        return new LinkedHashMap<>(entries);
    }

    // Sets (or creates) an item's base price. Existing current prices are left alone so the market can keep
    // drifting toward the new base over time instead of snapping to it immediately.
    public void setBasePrice(ResourceLocation id, double basePrice)
    {
        MarketEntry entry = entries.get(id);
        if (entry == null)
            entries.put(id, new MarketEntry(basePrice, basePrice));
        else
            entry.basePrice = basePrice;
        setDirty();
    }

    // Removes an item from the market entirely. Returns false if it wasn't tracked.
    public boolean removeEntry(ResourceLocation id)
    {
        boolean removed = entries.remove(id) != null;
        if (removed)
            setDirty();
        return removed;
    }

    public void applyBuy(ResourceLocation id, int quantity)
    {
        applyImpact(id, quantity, true);
    }

    public void applySell(ResourceLocation id, int quantity)
    {
        applyImpact(id, quantity, false);
    }

    private void applyImpact(ResourceLocation id, int quantity, boolean buy)
    {
        MarketEntry entry = entries.get(id);
        if (entry == null || quantity <= 0)
            return;

        double delta = Config.marketImpactRate * Math.sqrt(quantity);
        double multiplier = buy ? (1.0 + delta) : Math.max(0.01, 1.0 - delta);
        entry.currentPrice = clamp(entry.currentPrice * multiplier, entry.basePrice);
        setDirty();
    }

    // Nudges every item's current price back toward its base price. Called periodically from the server tick.
    public void tickReversion()
    {
        boolean changed = false;
        for (MarketEntry entry : entries.values())
        {
            double next = entry.currentPrice + (entry.basePrice - entry.currentPrice) * Config.marketReversionRate;
            if (next != entry.currentPrice)
            {
                entry.currentPrice = clamp(next, entry.basePrice);
                changed = true;
            }
        }
        if (changed)
            setDirty();
    }

    private static double clamp(double price, double basePrice)
    {
        double min = basePrice * Config.marketMinMultiplier;
        double max = basePrice * Config.marketMaxMultiplier;
        return Math.min(max, Math.max(min, price));
    }
}
