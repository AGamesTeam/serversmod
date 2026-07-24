package com.andruspro6446.servermod.block;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

// Feed items into this block with a hopper (from any side) and it automatically sells them at the current
// market price, the instant they arrive, paying whoever placed the block - not whoever owns the hopper.
public class AutoSellerBlock extends Block implements EntityBlock
{
    public AutoSellerBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer != null && level.getBlockEntity(pos) instanceof AutoSellerBlockEntity blockEntity)
            blockEntity.setOwner(placer.getUUID());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (hand != InteractionHand.MAIN_HAND)
            return InteractionResult.PASS;

        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        if (level.getBlockEntity(pos) instanceof AutoSellerBlockEntity blockEntity)
        {
            player.displayClientMessage(Component.literal(
                    "Feed items into this with a hopper to sell them automatically. Owner: " + blockEntity.getOwnerName()
            ).withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new AutoSellerBlockEntity(pos, state);
    }
}
