package com.andruspro6446.servermod.command;

import com.andruspro6446.servermod.ServerMod;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.territory.BillingStatus;
import com.andruspro6446.servermod.territory.BorderTracker;
import com.andruspro6446.servermod.territory.City;
import com.andruspro6446.servermod.territory.ClaimPermission;
import com.andruspro6446.servermod.territory.ClaimRequests;
import com.andruspro6446.servermod.territory.ClaimShovelItem;
import com.andruspro6446.servermod.territory.ClaimType;
import com.andruspro6446.servermod.territory.Country;
import com.andruspro6446.servermod.territory.CountryLaw;
import com.andruspro6446.servermod.territory.Government;
import com.andruspro6446.servermod.territory.GovPermission;
import com.andruspro6446.servermod.territory.JailCell;
import com.andruspro6446.servermod.territory.JailListener;
import com.andruspro6446.servermod.territory.LandClaim;
import com.andruspro6446.servermod.territory.MissedPaymentPolicy;
import com.andruspro6446.servermod.territory.PendingClaim;
import com.andruspro6446.servermod.territory.PermissionMode;
import com.andruspro6446.servermod.territory.PublicSquare;
import com.andruspro6446.servermod.territory.Rect;
import com.andruspro6446.servermod.territory.Report;
import com.andruspro6446.servermod.territory.ReportStatus;
import com.andruspro6446.servermod.territory.Sentence;
import com.andruspro6446.servermod.territory.ShovelTier;
import com.andruspro6446.servermod.territory.TerritoryData;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// /land accept|decline - resolves a pending claim from its chat Accept/Decline click.
// /land name - names a held city/country shovel before you start selecting corners with it.
// /land buy - purchase a claim shovel of a given type/tier.
// /land city|country <name> rate|official - government management, permission-gated.
// /land admin - op-only: shovel tiers and the country->admin rate.
public final class TerritoryCommands
{
    private TerritoryCommands()
    {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        dispatcher.register(Commands.literal("land")
                .then(Commands.literal("accept")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> resolveClaim(ctx, true))))
                .then(Commands.literal("decline")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> resolveClaim(ctx, false))))
                .then(Commands.literal("border")
                        .then(Commands.literal("accept").executes(TerritoryCommands::borderAccept))
                        .then(Commands.literal("decline").executes(TerritoryCommands::borderDecline)))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(TerritoryCommands::nameShovel)))
                .then(Commands.literal("buy")
                        .then(Commands.literal("land").then(Commands.argument("tier", StringArgumentType.word())
                                .executes(ctx -> buyShovel(ctx, ClaimType.LAND))))
                        .then(Commands.literal("city").then(Commands.argument("tier", StringArgumentType.word())
                                .executes(ctx -> buyShovel(ctx, ClaimType.CITY))))
                        .then(Commands.literal("country").then(Commands.argument("tier", StringArgumentType.word())
                                .executes(ctx -> buyShovel(ctx, ClaimType.COUNTRY)))))
                .then(Commands.literal("status").executes(TerritoryCommands::status))
                .then(Commands.literal("reactivate").executes(TerritoryCommands::reactivateLandHere))
                .then(Commands.literal("claim")
                        .then(Commands.literal("rule")
                                .then(Commands.argument("permission", StringArgumentType.word())
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(TerritoryCommands::setClaimRule))))
                        .then(Commands.literal("trust")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .then(Commands.argument("permission", StringArgumentType.word())
                                                .executes(TerritoryCommands::trustPlayer))))
                        .then(Commands.literal("untrust")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(TerritoryCommands::untrustPlayer))))
                .then(Commands.literal("report")
                        .then(Commands.literal("resolve")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("fine", DoubleArgumentType.doubleArg(0))
                                                .then(Commands.argument("cell", StringArgumentType.word())
                                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                                                .then(Commands.argument("quota", DoubleArgumentType.doubleArg(0))
                                                                        .executes(TerritoryCommands::resolveReport)))))))
                        .then(Commands.literal("deny")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(TerritoryCommands::denyReport)))
                        .then(Commands.literal("city")
                                .then(Commands.argument("gov", StringArgumentType.word())
                                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                .then(Commands.argument("permission", StringArgumentType.word())
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(ctx -> submitReport(ctx, ClaimType.CITY)))))))
                        .then(Commands.literal("country")
                                .then(Commands.argument("gov", StringArgumentType.word())
                                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                .then(Commands.argument("permission", StringArgumentType.word())
                                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                                .executes(ctx -> submitReport(ctx, ClaimType.COUNTRY))))))))
                .then(Commands.literal("reports")
                        .then(Commands.literal("city").then(Commands.argument("gov", StringArgumentType.word())
                                .executes(ctx -> listReports(ctx, ClaimType.CITY))))
                        .then(Commands.literal("country").then(Commands.argument("gov", StringArgumentType.word())
                                .executes(ctx -> listReports(ctx, ClaimType.COUNTRY)))))
                .then(Commands.literal("release")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(TerritoryCommands::pardon)))
                .then(Commands.literal("city")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.literal("rate")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                                .executes(ctx -> setRate(ctx, ClaimType.CITY))))
                                .then(Commands.literal("reactivate").executes(ctx -> reactivateGovernment(ctx, ClaimType.CITY)))
                                .then(Commands.literal("victimshare")
                                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> setVictimShare(ctx, ClaimType.CITY))))
                                .then(Commands.literal("salestax")
                                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> setSalesTax(ctx, ClaimType.CITY))))
                                .then(Commands.literal("jail")
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("cell", StringArgumentType.word())
                                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                                .then(Commands.argument("maxCapacity", IntegerArgumentType.integer(1))
                                                                        .executes(ctx -> addJailCell(ctx, ClaimType.CITY))))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("cell", StringArgumentType.word())
                                                        .executes(ctx -> removeJailCell(ctx, ClaimType.CITY)))))
                                .then(Commands.literal("officialaccess")
                                        .then(Commands.argument("permission", StringArgumentType.word())
                                                .then(Commands.argument("access", StringArgumentType.word())
                                                        .executes(TerritoryCommands::setCityOfficialAccess))))
                                .then(Commands.literal("official")
                                        .then(Commands.literal("grant")
                                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                        .then(Commands.argument("permission", StringArgumentType.word())
                                                                .executes(ctx -> grantOfficial(ctx, ClaimType.CITY)))))
                                        .then(Commands.literal("revoke")
                                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                        .executes(ctx -> revokeOfficial(ctx, ClaimType.CITY)))))
                                .then(Commands.literal("square")
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> addPublicSquare(ctx, ClaimType.CITY))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> removePublicSquare(ctx, ClaimType.CITY))))
                                        .then(Commands.literal("list").executes(ctx -> listPublicSquares(ctx, ClaimType.CITY))))))
                .then(Commands.literal("country")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.literal("rate")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                                .executes(ctx -> setRate(ctx, ClaimType.COUNTRY))))
                                .then(Commands.literal("reactivate").executes(ctx -> reactivateGovernment(ctx, ClaimType.COUNTRY)))
                                .then(Commands.literal("victimshare")
                                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> setVictimShare(ctx, ClaimType.COUNTRY))))
                                .then(Commands.literal("salestax")
                                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                                .executes(ctx -> setSalesTax(ctx, ClaimType.COUNTRY))))
                                .then(Commands.literal("jail")
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("cell", StringArgumentType.word())
                                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                                .then(Commands.argument("maxCapacity", IntegerArgumentType.integer(1))
                                                                        .executes(ctx -> addJailCell(ctx, ClaimType.COUNTRY))))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("cell", StringArgumentType.word())
                                                        .executes(ctx -> removeJailCell(ctx, ClaimType.COUNTRY)))))
                                .then(Commands.literal("official")
                                        .then(Commands.literal("grant")
                                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                        .then(Commands.argument("permission", StringArgumentType.word())
                                                                .executes(ctx -> grantOfficial(ctx, ClaimType.COUNTRY)))))
                                        .then(Commands.literal("revoke")
                                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                        .executes(ctx -> revokeOfficial(ctx, ClaimType.COUNTRY)))))
                                .then(Commands.literal("law")
                                        .then(Commands.literal("enable")
                                                .then(Commands.argument("law", StringArgumentType.word())
                                                        .then(Commands.argument("enabled", StringArgumentType.word())
                                                                .executes(TerritoryCommands::setLawEnabled))))
                                        .then(Commands.literal("configure")
                                                .then(Commands.argument("law", StringArgumentType.word())
                                                        .then(Commands.argument("fine", DoubleArgumentType.doubleArg(0))
                                                                .then(Commands.argument("cell", StringArgumentType.word())
                                                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                                                                .then(Commands.argument("quota", DoubleArgumentType.doubleArg(0))
                                                                                        .executes(TerritoryCommands::configureLaw)))))))
                                        .then(Commands.literal("maxy")
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .executes(TerritoryCommands::setLawMaxY)))
                                        .then(Commands.literal("zone")
                                                .then(Commands.literal("add")
                                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                        .executes(TerritoryCommands::addLawZone))))))
                                                .then(Commands.literal("remove")
                                                        .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                                                .executes(TerritoryCommands::removeLawZone)))))
                                .then(Commands.literal("permit")
                                        .then(Commands.literal("grant")
                                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                        .then(Commands.argument("law", StringArgumentType.word())
                                                                .executes(TerritoryCommands::grantPermit))))
                                        .then(Commands.literal("revoke")
                                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                                        .then(Commands.argument("law", StringArgumentType.word())
                                                                .executes(TerritoryCommands::revokePermit)))))
                                .then(Commands.literal("square")
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> addPublicSquare(ctx, ClaimType.COUNTRY))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> removePublicSquare(ctx, ClaimType.COUNTRY))))
                                        .then(Commands.literal("list").executes(ctx -> listPublicSquares(ctx, ClaimType.COUNTRY))))))
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("tier")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0))
                                                                .then(Commands.argument("maxCharges", IntegerArgumentType.integer(1))
                                                                        .then(Commands.argument("maxArea", IntegerArgumentType.integer(1))
                                                                                .executes(TerritoryCommands::addTier)))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .executes(TerritoryCommands::removeTier)))))
                        .then(Commands.literal("rate")
                                .then(Commands.literal("country")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                                                .executes(TerritoryCommands::setAdminCountryRate))))
                        .then(Commands.literal("policy")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .then(Commands.argument("policy", StringArgumentType.word())
                                                .executes(TerritoryCommands::setPolicy))))
                        .then(Commands.literal("gracedays")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                                .executes(TerritoryCommands::setGraceDays)))))
        );
    }

    // Returns null (rather than throwing) on an unrecognized type name, so callers can report it as a normal
    // command failure instead of a generic error.
    private static ClaimType parseType(String raw)
    {
        try
        {
            return ClaimType.valueOf(raw.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static int resolveClaim(CommandContext<CommandSourceStack> ctx, boolean accept) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String requestId = StringArgumentType.getString(ctx, "id");
        PendingClaim pending = ClaimRequests.get(requestId);
        if (pending == null || !pending.playerId.equals(player.getUUID()))
        {
            ctx.getSource().sendFailure(Component.literal("That claim request doesn't exist or has expired.").withStyle(ChatFormatting.RED));
            return 0;
        }
        ClaimRequests.remove(requestId);

        if (!accept)
        {
            ctx.getSource().sendSuccess(() -> Component.literal("Claim declined."), false);
            return 1;
        }

        MinecraftServer server = ctx.getSource().getServer();
        TerritoryData data = TerritoryData.get(server);

        if (pending.type != ClaimType.LAND && nameTaken(data, pending.type, pending.name))
        {
            ctx.getSource().sendFailure(Component.literal("A " + pending.type.name().toLowerCase(Locale.ROOT)
                    + " named \"" + pending.name + "\" already exists.").withStyle(ChatFormatting.RED));
            return 0;
        }

        long blockCount = pending.blockCount();
        switch (pending.type)
        {
            case LAND -> {
                data.finalizeLandClaim(player.getUUID(), pending.dimension, pending.moldedPieces);
                ctx.getSource().sendSuccess(() -> Component.literal("Claimed " + blockCount + " blocks.").withStyle(ChatFormatting.GREEN), false);
            }
            case CITY -> {
                City city = data.finalizeCity(player.getUUID(), pending.name, pending.dimension, pending.moldedPieces);
                ctx.getSource().sendSuccess(() -> Component.literal("Founded city \"" + city.name + "\" (" + blockCount + " blocks).").withStyle(ChatFormatting.GREEN), false);
            }
            case COUNTRY -> {
                Country country = data.finalizeCountry(player.getUUID(), pending.name, pending.dimension, pending.moldedPieces);
                ctx.getSource().sendSuccess(() -> Component.literal("Founded country \"" + country.name + "\" (" + blockCount + " blocks).").withStyle(ChatFormatting.GREEN), false);
            }
        }
        return 1;
    }

    private static boolean nameTaken(TerritoryData data, ClaimType type, String name)
    {
        return type == ClaimType.CITY ? data.findCityByName(name) != null : data.findCountryByName(name) != null;
    }

    private static int nameShovel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ClaimShovelItem shovel) || shovel.getClaimType() == ClaimType.LAND)
        {
            ctx.getSource().sendFailure(Component.literal("Hold a city or country claim shovel first.").withStyle(ChatFormatting.RED));
            return 0;
        }

        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());
        if (nameTaken(data, shovel.getClaimType(), name))
        {
            ctx.getSource().sendFailure(Component.literal("A " + shovel.getClaimType().name().toLowerCase(Locale.ROOT)
                    + " named \"" + name + "\" already exists.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ClaimShovelItem.setName(held, name);
        ctx.getSource().sendSuccess(() -> Component.literal("Shovel will found \"" + name + "\" when you finish selecting corners."), false);
        return 1;
    }

    private static Item shovelItemFor(ClaimType type)
    {
        return switch (type)
        {
            case LAND -> ServerMod.LAND_CLAIM_SHOVEL.get();
            case CITY -> ServerMod.CITY_CLAIM_SHOVEL.get();
            case COUNTRY -> ServerMod.COUNTRY_CLAIM_SHOVEL.get();
        };
    }

    private static int buyShovel(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String tierName = StringArgumentType.getString(ctx, "tier");
        MinecraftServer server = ctx.getSource().getServer();
        TerritoryData data = TerritoryData.get(server);

        ShovelTier tier = data.findTierByName(type, tierName);
        if (tier == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " tier \"" + tierName + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }

        MoneyData moneyData = MoneyData.get(server);
        int balance = moneyData.getMoney(player.getUUID());
        if (balance < tier.priceCents)
        {
            ctx.getSource().sendFailure(Component.literal("You need " + Money.format(tier.priceCents) + " but only have " + Money.format(balance) + ".").withStyle(ChatFormatting.RED));
            return 0;
        }

        moneyData.addMoney(server, player.getUUID(), -tier.priceCents);
        ItemStack stack = new ItemStack(shovelItemFor(type));
        ClaimShovelItem.initialize(stack, tier);
        InventoryUtil.giveOrDrop(player, stack);

        ctx.getSource().sendSuccess(() -> Component.literal("Bought a " + tier.name + " " + type.name().toLowerCase(Locale.ROOT)
                + " claim shovel (" + tier.maxCharges + " use" + (tier.maxCharges == 1 ? "" : "s") + ") for " + Money.format(tier.priceCents) + "."), false);
        return 1;
    }

    private static Government findGovernment(TerritoryData data, ClaimType type, String name)
    {
        return type == ClaimType.CITY ? data.findCityByName(name) : data.findCountryByName(name);
    }

    private static int setRate(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        int cents = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_RATES))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to set " + government.name + "'s rate.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (type == ClaimType.CITY)
            data.setCityRate((City) government, cents);
        else
            data.setCountryRate((Country) government, cents);

        ctx.getSource().sendSuccess(() -> Component.literal(government.name + "'s rate set to " + Money.format(cents) + "/block/week."), true);
        return 1;
    }

    // A business with a Sell Barrel anywhere inside this government's territory pays this percent of every
    // sale straight to its treasury - see business.SalesTax. Same permission as the land-claim/city rate,
    // since it's the same kind of economic lever.
    private static int setSalesTax(CommandContext<CommandSourceStack> ctx, ClaimType govType) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, govType, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_RATES))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to set " + government.name + "'s sales tax.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int percent = IntegerArgumentType.getInteger(ctx, "percent");
        data.setSalesTaxRate(government, percent);
        ctx.getSource().sendSuccess(() -> Component.literal(government.name + "'s sales tax set to " + percent + "% of every business transaction inside its territory."), true);
        return 1;
    }

    // Public squares are set at the founder/official's current position - like queue points and land claims,
    // this is something you have to physically be somewhere to designate, so it's a command rather than a web
    // form (which has no way to know where the player is standing in the world).
    private static int addPublicSquare(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_COMMERCE))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to set up public squares for " + government.name + ".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (radius > data.publicSquareMaxRadius())
        {
            ctx.getSource().sendFailure(Component.literal("Radius can't exceed " + data.publicSquareMaxRadius() + ".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (government.balanceCents < data.publicSquareCostCents())
        {
            ctx.getSource().sendFailure(Component.literal(government.name + "'s treasury doesn't have " + Money.format(data.publicSquareCostCents()) + ".").withStyle(ChatFormatting.RED));
            return 0;
        }

        government.balanceCents -= data.publicSquareCostCents();
        data.addPublicSquare(government, new PublicSquare(player.serverLevel().dimension(), player.blockPosition(), radius));
        ctx.getSource().sendSuccess(() -> Component.literal("Founded a public square for " + government.name + " here (radius " + radius
                + ") for " + Money.format(data.publicSquareCostCents()) + " - any business with a queue point inside it is now discoverable."), true);
        return 1;
    }

    private static int removePublicSquare(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        int index = IntegerArgumentType.getInteger(ctx, "index");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_COMMERCE))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s public squares.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!data.removePublicSquare(government, index))
        {
            ctx.getSource().sendFailure(Component.literal("No public square at index " + index + ".").withStyle(ChatFormatting.RED));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Removed public square " + index + " from " + government.name + "."), true);
        return 1;
    }

    private static int listPublicSquares(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_COMMERCE))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to view " + government.name + "'s public squares.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (government.publicSquares.isEmpty())
        {
            ctx.getSource().sendSuccess(() -> Component.literal(government.name + " has no public squares."), false);
            return 1;
        }
        for (int i = 0; i < government.publicSquares.size(); i++)
        {
            PublicSquare square = government.publicSquares.get(i);
            int index = i;
            ctx.getSource().sendSuccess(() -> Component.literal(index + ": " + square.pos.toShortString()
                    + " in " + square.dimension.location() + ", radius " + square.radius), false);
        }
        return 1;
    }

    private static GameProfile firstProfile(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException
    {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, name);
        return profiles.iterator().next();
    }

    private static int grantOfficial(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        GameProfile target = firstProfile(ctx, "player");
        String rawPermission = StringArgumentType.getString(ctx, "permission");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_OFFICIALS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s officials.").withStyle(ChatFormatting.RED));
            return 0;
        }

        GovPermission permission;
        try
        {
            permission = GovPermission.valueOf(rawPermission.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown permission \"" + rawPermission + "\". Valid values: manage_laws, manage_jails, "
                    + "manage_permits, manage_rates, manage_officials, manage_land_rules.").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.grantOfficial(government, target.getId(), permission);
        ctx.getSource().sendSuccess(() -> Component.literal("Granted " + target.getName() + " " + permission + " in " + government.name + "."), true);
        return 1;
    }

    private static int revokeOfficial(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        GameProfile target = firstProfile(ctx, "player");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_OFFICIALS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s officials.").withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean removed = data.removeOfficial(government, target.getId());
        ctx.getSource().sendSuccess(() -> Component.literal(removed
                ? "Removed " + target.getName() + " as an official of " + government.name + "."
                : target.getName() + " wasn't an official of " + government.name + "."), true);
        return 1;
    }

    private static int addTier(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ClaimType type = parseType(StringArgumentType.getString(ctx, "type"));
        if (type == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown claim type - use land, city, or country.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        int priceCents = Money.toCents(DoubleArgumentType.getDouble(ctx, "price"));
        int maxCharges = IntegerArgumentType.getInteger(ctx, "maxCharges");
        long maxArea = IntegerArgumentType.getInteger(ctx, "maxArea");

        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());
        if (data.findTierByName(type, name) != null)
        {
            ctx.getSource().sendFailure(Component.literal("A " + type.name().toLowerCase(Locale.ROOT) + " tier named \"" + name + "\" already exists.").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.addTier(type, name, priceCents, maxCharges, maxArea);
        ctx.getSource().sendSuccess(() -> Component.literal("Added " + type.name().toLowerCase(Locale.ROOT) + " tier \"" + name + "\": "
                + Money.format(priceCents) + ", " + maxCharges + " use(s), up to " + maxArea + " blocks."), true);
        return 1;
    }

    private static int removeTier(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ClaimType type = parseType(StringArgumentType.getString(ctx, "type"));
        if (type == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown claim type - use land, city, or country.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        ShovelTier tier = data.findTierByName(type, name);
        if (tier == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown tier \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.removeTier(type, tier.id);
        ctx.getSource().sendSuccess(() -> Component.literal("Removed tier \"" + name + "\"."), true);
        return 1;
    }

    private static int setAdminCountryRate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        int cents = Money.toCents(DoubleArgumentType.getDouble(ctx, "amount"));
        TerritoryData.get(ctx.getSource().getServer()).setAdminCountryRatePerBlockCents(cents);
        ctx.getSource().sendSuccess(() -> Component.literal("Country -> admin rate set to " + Money.format(cents) + "/block/week."), true);
        return 1;
    }

    private static int setPolicy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ClaimType type = parseType(StringArgumentType.getString(ctx, "type"));
        if (type == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown claim type - use land, city, or country.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String rawPolicy = StringArgumentType.getString(ctx, "policy");
        MissedPaymentPolicy policy;
        try
        {
            policy = MissedPaymentPolicy.valueOf(rawPolicy.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown policy \"" + rawPolicy
                    + "\". Valid values: suspend, dissolve, grace_then_suspend.").withStyle(ChatFormatting.RED));
            return 0;
        }

        TerritoryData.get(ctx.getSource().getServer()).setPolicy(type, policy);
        ctx.getSource().sendSuccess(() -> Component.literal(type.name().toLowerCase(Locale.ROOT) + " missed-payment policy set to " + policy), true);
        return 1;
    }

    private static int setGraceDays(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ClaimType type = parseType(StringArgumentType.getString(ctx, "type"));
        if (type == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown claim type - use land, city, or country.").withStyle(ChatFormatting.RED));
            return 0;
        }
        int days = IntegerArgumentType.getInteger(ctx, "days");
        TerritoryData.get(ctx.getSource().getServer()).setGraceDays(type, days);
        ctx.getSource().sendSuccess(() -> Component.literal(type.name().toLowerCase(Locale.ROOT) + " grace period set to " + days + " day" + (days == 1 ? "" : "s")), true);
        return 1;
    }

    private static int reactivateLandHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        TerritoryData data = TerritoryData.get(server);

        LandClaim claim = findOwnLandClaimHere(ctx, data, player);
        if (claim == null)
            return 0;
        if (claim.billing.status == BillingStatus.ACTIVE)
        {
            ctx.getSource().sendFailure(Component.literal("This claim isn't suspended.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!data.reactivateLandClaim(server, claim))
        {
            ctx.getSource().sendFailure(Component.literal("You can't afford the rent due to reactivate this claim.").withStyle(ChatFormatting.RED));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Claim reactivated."), false);
        return 1;
    }

    // Every /land claim ... command acts on whichever land claim the player is currently standing on, and
    // requires them to be its owner (not just trusted or a city official).
    private static LandClaim findOwnLandClaimHere(CommandContext<CommandSourceStack> ctx, TerritoryData data, ServerPlayer player)
    {
        BlockPos pos = player.blockPosition();
        LandClaim claim = data.findLandClaimAt(player.level().dimension(), pos.getX(), pos.getZ());
        if (claim == null || !claim.ownerId.equals(player.getUUID()))
        {
            ctx.getSource().sendFailure(Component.literal("You're not standing on land you own.").withStyle(ChatFormatting.RED));
            return null;
        }
        return claim;
    }

    private static ClaimPermission parsePermission(CommandContext<CommandSourceStack> ctx, String argName) throws CommandSyntaxException
    {
        String raw = StringArgumentType.getString(ctx, argName);
        try
        {
            return ClaimPermission.valueOf(raw.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown permission \"" + raw + "\". Valid values: build, container_open, "
                    + "container_take, interact, pvp, mob_damage.").withStyle(ChatFormatting.RED));
            return null;
        }
    }

    private static int setClaimRule(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());
        LandClaim claim = findOwnLandClaimHere(ctx, data, player);
        if (claim == null)
            return 0;

        ClaimPermission permission = parsePermission(ctx, "permission");
        if (permission == null)
            return 0;

        String rawMode = StringArgumentType.getString(ctx, "mode");
        PermissionMode mode;
        try
        {
            mode = PermissionMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown mode \"" + rawMode + "\". Valid values: allowed, blocked, reportable.").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.setRule(claim, permission, mode);
        ctx.getSource().sendSuccess(() -> Component.literal(permission + " set to " + mode + " on this claim."), false);
        return 1;
    }

    private static int trustPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());
        LandClaim claim = findOwnLandClaimHere(ctx, data, player);
        if (claim == null)
            return 0;

        GameProfile target = firstProfile(ctx, "player");
        ClaimPermission permission = parsePermission(ctx, "permission");
        if (permission == null)
            return 0;

        data.trustPlayer(claim, target.getId(), permission);
        ctx.getSource().sendSuccess(() -> Component.literal("Trusted " + target.getName() + " with " + permission + " on this claim."), false);
        return 1;
    }

    private static int untrustPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());
        LandClaim claim = findOwnLandClaimHere(ctx, data, player);
        if (claim == null)
            return 0;

        GameProfile target = firstProfile(ctx, "player");
        boolean removed = data.untrustPlayer(claim, target.getId());
        ctx.getSource().sendSuccess(() -> Component.literal(removed
                ? "Removed " + target.getName() + "'s trust on this claim."
                : target.getName() + " wasn't trusted on this claim."), false);
        return 1;
    }

    private static int setCityOfficialAccess(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        City city = data.findCityByName(name);
        if (city == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown city \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!city.hasPermission(player.getUUID(), GovPermission.MANAGE_LAND_RULES))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + city.name + "'s land rules.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ClaimPermission permission = parsePermission(ctx, "permission");
        if (permission == null)
            return 0;

        String rawAccess = StringArgumentType.getString(ctx, "access");
        boolean access = Boolean.parseBoolean(rawAccess);

        data.setCityOfficialAccess(city, permission, access);
        ctx.getSource().sendSuccess(() -> Component.literal(city.name + " officials " + (access ? "now have " : "no longer have ")
                + permission + " on land claims within the city."), true);
        return 1;
    }

    private static int reactivateGovernment(CommandContext<CommandSourceStack> ctx, ClaimType type) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, type, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + type.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_RATES))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s finances.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (government.billing.status == BillingStatus.ACTIVE)
        {
            ctx.getSource().sendFailure(Component.literal(government.name + " isn't suspended.").withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean success = type == ClaimType.CITY ? data.reactivateCity((City) government) : data.reactivateCountry((Country) government);
        if (!success)
        {
            ctx.getSource().sendFailure(Component.literal(government.name + " can't afford the rent due to reactivate.").withStyle(ChatFormatting.RED));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(government.name + " reactivated."), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        List<LandClaim> ownClaims = data.getLandClaimsOwnedBy(player.getUUID());
        long totalBlocks = ownClaims.stream().mapToLong(c -> c.region.blockCount()).sum();
        long suspended = ownClaims.stream().filter(c -> c.billing.status == BillingStatus.SUSPENDED).count();

        ctx.getSource().sendSuccess(() -> Component.literal("You own " + ownClaims.size() + " land claim(s), " + totalBlocks
                + " blocks total" + (suspended > 0 ? " (" + suspended + " suspended)" : "") + "."), false);

        List<City> ownCities = data.getAllCities().stream().filter(c -> c.hasPermission(player.getUUID(), GovPermission.MANAGE_RATES)).toList();
        for (City city : ownCities)
            ctx.getSource().sendSuccess(() -> Component.literal("City \"" + city.name + "\": " + Money.format(city.balanceCents)
                    + " treasury, status " + city.billing.status), false);

        List<Country> ownCountries = data.getAllCountries().stream().filter(c -> c.hasPermission(player.getUUID(), GovPermission.MANAGE_RATES)).toList();
        for (Country country : ownCountries)
            ctx.getSource().sendSuccess(() -> Component.literal("Country \"" + country.name + "\": " + Money.format(country.balanceCents)
                    + " treasury, status " + country.billing.status), false);

        return 1;
    }

    private static int submitReport(CommandContext<CommandSourceStack> ctx, ClaimType govType) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        LandClaim claim = findOwnLandClaimHere(ctx, data, player);
        if (claim == null)
            return 0;

        String govName = StringArgumentType.getString(ctx, "gov");
        Government government = findGovernment(data, govType, govName);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + govName + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }

        boolean hasJurisdiction = govType == ClaimType.CITY
                ? data.citiesOverlapping(claim.region).contains(government)
                : government.equals(data.countryOverlapping(claim.region));
        if (!hasJurisdiction)
        {
            ctx.getSource().sendFailure(Component.literal(government.name + " has no jurisdiction over this claim.").withStyle(ChatFormatting.RED));
            return 0;
        }

        GameProfile target = firstProfile(ctx, "player");
        ClaimPermission permission = parsePermission(ctx, "permission");
        if (permission == null)
            return 0;
        if (claim.rules.get(permission) != PermissionMode.REPORTABLE)
        {
            ctx.getSource().sendFailure(Component.literal(permission + " isn't set to reportable on this claim.").withStyle(ChatFormatting.RED));
            return 0;
        }

        String reason = StringArgumentType.getString(ctx, "reason");
        Report report = data.submitReport(claim, player.getUUID(), target.getId(), govType, government, permission, reason);
        ctx.getSource().sendSuccess(() -> Component.literal("Report filed with " + government.name + " (id " + report.id + ")."), false);
        return 1;
    }

    private static int listReports(CommandContext<CommandSourceStack> ctx, ClaimType govType) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String govName = StringArgumentType.getString(ctx, "gov");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, govType, govName);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + govName + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_JAILS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to review " + government.name + "'s reports.").withStyle(ChatFormatting.RED));
            return 0;
        }

        List<Report> pending = data.getPendingReportsFor(government);
        if (pending.isEmpty())
        {
            ctx.getSource().sendSuccess(() -> Component.literal("No pending reports for " + government.name + "."), false);
            return 1;
        }
        for (Report report : pending)
        {
            ctx.getSource().sendSuccess(() -> Component.literal(report.id + ": " + report.permission + " - \"" + report.reason + "\""), false);
        }
        return pending.size();
    }

    private static int denyReport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        UUID reportId = parseUuid(ctx, "id");
        if (reportId == null)
            return 0;
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Report report = data.getReport(reportId);
        if (report == null || report.status != ReportStatus.PENDING)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown or already-resolved report.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Government government = report.governmentType == ClaimType.CITY ? data.getCity(report.governmentId) : data.getCountry(report.governmentId);
        if (government == null || !government.hasPermission(player.getUUID(), GovPermission.MANAGE_JAILS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to review this report.").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.denyReport(report);
        ctx.getSource().sendSuccess(() -> Component.literal("Report denied."), true);
        return 1;
    }

    private static int resolveReport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        UUID reportId = parseUuid(ctx, "id");
        if (reportId == null)
            return 0;
        MinecraftServer server = ctx.getSource().getServer();
        TerritoryData data = TerritoryData.get(server);

        Report report = data.getReport(reportId);
        if (report == null || report.status != ReportStatus.PENDING)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown or already-resolved report.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Government government = report.governmentType == ClaimType.CITY ? data.getCity(report.governmentId) : data.getCountry(report.governmentId);
        if (government == null || !government.hasPermission(player.getUUID(), GovPermission.MANAGE_JAILS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to review this report.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int fineCents = Money.toCents(DoubleArgumentType.getDouble(ctx, "fine"));
        String cellName = StringArgumentType.getString(ctx, "cell");
        long jailMillis = IntegerArgumentType.getInteger(ctx, "minutes") * 60L * 1000;
        int quotaCents = Money.toCents(DoubleArgumentType.getDouble(ctx, "quota"));

        JailCell cell = null;
        if (!cellName.equalsIgnoreCase("none"))
        {
            cell = government.findJailCellByName(cellName);
            if (cell == null)
            {
                ctx.getSource().sendFailure(Component.literal("Unknown jail cell \"" + cellName + "\".").withStyle(ChatFormatting.RED));
                return 0;
            }
            if (data.jailCellOccupancy(cell) >= cell.maxCapacity)
            {
                ctx.getSource().sendFailure(Component.literal("Cell \"" + cellName + "\" is full.").withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        Sentence sentence = data.resolveReport(server, government, report, fineCents, cell, jailMillis, quotaCents);
        if (sentence != null)
        {
            ServerPlayer offender = server.getPlayerList().getPlayer(report.offenderId);
            if (offender != null)
                JailListener.applySentenceNow(server, offender, sentence, cell);
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Report resolved."), true);
        return 1;
    }

    private static int pardon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        GameProfile target = firstProfile(ctx, "player");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Sentence sentence = data.getSentence(target.getId());
        if (sentence == null)
        {
            ctx.getSource().sendFailure(Component.literal(target.getName() + " isn't serving a sentence.").withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayer(target.getId());
        if (player != null && sentence.applied)
            JailListener.releaseNow(ctx.getSource().getServer(), data, player, sentence);
        else
            data.removeSentence(target.getId());

        ctx.getSource().sendSuccess(() -> Component.literal(target.getName() + " pardoned."), true);
        return 1;
    }

    private static int setVictimShare(CommandContext<CommandSourceStack> ctx, ClaimType govType) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, govType, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_JAILS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s reports.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int percent = IntegerArgumentType.getInteger(ctx, "percent");
        data.setVictimShare(government, percent);
        ctx.getSource().sendSuccess(() -> Component.literal(government.name + "'s victim share set to " + percent + "%."), true);
        return 1;
    }

    private static int addJailCell(CommandContext<CommandSourceStack> ctx, ClaimType govType) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, govType, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_JAILS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s jails.").withStyle(ChatFormatting.RED));
            return 0;
        }

        String cellName = StringArgumentType.getString(ctx, "cell");
        if (government.findJailCellByName(cellName) != null)
        {
            ctx.getSource().sendFailure(Component.literal("A cell named \"" + cellName + "\" already exists.").withStyle(ChatFormatting.RED));
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        int maxCapacity = IntegerArgumentType.getInteger(ctx, "maxCapacity");

        data.addJailCell(government, cellName, player.level().dimension(), player.blockPosition(), radius, maxCapacity);
        ctx.getSource().sendSuccess(() -> Component.literal("Added jail cell \"" + cellName + "\" to " + government.name + " here."), true);
        return 1;
    }

    private static int removeJailCell(CommandContext<CommandSourceStack> ctx, ClaimType govType) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Government government = findGovernment(data, govType, name);
        if (government == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!government.hasPermission(player.getUUID(), GovPermission.MANAGE_JAILS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + government.name + "'s jails.").withStyle(ChatFormatting.RED));
            return 0;
        }

        String cellName = StringArgumentType.getString(ctx, "cell");
        JailCell cell = government.findJailCellByName(cellName);
        if (cell == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown jail cell \"" + cellName + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.removeJailCell(government, cell.id);
        ctx.getSource().sendSuccess(() -> Component.literal("Removed jail cell \"" + cellName + "\" from " + government.name + "."), true);
        return 1;
    }

    private static CountryLaw parseLaw(String raw)
    {
        try
        {
            return CountryLaw.valueOf(raw.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static final String INVALID_LAW_MESSAGE = "Unknown law. Valid values: no_fire, no_destruction, clothing_required.";

    private static int setLawEnabled(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_LAWS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s laws.").withStyle(ChatFormatting.RED));
            return 0;
        }

        CountryLaw law = parseLaw(StringArgumentType.getString(ctx, "law"));
        if (law == null)
        {
            ctx.getSource().sendFailure(Component.literal(INVALID_LAW_MESSAGE).withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean enabled = Boolean.parseBoolean(StringArgumentType.getString(ctx, "enabled"));

        data.setLawEnabled(country, law, enabled);
        ctx.getSource().sendSuccess(() -> Component.literal(law + " " + (enabled ? "enabled" : "disabled") + " in " + country.name + "."), true);
        return 1;
    }

    private static int configureLaw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_LAWS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s laws.").withStyle(ChatFormatting.RED));
            return 0;
        }

        CountryLaw law = parseLaw(StringArgumentType.getString(ctx, "law"));
        if (law == null)
        {
            ctx.getSource().sendFailure(Component.literal(INVALID_LAW_MESSAGE).withStyle(ChatFormatting.RED));
            return 0;
        }

        int fineCents = Money.toCents(DoubleArgumentType.getDouble(ctx, "fine"));
        String cellName = StringArgumentType.getString(ctx, "cell");
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        int quotaCents = Money.toCents(DoubleArgumentType.getDouble(ctx, "quota"));

        UUID cellId = null;
        if (!cellName.equalsIgnoreCase("none"))
        {
            JailCell cell = country.findJailCellByName(cellName);
            if (cell == null)
            {
                ctx.getSource().sendFailure(Component.literal("Unknown jail cell \"" + cellName + "\".").withStyle(ChatFormatting.RED));
                return 0;
            }
            cellId = cell.id;
        }

        data.configureLaw(country, law, fineCents, cellId, minutes, quotaCents);
        ctx.getSource().sendSuccess(() -> Component.literal("Configured " + law + " for " + country.name + "."), true);
        return 1;
    }

    private static int setLawMaxY(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_LAWS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s laws.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int y = IntegerArgumentType.getInteger(ctx, "y");
        data.setLawMaxY(country, y);
        ctx.getSource().sendSuccess(() -> Component.literal(country.name + "'s laws now apply up to Y=" + y + " (plus any override zones)."), true);
        return 1;
    }

    private static int addLawZone(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_LAWS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s laws.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Rect zone = Rect.ofCorners(IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "z1"),
                IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "z2"));
        data.addLawYOverrideZone(country, zone);
        ctx.getSource().sendSuccess(() -> Component.literal("Added a Y-cap override zone to " + country.name + "."), true);
        return 1;
    }

    private static int removeLawZone(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_LAWS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s laws.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (!data.removeLawYOverrideZone(country, index))
        {
            ctx.getSource().sendFailure(Component.literal("No override zone at index " + index + ".").withStyle(ChatFormatting.RED));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Removed override zone " + index + " from " + country.name + "."), true);
        return 1;
    }

    private static int grantPermit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_PERMITS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s permits.").withStyle(ChatFormatting.RED));
            return 0;
        }

        GameProfile target = firstProfile(ctx, "player");
        CountryLaw law = parseLaw(StringArgumentType.getString(ctx, "law"));
        if (law == null)
        {
            ctx.getSource().sendFailure(Component.literal(INVALID_LAW_MESSAGE).withStyle(ChatFormatting.RED));
            return 0;
        }

        data.grantPermit(country, target.getId(), law);
        ctx.getSource().sendSuccess(() -> Component.literal("Granted " + target.getName() + " a permit exempting them from " + law + " in " + country.name + "."), true);
        return 1;
    }

    private static int revokePermit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());

        Country country = data.findCountryByName(name);
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("Unknown country \"" + name + "\".").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!country.hasPermission(player.getUUID(), GovPermission.MANAGE_PERMITS))
        {
            ctx.getSource().sendFailure(Component.literal("You don't have permission to manage " + country.name + "'s permits.").withStyle(ChatFormatting.RED));
            return 0;
        }

        GameProfile target = firstProfile(ctx, "player");
        CountryLaw law = parseLaw(StringArgumentType.getString(ctx, "law"));
        if (law == null)
        {
            ctx.getSource().sendFailure(Component.literal(INVALID_LAW_MESSAGE).withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!data.revokePermit(country, target.getId(), law))
        {
            ctx.getSource().sendFailure(Component.literal(target.getName() + " doesn't have a permit for " + law + ".").withStyle(ChatFormatting.RED));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Revoked " + target.getName() + "'s permit for " + law + " in " + country.name + "."), true);
        return 1;
    }

    private static int borderAccept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TerritoryData data = TerritoryData.get(ctx.getSource().getServer());
        BlockPos pos = player.blockPosition();
        Country country = data.findCountryAt(player.level().dimension(), pos.getX(), pos.getZ());
        if (country == null)
        {
            ctx.getSource().sendFailure(Component.literal("You're not at a country border right now.").withStyle(ChatFormatting.RED));
            return 0;
        }

        data.agreeToLaws(country, player.getUUID());
        BorderTracker.setCurrentCountry(player.getUUID(), country.id);
        BorderTracker.setAwaitingConsent(player.getUUID(), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Agreed to " + country.name + "'s laws."), false);
        return 1;
    }

    private static int borderDecline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
    {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID playerId = player.getUUID();
        BorderTracker.setAwaitingConsent(playerId, false);
        BorderTracker.setCurrentCountry(playerId, null);

        BorderTracker.Position fallback = BorderTracker.getLastOutside(playerId);
        if (fallback != null)
        {
            ServerLevel dest = ctx.getSource().getServer().getLevel(fallback.dimension());
            if (dest != null)
                player.teleportTo(dest, fallback.x(), fallback.y(), fallback.z(), fallback.yaw(), fallback.pitch());
        }

        ctx.getSource().sendSuccess(() -> Component.literal("Declined."), false);
        return 1;
    }

    private static UUID parseUuid(CommandContext<CommandSourceStack> ctx, String argName) throws CommandSyntaxException
    {
        try
        {
            return UUID.fromString(StringArgumentType.getString(ctx, argName));
        }
        catch (IllegalArgumentException e)
        {
            ctx.getSource().sendFailure(Component.literal("Invalid id.").withStyle(ChatFormatting.RED));
            return null;
        }
    }
}
