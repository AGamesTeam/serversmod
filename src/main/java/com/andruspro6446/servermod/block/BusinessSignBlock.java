package com.andruspro6446.servermod.block;

import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.UUID;

// Every business gets one of these on registration (see web.BusinessHandler.doRegister) - place it near your
// storefront and right-click to show anyone your hours and whether you're currently open. Customer NPCs check
// the same open/closed state and complain in their review if they show up while you're closed (see
// review.ReviewGenerator.Outcome.CLOSED). Costs its own small recurring fee, folded into the weekly bill
// (see BusinessData.signFeeCentsPerMonth/amountDueCents).
public class BusinessSignBlock extends Block implements EntityBlock
{
    public BusinessSignBlock(Properties properties)
    {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack)
    {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || placer == null)
            return;
        if (level.getBlockEntity(pos) instanceof BusinessSignBlockEntity blockEntity)
            blockEntity.setOwner(placer.getUUID());
        if (level instanceof ServerLevel serverLevel)
        {
            BusinessData data = BusinessData.get(serverLevel.getServer());
            data.markSignPlaced(placer.getUUID());
            spawnNameDisplay(serverLevel, pos, data, placer.getUUID());
        }
    }

    // A floating text label showing the business's name, so the sign is legible at a glance rather than only
    // on right-click. Just a plain Display entity riding above the block - not a full vanilla sign, so it
    // doesn't need the sign-block state/rendering machinery. TextDisplay's text/billboard setters are private
    // (only settable from within net.minecraft.world.entity itself), so this configures it the same way
    // "/summon" does: build the NBT it reads in readAdditionalSaveData and load() it onto a freshly created,
    // as-yet-unpositioned entity in one go (load() would reset position to the origin if Pos were omitted).
    private static void spawnNameDisplay(ServerLevel level, BlockPos pos, BusinessData data, UUID ownerId)
    {
        Business business = data.getByOwner(ownerId);
        if (business == null)
            return;

        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null)
            return;

        ListTag posTag = new ListTag();
        posTag.add(DoubleTag.valueOf(pos.getX() + 0.5));
        posTag.add(DoubleTag.valueOf(pos.getY() + 1.3));
        posTag.add(DoubleTag.valueOf(pos.getZ() + 0.5));

        CompoundTag tag = new CompoundTag();
        tag.put("Pos", posTag);
        tag.putString(Display.TextDisplay.TAG_TEXT, Component.Serializer.toJson(
                Component.literal(business.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        tag.putString(Display.TAG_BILLBOARD, Display.BillboardConstraints.CENTER.getSerializedName());
        display.load(tag);

        level.addFreshEntity(display);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving)
    {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel)
        {
            for (Display.TextDisplay display : serverLevel.getEntitiesOfClass(Display.TextDisplay.class, new AABB(pos).inflate(2)))
                display.discard();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel))
            return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof BusinessSignBlockEntity blockEntity) || blockEntity.getOwnerId() == null)
        {
            player.displayClientMessage(Component.literal("This sign isn't linked to a business.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }

        BusinessData data = BusinessData.get(serverLevel.getServer());
        Business business = data.getByOwner(blockEntity.getOwnerId());
        if (business == null)
        {
            player.displayClientMessage(Component.literal("This sign's business no longer exists.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }

        boolean open = data.isOpenNow(business);
        double average = business.reviews.isEmpty() ? 0 : business.reviews.stream().mapToDouble(r -> r.stars).average().orElse(0);
        String ratingText = business.reviews.isEmpty() ? "no reviews yet" : "%.1f/5.0 (%d review%s)"
                .formatted(average, business.reviews.size(), business.reviews.size() == 1 ? "" : "s");

        player.sendSystemMessage(Component.literal(business.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("Hours: " + formatHour(business.openHour) + " - " + formatHour(business.closeHour))
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal(open ? "Currently OPEN" : "Currently CLOSED")
                .withStyle(open ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal("Rating: " + ratingText).withStyle(ChatFormatting.YELLOW));
        return InteractionResult.CONSUME;
    }

    private static String formatHour(int hour24)
    {
        int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        return hour12 + (hour24 < 12 ? "am" : "pm");
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new BusinessSignBlockEntity(pos, state);
    }
}
