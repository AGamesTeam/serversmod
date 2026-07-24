package com.andruspro6446.servermod.command;

import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.business.MissedPaymentPolicy;
import com.andruspro6446.servermod.business.QueuePos;
import com.andruspro6446.servermod.customer.CustomerNpcManager;
import com.andruspro6446.servermod.market.MailboxData;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.util.InventoryUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

// /money add|remove|set|get - op-only balance administration, works on offline players too.
// /trade money|item - lets any player give their own money/items to another player, online or offline.
// /business fee|policy|gracedays - op-only business rule administration (also editable from the admin web page).
// /business queue add|remove - a business owner designates/removes a customer NPC queue point at their position.
// /business npcaccept|npcdecline - clicked from the chat purchase dialogue (see customer.CustomerNpcManager),
// not meant to be typed manually.
public final class ModCommands
{
    private ModCommands()
    {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("money")
                .then(Commands.literal("get")
                        .executes(ModCommands::getOwnMoney)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .requires(source -> source.hasPermission(2))
                                .executes(ModCommands::getOtherMoney)))
                .then(Commands.literal("add")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> changeMoney(ctx, 1)))))
                .then(Commands.literal("remove")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> changeMoney(ctx, -1)))))
                .then(Commands.literal("set")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ModCommands::setMoney))))
        );

        dispatcher.register(Commands.literal("trade")
                .then(Commands.literal("money")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ModCommands::tradeMoney))))
                .then(Commands.literal("item")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ModCommands::tradeItem)))))
        );

        dispatcher.register(Commands.literal("business")
                .then(Commands.literal("fee")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("registration")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ModCommands::setRegistrationFee)))
                        .then(Commands.literal("weekly")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ModCommands::setWeeklyFee)))
                        .then(Commands.literal("barrel")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                        .executes(ModCommands::setSellBarrelFee))))
                .then(Commands.literal("policy")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("policy", StringArgumentType.word())
                                .executes(ModCommands::setMissedPaymentPolicy)))
                .then(Commands.literal("gracedays")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                .executes(ModCommands::setGraceDays)))
                .then(Commands.literal("queue")
                        .then(Commands.literal("add").executes(ModCommands::addQueuePoint))
                        .then(Commands.literal("remove").executes(ModCommands::removeQueuePoint)))
                .then(Commands.literal("npcaccept")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ModCommands::npcAccept)))
                .then(Commands.literal("npcdecline")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ModCommands::npcDecline)))
                .then(Commands.literal("npchaggle")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ModCommands::npcHaggleAccept)))
        );
    }

    private static int addQueuePoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        BusinessData data = BusinessData.get(server);
        Business business = data.getByOwner(player.getUUID());
        if (business == null)
        {
            ctx.getSource().sendFailure(Component.literal("You don't have a business.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Direction facing = player.getDirection();
        QueuePos queuePos = new QueuePos(player.level().dimension(), player.blockPosition(), facing);
        data.addQueuePoint(business, queuePos);
        ctx.getSource().sendSuccess(() -> Component.literal("Added a customer queue point here, facing "
                + facing.getSerializedName() + ". Customers will line up behind this spot."), true);
        return 1;
    }

    private static int removeQueuePoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        BusinessData data = BusinessData.get(server);
        Business business = data.getByOwner(player.getUUID());
        if (business == null)
        {
            ctx.getSource().sendFailure(Component.literal("You don't have a business.").withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean removed = data.removeQueuePointNear(business, player.level().dimension(), player.blockPosition());
        if (removed)
            ctx.getSource().sendSuccess(() -> Component.literal("Removed the nearest customer queue point."), true);
        else
            ctx.getSource().sendFailure(Component.literal("No queue point found near you.").withStyle(ChatFormatting.RED));
        return removed ? 1 : 0;
    }

    private static int npcAccept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID npcId = parseUuidArg(ctx);
        if (npcId == null)
        {
            ctx.getSource().sendFailure(Component.literal("Invalid customer id.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String message = CustomerNpcManager.resolveAccept(ctx.getSource().getServer(), player, npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int npcDecline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID npcId = parseUuidArg(ctx);
        if (npcId == null)
        {
            ctx.getSource().sendFailure(Component.literal("Invalid customer id.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String message = CustomerNpcManager.resolveDecline(ctx.getSource().getServer(), player, npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int npcHaggleAccept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID npcId = parseUuidArg(ctx);
        if (npcId == null)
        {
            ctx.getSource().sendFailure(Component.literal("Invalid customer id.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String message = CustomerNpcManager.resolveHaggleAccept(ctx.getSource().getServer(), player, npcId);
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static UUID parseUuidArg(CommandContext<CommandSourceStack> ctx)
    {
        try
        {
            return UUID.fromString(StringArgumentType.getString(ctx, "id"));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static int setRegistrationFee(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        int cents = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        MinecraftServer server = ctx.getSource().getServer();
        BusinessData.get(server).setRegistrationFeeCents(cents);
        ctx.getSource().sendSuccess(() -> Component.literal("Business registration fee set to " + Money.format(cents)), true);
        return cents;
    }

    private static int setWeeklyFee(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        int cents = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        MinecraftServer server = ctx.getSource().getServer();
        BusinessData.get(server).setWeeklyFeeCents(cents);
        ctx.getSource().sendSuccess(() -> Component.literal("Business weekly fee set to " + Money.format(cents)), true);
        return cents;
    }

    private static int setSellBarrelFee(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        int cents = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        MinecraftServer server = ctx.getSource().getServer();
        BusinessData.get(server).setSellBarrelDailyFeeCents(cents);
        ctx.getSource().sendSuccess(() -> Component.literal("Sell Barrel fee set to " + Money.format(cents) + " per barrel, per day"), true);
        return cents;
    }

    private static int setMissedPaymentPolicy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        String raw = StringArgumentType.getString(ctx, "policy");
        MissedPaymentPolicy policy;
        try
        {
            policy = MissedPaymentPolicy.valueOf(raw.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown policy \"" + raw + "\". Valid values: suspend, dissolve, grace_then_suspend.").withStyle(ChatFormatting.RED));
            return 0;
        }

        BusinessData.get(ctx.getSource().getServer()).setMissedPaymentPolicy(policy);
        ctx.getSource().sendSuccess(() -> Component.literal("Missed-payment policy set to " + policy), true);
        return 1;
    }

    private static int setGraceDays(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        int days = IntegerArgumentType.getInteger(ctx, "days");
        BusinessData.get(ctx.getSource().getServer()).setGraceDays(days);
        ctx.getSource().sendSuccess(() -> Component.literal("Business grace period set to " + days + " day" + (days == 1 ? "" : "s")), true);
        return days;
    }

    private static GameProfile firstProfile(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException
    {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, name);
        return profiles.iterator().next();
    }

    private static int getOwnMoney(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int balance = MoneyData.get(ctx.getSource().getServer()).getMoney(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("Your balance is " + Money.format(balance)), false);
        return balance;
    }

    private static int getOtherMoney(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        GameProfile target = firstProfile(ctx, "player");
        int balance = MoneyData.get(ctx.getSource().getServer()).getMoney(target.getId());
        ctx.getSource().sendSuccess(() -> Component.literal(target.getName() + "'s balance is " + Money.format(balance)), false);
        return balance;
    }

    private static int changeMoney(CommandContext<CommandSourceStack> ctx, int sign) throws CommandSyntaxException
    {
        GameProfile target = firstProfile(ctx, "player");
        int amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount")) * sign;
        MinecraftServer server = ctx.getSource().getServer();
        int newBalance = MoneyData.get(server).addMoney(server, target.getId(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal(
                (sign > 0 ? "Added " + Money.format(Math.abs(amount)) + " to " : "Removed " + Money.format(Math.abs(amount)) + " from ")
                        + target.getName() + " (new balance: " + Money.format(newBalance) + ")"
        ), true);
        return newBalance;
    }

    private static int setMoney(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        GameProfile target = firstProfile(ctx, "player");
        int amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        MinecraftServer server = ctx.getSource().getServer();
        MoneyData.get(server).setMoney(server, target.getId(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal("Set " + target.getName() + "'s balance to " + Money.format(amount)), true);
        return amount;
    }

    private static int tradeMoney(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        GameProfile target = firstProfile(ctx, "player");
        int amount = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        MinecraftServer server = ctx.getSource().getServer();

        if (target.getId().equals(sender.getUUID()))
        {
            ctx.getSource().sendFailure(Component.literal("You can't trade with yourself.").withStyle(ChatFormatting.RED));
            return 0;
        }

        MoneyData moneyData = MoneyData.get(server);
        if (moneyData.getMoney(sender.getUUID()) < amount)
        {
            ctx.getSource().sendFailure(Component.literal("You don't have " + Money.format(amount) + ".").withStyle(ChatFormatting.RED));
            return 0;
        }

        moneyData.addMoney(server, sender.getUUID(), -amount);
        moneyData.addMoney(server, target.getId(), amount);
        ctx.getSource().sendSuccess(() -> Component.literal("Sent " + Money.format(amount) + " to " + target.getName()), false);
        return 1;
    }

    private static int tradeItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        GameProfile target = firstProfile(ctx, "player");
        ResourceLocation itemId = ResourceLocationArgument.getId(ctx, "item");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        MinecraftServer server = ctx.getSource().getServer();

        if (target.getId().equals(sender.getUUID()))
        {
            ctx.getSource().sendFailure(Component.literal("You can't trade with yourself.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown item: " + itemId).withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!InventoryUtil.removeItem(sender.getInventory(), item, amount))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have " + amount + "x that item.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target.getId());
        if (targetPlayer != null)
            InventoryUtil.giveOrDrop(targetPlayer, new ItemStack(item, amount));
        else
            MailboxData.get(server).addPending(target.getId(), itemId, amount);

        ctx.getSource().sendSuccess(() -> Component.literal("Sent " + amount + "x " + itemId + " to " + target.getName()), false);
        return 1;
    }
}
