package com.andruspro6446.servermod.territory;

import com.andruspro6446.servermod.ServerMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;

// Auto-detects and auto-punishes the concrete CountryLaw catalog, and drives the country border
// consent/re-consent flow. Unlike ProtectionListener (land claims, manual reports), violations here are
// immediately punished using the country's own pre-configured LawConfig - no reporter, no review.
@Mod.EventBusSubscriber(modid = ServerMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LawListener
{
    private LawListener()
    {
    }

    // A player who disconnects with a prompt outstanding would otherwise be stuck forever unprompted (the
    // border tick skips anyone already awaiting a response) - clear that flag on (re)join so they get freshly
    // evaluated on their next tick.
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        BorderTracker.setAwaitingConsent(event.getEntity().getUUID(), false);
    }

    private static boolean applies(Country country, CountryLaw law, UUID playerId, int x, int y, int z)
    {
        LawConfig config = country.laws.get(law);
        return config.enabled && country.lawsApplyAt(x, y, z) && !country.isExempt(playerId, law);
    }

    // Flint & steel / fire charge use is the direct, player-caused way to start a fire; natural fire spread
    // is a world-tick mechanic and deliberately out of scope here (see CountryLaw.NO_FIRE).
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!(player.level() instanceof ServerLevel level))
            return;

        ItemStack held = event.getItemStack();
        if (held.getItem() != Items.FLINT_AND_STEEL && held.getItem() != Items.FIRE_CHARGE)
            return;

        BlockPos pos = event.getPos().relative(event.getFace());
        TerritoryData data = TerritoryData.get(level.getServer());
        Country country = data.findCountryAt(level.dimension(), pos.getX(), pos.getZ());
        if (country == null)
            return;

        if (applies(country, CountryLaw.NO_FIRE, player.getUUID(), pos.getX(), pos.getY(), pos.getZ()))
        {
            event.setCanceled(true);
            event.setUseItem(Event.Result.DENY);
            punish(level.getServer(), country, CountryLaw.NO_FIRE, player, "You aren't allowed to start fires in " + country.name + ".");
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;
        enforceNoDestruction(level, event.getPos(), player, event);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        enforceNoDestruction(level, event.getPos(), player, event);
    }

    // NO_DESTRUCTION only governs unclaimed land - a land claim's own BUILD permission already covers
    // claimed ground, complete with its own owner-cut-of-punishment report flow.
    private static void enforceNoDestruction(ServerLevel level, BlockPos pos, ServerPlayer player, BlockEvent event)
    {
        TerritoryData data = TerritoryData.get(level.getServer());
        if (data.findLandClaimAt(level.dimension(), pos.getX(), pos.getZ()) != null)
            return;

        Country country = data.findCountryAt(level.dimension(), pos.getX(), pos.getZ());
        if (country == null)
            return;

        if (applies(country, CountryLaw.NO_DESTRUCTION, player.getUUID(), pos.getX(), pos.getY(), pos.getZ()))
        {
            event.setCanceled(true);
            punish(level.getServer(), country, CountryLaw.NO_DESTRUCTION, player,
                    "You aren't allowed to alter unclaimed land in " + country.name + ".");
        }
    }

    private static void punish(MinecraftServer server, Country country, CountryLaw law, ServerPlayer player, String message)
    {
        player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
        TerritoryData data = TerritoryData.get(server);
        Sentence sentence = data.applyLawPunishment(server, country, law, player.getUUID());
        LawConfig config = country.laws.get(law);
        StringBuilder summary = new StringBuilder("[ServerMod] Violation of " + country.name + "'s " + law + " law.");
        if (config.fineCents > 0)
            summary.append(" Fined.");
        if (sentence != null)
            summary.append(" A sentence has been recorded against you.");
        player.sendSystemMessage(Component.literal(summary.toString()).withStyle(ChatFormatting.GOLD));
    }

    // Periodic (not event-driven) clothing check - see CountryLaw.CLOTHING_REQUIRED.
    public static void clothingTick(MinecraftServer server)
    {
        TerritoryData data = TerritoryData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition();
            Country country = data.findCountryAt(level.dimension(), pos.getX(), pos.getZ());
            if (country == null)
                continue;
            if (!applies(country, CountryLaw.CLOTHING_REQUIRED, player.getUUID(), pos.getX(), pos.getY(), pos.getZ()))
                continue;

            boolean clothed = !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                    && !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty();
            if (!clothed)
                punish(server, country, CountryLaw.CLOTHING_REQUIRED, player,
                        "You must be properly dressed (chestplate + leggings) in " + country.name + ".");
        }
    }

    // Periodic border crossing / consent tick. Detects entering/leaving a country, prompts Accept/Decline on
    // entry, and re-prompts everyone still inside the instant their country's lawVersion moves ahead of what
    // they last agreed to.
    public static void borderTick(MinecraftServer server)
    {
        TerritoryData data = TerritoryData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            UUID playerId = player.getUUID();
            ServerLevel level = player.serverLevel();
            BlockPos pos = player.blockPosition();
            Country country = data.findCountryAt(level.dimension(), pos.getX(), pos.getZ());
            UUID currentId = BorderTracker.getCurrentCountry(playerId);

            if (country == null)
            {
                if (currentId != null)
                    BorderTracker.setCurrentCountry(playerId, null);
                BorderTracker.setAwaitingConsent(playerId, false);
                BorderTracker.setLastOutside(playerId, new BorderTracker.Position(level.dimension(),
                        player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
                continue;
            }

            if (BorderTracker.isAwaitingConsent(playerId))
                continue;

            boolean freshEntry = currentId == null || !currentId.equals(country.id);
            Integer agreedVersion = country.agreedLawVersions.get(playerId);
            boolean staleAgreement = agreedVersion == null || agreedVersion != country.lawVersion;

            if (freshEntry || staleAgreement)
                promptBorder(country, player, freshEntry, agreedVersion != null && staleAgreement);
        }
    }

    private static void promptBorder(Country country, ServerPlayer player, boolean freshEntry, boolean rulesChanged)
    {
        BorderTracker.setAwaitingConsent(player.getUUID(), true);

        List<CountryLaw> active = country.laws.entrySet().stream()
                .filter(e -> e.getValue().enabled).map(java.util.Map.Entry::getKey).toList();

        StringBuilder header = new StringBuilder();
        if (freshEntry)
            header.append("You're entering ").append(country.name).append(". ");
        else if (rulesChanged)
            header.append(country.name).append("'s laws have changed since you last agreed. ");
        header.append(active.isEmpty() ? "It has no active laws right now." : "Active laws: " + active);

        Component accept = Component.literal("[Accept]").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/land border accept"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Agree and enter"))));
        Component decline = Component.literal("[Decline]").setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/land border decline"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Decline and be sent back"))));

        player.sendSystemMessage(Component.literal("[ServerMod] ").withStyle(ChatFormatting.GRAY).append(header.toString()));
        player.sendSystemMessage(accept.copy().append(Component.literal("   ")).append(decline));
    }
}
