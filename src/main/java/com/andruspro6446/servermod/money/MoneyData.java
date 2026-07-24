package com.andruspro6446.servermod.money;

import com.andruspro6446.servermod.network.NetworkHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Persists every player's money balance (in cents, see Money) in the overworld's saved data, so it survives
// server restarts. All access must happen on the main server thread (see web.MainThreadExecutor for HTTP handlers).
public class MoneyData extends SavedData
{
    private static final String DATA_NAME = "servermod_money";

    private final Map<UUID, Integer> balances = new HashMap<>();

    public static MoneyData create()
    {
        return new MoneyData();
    }

    public static MoneyData load(CompoundTag tag)
    {
        MoneyData data = new MoneyData();
        // Saves from before balances were cents-denominated have no "cents" marker; their stored ints were
        // whole dollars, so scale them up once here. save() always writes the marker, so this only ever fires
        // for pre-upgrade data - after that the values are already cents and setDirty() below is skipped.
        boolean legacyDollars = !tag.getBoolean("cents");
        CompoundTag balancesTag = tag.getCompound("balances");
        for (String key : balancesTag.getAllKeys())
        {
            int stored = balancesTag.getInt(key);
            data.balances.put(UUID.fromString(key), legacyDollars ? stored * 100 : stored);
        }
        if (legacyDollars && !balancesTag.getAllKeys().isEmpty())
            data.setDirty();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        CompoundTag balancesTag = new CompoundTag();
        balances.forEach((uuid, cents) -> balancesTag.putInt(uuid.toString(), cents));
        tag.put("balances", balancesTag);
        tag.putBoolean("cents", true);
        return tag;
    }

    public static MoneyData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(MoneyData::load, MoneyData::create, DATA_NAME);
    }

    public int getMoney(UUID playerId)
    {
        return balances.getOrDefault(playerId, 0);
    }

    // Adds (or subtracts, if negative) money for a player, persists it, and syncs the new balance to their client if online.
    // Balances never go below zero.
    public int addMoney(MinecraftServer server, UUID playerId, int amount)
    {
        int newBalance = Math.max(0, getMoney(playerId) + amount);
        balances.put(playerId, newBalance);
        setDirty();
        syncIfOnline(server, playerId, newBalance);
        return newBalance;
    }

    public void setMoney(MinecraftServer server, UUID playerId, int amount)
    {
        int clamped = Math.max(0, amount);
        balances.put(playerId, clamped);
        setDirty();
        syncIfOnline(server, playerId, clamped);
    }

    private void syncIfOnline(MinecraftServer server, UUID playerId, int amount)
    {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null)
            NetworkHandler.sendMoneyUpdate(player, amount);
    }
}
