package com.andruspro6446.servermod.territory;

import com.andruspro6446.servermod.money.Money;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;

// Right-click a block to mark the first corner, right-click again for the opposite corner. The selection is
// molded around other players' claims of the same type automatically (your own overlapping claims merge
// instead), then a chat confirmation shows the resulting size and weekly rent - nothing is claimed until
// accepted. One charge is spent per completed selection, accepted or not; the shovel breaks after its last
// charge. See TerritoryData for the tier (price/charges/max area) baked into this stack at purchase time.
public class ClaimShovelItem extends Item
{
    private final ClaimType claimType;

    public ClaimShovelItem(ClaimType claimType, Properties properties)
    {
        super(properties);
        this.claimType = claimType;
    }

    public ClaimType getClaimType()
    {
        return claimType;
    }

    public static void initialize(ItemStack stack, ShovelTier tier)
    {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID("TierId", tier.id);
        tag.putInt("Charges", tier.maxCharges);
    }

    public static void setName(ItemStack stack, String name)
    {
        stack.getOrCreateTag().putString("Name", name);
    }

    private static void clearSelection(CompoundTag tag)
    {
        tag.remove("Corner1X");
        tag.remove("Corner1Z");
        tag.remove("Corner1Dim");
    }

    @Override
    public InteractionResult useOn(UseOnContext context)
    {
        Level level = context.getLevel();
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        Player player = context.getPlayer();
        if (player == null || !(level instanceof ServerLevel serverLevel))
            return InteractionResult.PASS;

        ItemStack stack = context.getItemInHand();
        CompoundTag tag = stack.getOrCreateTag();

        if (!tag.hasUUID("TierId"))
        {
            player.displayClientMessage(Component.literal("This shovel has no tier set - contact an admin.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        if (claimType != ClaimType.LAND && !tag.contains("Name"))
        {
            player.displayClientMessage(Component.literal("Set a name first: /land name <name>").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        BlockPos pos = context.getClickedPos();
        ResourceKey<Level> dim = serverLevel.dimension();

        if (!tag.contains("Corner1X"))
        {
            tag.putInt("Corner1X", pos.getX());
            tag.putInt("Corner1Z", pos.getZ());
            tag.putString("Corner1Dim", dim.location().toString());
            player.displayClientMessage(Component.literal(
                    "First corner set at " + pos.getX() + ", " + pos.getZ() + ". Right-click the opposite corner to finish."
            ).withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }

        if (!tag.getString("Corner1Dim").equals(dim.location().toString()))
        {
            player.displayClientMessage(Component.literal("That's a different dimension than your first corner - selection reset.").withStyle(ChatFormatting.RED), true);
            clearSelection(tag);
            return InteractionResult.CONSUME;
        }

        int x1 = tag.getInt("Corner1X");
        int z1 = tag.getInt("Corner1Z");
        clearSelection(tag);

        MinecraftServer server = serverLevel.getServer();
        TerritoryData data = TerritoryData.get(server);
        ShovelTier tier = data.getTier(claimType, tag.getUUID("TierId"));
        if (tier == null)
        {
            player.displayClientMessage(Component.literal("This shovel's tier no longer exists - contact an admin.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        Rect selection = Rect.ofCorners(x1, z1, pos.getX(), pos.getZ());
        if (selection.area() > tier.maxAreaBlocks)
        {
            player.displayClientMessage(Component.literal(
                    "That area is too big for this shovel (max " + tier.maxAreaBlocks + " blocks, selected " + selection.area() + ")."
            ).withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        List<Rect> molded = switch (claimType)
        {
            case LAND -> data.moldLandSelection(dim, player.getUUID(), selection);
            case CITY -> data.moldCitySelection(dim, player.getUUID(), selection);
            case COUNTRY -> data.moldCountrySelection(dim, player.getUUID(), selection);
        };

        long blockCount = molded.stream().mapToLong(Rect::area).sum();
        if (blockCount <= 0)
        {
            player.displayClientMessage(Component.literal("That area is entirely already claimed by someone else.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        String name = tag.contains("Name") ? tag.getString("Name") : null;
        String requestId = ClaimRequests.create(player.getUUID(), claimType, dim, molded, name);
        sendConfirmation(player, data, requestId, dim, molded, blockCount, name);

        int charges = tag.getInt("Charges") - 1;
        if (charges <= 0)
            stack.shrink(1);
        else
            tag.putInt("Charges", charges);

        return InteractionResult.CONSUME;
    }

    private void sendConfirmation(Player player, TerritoryData data, String requestId, ResourceKey<Level> dim,
            List<Rect> molded, long blockCount, String name)
    {
        ClaimRegion region = new ClaimRegion(dim, molded);
        MutableComponent details;

        switch (claimType)
        {
            case LAND -> {
                Map<City, Integer> breakdown = data.landRentBreakdown(region);
                if (breakdown.isEmpty())
                {
                    details = Component.literal("Claiming " + blockCount + " blocks. Not inside any city - no rent owed.");
                }
                else
                {
                    MutableComponent rentLines = Component.literal("");
                    int totalWeeklyCents = breakdown.values().stream().mapToInt(Integer::intValue).sum();
                    for (Map.Entry<City, Integer> entry : breakdown.entrySet())
                    {
                        City city = entry.getKey();
                        long overlapBlocks = ClaimRegion.overlapBlockCount(region, city.region);
                        rentLines.append(Component.literal("\n  - " + city.name + ": " + overlapBlocks + " blocks @ "
                                + Money.format(city.landClaimRatePerBlockCents) + "/block = " + Money.format(entry.getValue()) + "/week"));
                        if (!city.officialAccess.isEmpty())
                        {
                            rentLines.append(Component.literal("\n    " + city.name + " officials automatically have: "
                                    + city.officialAccess.stream().map(Enum::toString).reduce((a, b) -> a + ", " + b).orElse("")
                                    + " on land here."));
                        }
                    }
                    details = Component.literal("Claiming " + blockCount + " blocks across " + breakdown.size() + " cit"
                            + (breakdown.size() == 1 ? "y" : "ies") + " (total " + Money.format(totalWeeklyCents) + "/week):")
                            .append(rentLines);
                }
            }
            case CITY -> {
                Country country = data.cityCountry(region);
                if (country == null)
                {
                    details = Component.literal("Founding \"" + name + "\" over " + blockCount + " blocks. Not inside any country - no rent owed.");
                }
                else
                {
                    int weeklyCents = data.cityRentToCountryCents(region, country);
                    details = Component.literal("Founding \"" + name + "\" over " + blockCount + " blocks, inside " + country.name
                            + ": " + Money.format(weeklyCents) + "/week owed to " + country.name + ".");
                }
            }
            default -> {
                int weeklyCents = (int) (blockCount * data.adminCountryRatePerBlockCents());
                details = Component.literal("Founding \"" + name + "\" over " + blockCount + " blocks. Owed to the server admin: "
                        + Money.format(data.adminCountryRatePerBlockCents()) + "/block = " + Money.format(weeklyCents) + "/week.");
            }
        }

        Component accept = Component.literal("[Accept]").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/land accept " + requestId))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Confirm this claim"))));
        Component decline = Component.literal("[Decline]").setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/land decline " + requestId))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Cancel this claim"))));

        player.sendSystemMessage(Component.literal("[ServerMod] ").withStyle(ChatFormatting.GRAY).append(details));
        player.sendSystemMessage(accept.copy().append(Component.literal("   ")).append(decline));
    }
}
