package com.andruspro6446.servermod.territory;

import com.andruspro6446.servermod.ServerMod;
import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.market.MarketEntry;
import com.andruspro6446.servermod.util.InventoryUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

// Enforces active Sentences: confines a jailed player to their cell's radius, tracks mining-quota progress
// as they break blocks (valued at the block's current market price - no fortune/silk-touch awareness, a
// deliberate approximation), and releases them - returning confiscated items and teleporting them back - once
// every assigned condition is met. tick() is called periodically from the server tick, not a Forge event;
// the two @SubscribeEvent methods below handle mining progress and re-applying/releasing a sentence on login.
@Mod.EventBusSubscriber(modid = ServerMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class JailListener
{
    private JailListener()
    {
    }

    public static void tick(MinecraftServer server)
    {
        TerritoryData data = TerritoryData.get(server);
        List<Sentence> sentences = data.getAllSentences();
        for (Sentence sentence : sentences)
        {
            ServerPlayer player = server.getPlayerList().getPlayer(sentence.playerId);

            if (!sentence.applied)
            {
                if (player == null)
                    continue;
                if (sentence.isComplete())
                {
                    data.removeSentence(sentence.playerId);
                    continue;
                }
                applySentenceNow(server, player, sentence, findCell(data, sentence));
                continue;
            }

            if (sentence.isComplete())
            {
                if (player != null)
                    releaseNow(server, data, player, sentence);
                continue;
            }

            if (player != null && sentence.jailCellId != null)
            {
                JailCell cell = findCell(data, sentence);
                if (cell != null && !withinRadius(player, cell))
                    teleportToCell(player, cell);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        MinecraftServer server = player.getServer();
        if (server == null)
            return;
        TerritoryData data = TerritoryData.get(server);
        Sentence sentence = data.getSentence(player.getUUID());
        if (sentence == null)
            return;

        if (sentence.isComplete())
        {
            if (sentence.applied)
                releaseNow(server, data, player, sentence);
            else
                data.removeSentence(player.getUUID());
            return;
        }

        if (!sentence.applied)
            applySentenceNow(server, player, sentence, findCell(data, sentence));
        else if (sentence.jailCellId != null)
        {
            JailCell cell = findCell(data, sentence);
            if (cell != null)
                teleportToCell(player, cell);
        }
    }

    @SubscribeEvent
    public static void onBlockBreakForQuota(BlockEvent.BreakEvent event)
    {
        if (event.isCanceled())
            return;
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;

        TerritoryData data = TerritoryData.get(level.getServer());
        Sentence sentence = data.getSentence(player.getUUID());
        if (sentence == null || sentence.miningQuotaCents == 0 || sentence.miningProgressCents >= sentence.miningQuotaCents)
            return;

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getState().getBlock().asItem());
        if (itemId == null)
            return;
        MarketEntry entry = MarketData.get(level.getServer()).getEntry(itemId);
        if (entry == null)
            return;

        sentence.miningProgressCents += (int) Math.floor(entry.currentPrice * 100);
        data.markSentenceDirty();
    }

    private static JailCell findCell(TerritoryData data, Sentence sentence)
    {
        if (sentence.jailCellId == null)
            return null;
        for (City city : data.getAllCities())
        {
            JailCell cell = city.findJailCell(sentence.jailCellId);
            if (cell != null)
                return cell;
        }
        for (Country country : data.getAllCountries())
        {
            JailCell cell = country.findJailCell(sentence.jailCellId);
            if (cell != null)
                return cell;
        }
        return null;
    }

    public static void applySentenceNow(MinecraftServer server, ServerPlayer player, Sentence sentence, JailCell cell)
    {
        sentence.returnDimension = player.level().dimension();
        sentence.returnX = player.getX();
        sentence.returnY = player.getY();
        sentence.returnZ = player.getZ();

        sentence.confiscatedItems.clear();
        for (ItemStack stack : player.getInventory().items)
            if (!stack.isEmpty())
                sentence.confiscatedItems.add(stack.copy());
        for (ItemStack stack : player.getInventory().offhand)
            if (!stack.isEmpty())
                sentence.confiscatedItems.add(stack.copy());
        player.getInventory().clearContent();

        if (cell != null)
            teleportToCell(player, cell);

        sentence.applied = true;
        TerritoryData.get(server).markSentenceDirty();
        player.displayClientMessage(Component.literal("You've been jailed.").withStyle(ChatFormatting.RED), false);
    }

    public static void releaseNow(MinecraftServer server, TerritoryData data, ServerPlayer player, Sentence sentence)
    {
        if (sentence.returnDimension != null)
        {
            ServerLevel returnLevel = server.getLevel(sentence.returnDimension);
            if (returnLevel != null)
                player.teleportTo(returnLevel, sentence.returnX, sentence.returnY, sentence.returnZ, player.getYRot(), player.getXRot());
        }
        for (ItemStack stack : sentence.confiscatedItems)
            InventoryUtil.giveOrDrop(player, stack);

        data.removeSentence(player.getUUID());
        player.displayClientMessage(Component.literal("You've been released.").withStyle(ChatFormatting.GREEN), false);
    }

    private static void teleportToCell(ServerPlayer player, JailCell cell)
    {
        ServerLevel level = player.getServer().getLevel(cell.dimension);
        if (level == null)
            return;
        player.teleportTo(level, cell.pos.getX() + 0.5, cell.pos.getY(), cell.pos.getZ() + 0.5, player.getYRot(), player.getXRot());
    }

    private static boolean withinRadius(ServerPlayer player, JailCell cell)
    {
        if (!player.level().dimension().equals(cell.dimension))
            return false;
        double dx = player.getX() - (cell.pos.getX() + 0.5);
        double dz = player.getZ() - (cell.pos.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) cell.radius * cell.radius;
    }
}
