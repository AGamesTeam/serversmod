package com.andruspro6446.servermod.block;

import com.andruspro6446.servermod.ServerMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

// A physical sign every business gets on registration, showing customers its name, hours, and current
// open/closed status (see BusinessSignBlock) - and checked by customer NPCs when deciding whether to complain
// about arriving at a closed shop (see review.ReviewGenerator.Outcome.CLOSED). Carries no state of its own
// beyond which business it belongs to; everything it displays is read live from BusinessData on interaction.
public class BusinessSignBlockEntity extends BlockEntity
{
    private UUID ownerId;

    public BusinessSignBlockEntity(BlockPos pos, BlockState state)
    {
        super(ServerMod.BUSINESS_SIGN_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getOwnerId()
    {
        return ownerId;
    }

    public void setOwner(UUID ownerId)
    {
        this.ownerId = ownerId;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        if (ownerId != null)
            tag.putUUID("Owner", ownerId);
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }
}
