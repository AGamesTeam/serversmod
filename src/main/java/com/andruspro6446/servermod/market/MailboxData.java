package com.andruspro6446.servermod.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import com.andruspro6446.servermod.util.InventoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Holds items bought from the web panel while a player is offline, so they can't be placed straight into an
// inventory that isn't loaded. Delivered automatically the next time the player logs in.
public class MailboxData extends SavedData
{
    private static final String DATA_NAME = "servermod_mailbox";

    public record PendingItem(ResourceLocation item, int count) {}

    private final Map<UUID, List<PendingItem>> pending = new HashMap<>();

    public static MailboxData create()
    {
        return new MailboxData();
    }

    public static MailboxData load(CompoundTag tag)
    {
        MailboxData data = new MailboxData();
        CompoundTag playersTag = tag.getCompound("players");
        for (String key : playersTag.getAllKeys())
        {
            List<PendingItem> items = new ArrayList<>();
            ListTag listTag = playersTag.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < listTag.size(); i++)
            {
                CompoundTag itemTag = listTag.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(itemTag.getString("item"));
                if (id != null)
                    items.add(new PendingItem(id, itemTag.getInt("count")));
            }
            if (!items.isEmpty())
                data.pending.put(UUID.fromString(key), items);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        CompoundTag playersTag = new CompoundTag();
        pending.forEach((uuid, items) -> {
            ListTag listTag = new ListTag();
            for (PendingItem item : items)
            {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putString("item", item.item().toString());
                itemTag.putInt("count", item.count());
                listTag.add(itemTag);
            }
            playersTag.put(uuid.toString(), listTag);
        });
        tag.put("players", playersTag);
        return tag;
    }

    public static MailboxData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(MailboxData::load, MailboxData::create, DATA_NAME);
    }

    public void addPending(UUID playerId, ResourceLocation itemId, int count)
    {
        pending.computeIfAbsent(playerId, key -> new ArrayList<>()).add(new PendingItem(itemId, count));
        setDirty();
    }

    public boolean hasPending(UUID playerId)
    {
        List<PendingItem> items = pending.get(playerId);
        return items != null && !items.isEmpty();
    }

    // Gives the player everything waiting for them and clears their mailbox. Call on login.
    public void deliver(ServerPlayer player)
    {
        List<PendingItem> items = pending.remove(player.getUUID());
        if (items == null || items.isEmpty())
            return;

        for (PendingItem pendingItem : items)
        {
            Item item = ForgeRegistries.ITEMS.getValue(pendingItem.item());
            if (item == null)
                continue;
            InventoryUtil.giveOrDrop(player, new ItemStack(item, pendingItem.count()));
        }
        setDirty();
    }
}
