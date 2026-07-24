package com.andruspro6446.servermod.block;

import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.market.MarketEntry;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.money.MoneyData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;

// Right-click with an item in your main hand to sell the whole stack for money at the current market price,
// same as anyone else who walks up to it can. Selling increases supply (price down), just like a real market.
// The full item list with live prices is on the web panel; this block is the in-person, immediate way to cash
// items in.
public class TraderBlock extends Block
{
    public TraderBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (hand != InteractionHand.MAIN_HAND)
            return InteractionResult.PASS;

        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        ServerPlayer serverPlayer = (ServerPlayer) player;
        MinecraftServer server = ((ServerLevel) level).getServer();
        ItemStack held = player.getItemInHand(hand);

        if (held.isEmpty())
        {
            int balance = MoneyData.get(server).getMoney(serverPlayer.getUUID());
            player.displayClientMessage(Component.literal(
                    "Your balance is " + Money.format(balance) + ". Hold an item and right-click to sell it."
            ).withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }
        return sell(server, serverPlayer, held);
    }

    private InteractionResult sell(MinecraftServer server, ServerPlayer player, ItemStack held)
    {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
        MarketEntry entry = MarketData.get(server).getEntry(id);
        if (entry == null)
        {
            player.displayClientMessage(Component.literal("You can't sell that here.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        int unitPriceCents = (int) Math.floor(entry.currentPrice * 100);
        if (unitPriceCents <= 0)
        {
            player.displayClientMessage(Component.literal("That item is worth nothing right now.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        int count = held.getCount();
        int totalCents = unitPriceCents * count;
        String name = held.getHoverName().getString();
        held.setCount(0);

        int newBalance = MoneyData.get(server).addMoney(server, player.getUUID(), totalCents);
        MarketData.get(server).applySell(id, count);

        player.displayClientMessage(Component.literal(
                "Sold " + count + "x " + name + " for " + Money.format(totalCents) + " (balance: " + Money.format(newBalance) + ")"
        ).withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.CONSUME;
    }
}
