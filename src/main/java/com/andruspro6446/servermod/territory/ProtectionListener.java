package com.andruspro6446.servermod.territory;

import com.andruspro6446.servermod.ServerMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// Enforces land claims' BUILD/CONTAINER_OPEN/INTERACT/PVP/MOB_DAMAGE rules by canceling the underlying game
// event when the actor isn't allowed. CONTAINER_TAKE is deliberately not enforced here - see
// ClaimPermission's javadoc. Claim-less land (including everything outside any claim) is always unrestricted.
@Mod.EventBusSubscriber(modid = ServerMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProtectionListener
{
    private ProtectionListener()
    {
    }

    private static boolean blocked(ServerLevel level, BlockPos pos, ServerPlayer player, ClaimPermission permission)
    {
        TerritoryData data = TerritoryData.get(level.getServer());
        LandClaim claim = data.findLandClaimAt(level.dimension(), pos.getX(), pos.getZ());
        return claim != null && !data.isAllowed(claim, player.getUUID(), permission);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;

        if (blocked(level, event.getPos(), player, ClaimPermission.BUILD))
        {
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("You can't build here.").withStyle(ChatFormatting.RED), true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (blocked(level, event.getPos(), player, ClaimPermission.BUILD))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!(player.level() instanceof ServerLevel level))
            return;

        BlockPos pos = event.getPos();
        ClaimPermission permission = level.getBlockEntity(pos) instanceof MenuProvider
                ? ClaimPermission.CONTAINER_OPEN
                : ClaimPermission.INTERACT;

        if (blocked(level, pos, player, permission))
        {
            event.setCanceled(true);
            event.setUseBlock(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event)
    {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level))
            return;

        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer attacker))
            return;

        BlockPos pos = victim.blockPosition();
        ClaimPermission permission = victim instanceof ServerPlayer ? ClaimPermission.PVP : ClaimPermission.MOB_DAMAGE;

        if (blocked(level, pos, attacker, permission))
            event.setCanceled(true);
    }
}
