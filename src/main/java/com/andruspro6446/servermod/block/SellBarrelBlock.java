package com.andruspro6446.servermod.block;

import com.andruspro6446.servermod.business.BusinessData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

// A physical shop container a business places to make items sellable on its web storefront (see
// BusinessHandler/SellBarrelBlockEntity - the barrel's real contents ARE the storefront's stock, nothing
// virtual). Right-click opens it like a chest; a hopper on any side can feed it too. Ownership is set to
// whoever placed it, and BusinessData tracks its position so it can be found (and billed) later.
public class SellBarrelBlock extends Block implements EntityBlock
{
    public SellBarrelBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || placer == null)
            return;
        if (!(level.getBlockEntity(pos) instanceof SellBarrelBlockEntity blockEntity))
            return;

        blockEntity.setOwner(placer.getUUID());
        if (level instanceof ServerLevel serverLevel)
            BusinessData.get(serverLevel.getServer()).registerBarrel(placer.getUUID(), serverLevel.dimension(), pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving)
    {
        if (!state.is(newState.getBlock()))
        {
            if (level.getBlockEntity(pos) instanceof SellBarrelBlockEntity blockEntity)
            {
                Containers.dropContents(level, pos, blockEntity);
                if (level instanceof ServerLevel serverLevel && blockEntity.getOwnerId() != null)
                    BusinessData.get(serverLevel.getServer()).unregisterBarrel(blockEntity.getOwnerId(), serverLevel.dimension(), pos);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (hand != InteractionHand.MAIN_HAND)
            return InteractionResult.PASS;

        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        if (level.getBlockEntity(pos) instanceof SellBarrelBlockEntity blockEntity)
            player.openMenu(blockEntity);
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new SellBarrelBlockEntity(pos, state);
    }
}
