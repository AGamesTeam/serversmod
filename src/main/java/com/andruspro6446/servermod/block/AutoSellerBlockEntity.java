package com.andruspro6446.servermod.block;

import com.andruspro6446.servermod.ServerMod;
import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.market.MarketEntry;
import com.andruspro6446.servermod.money.MoneyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.UUID;

// Exposes an item-handler capability (not a vanilla Container) so a hopper feeding into this block sells
// each item the instant it arrives, at the current market price, crediting whoever placed the block - not
// whoever owns the hopper. Non-tradeable or currently worthless items are rejected so a hopper can route
// them elsewhere instead of losing them.
public class AutoSellerBlockEntity extends BlockEntity
{
    private UUID ownerId;
    private final ItemStackHandler itemHandler = new SellingItemHandler();
    private final LazyOptional<IItemHandler> itemHandlerOptional = LazyOptional.of(() -> itemHandler);

    public AutoSellerBlockEntity(BlockPos pos, BlockState state)
    {
        super(ServerMod.AUTO_SELLER_BLOCK_ENTITY.get(), pos, state);
    }

    public void setOwner(UUID ownerId)
    {
        this.ownerId = ownerId;
        setChanged();
    }

    public String getOwnerName()
    {
        if (ownerId == null)
            return "unclaimed";
        if (level instanceof ServerLevel serverLevel)
        {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(ownerId);
            if (player != null)
                return player.getGameProfile().getName();
        }
        return "an offline player";
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

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side)
    {
        if (cap == ForgeCapabilities.ITEM_HANDLER)
            return itemHandlerOptional.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps()
    {
        super.invalidateCaps();
        itemHandlerOptional.invalidate();
    }

    @Override
    public void setRemoved()
    {
        super.setRemoved();
        itemHandlerOptional.invalidate();
    }

    private MarketEntry findEntry(ItemStack stack)
    {
        if (level == null || level.isClientSide || ownerId == null)
            return null;

        Item item = stack.getItem();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null)
            return null;

        return MarketData.get(((ServerLevel) level).getServer()).getEntry(id);
    }

    private class SellingItemHandler extends ItemStackHandler
    {
        SellingItemHandler()
        {
            super(1);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack)
        {
            MarketEntry entry = findEntry(stack);
            return entry != null && unitPriceCents(entry) > 0;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate)
        {
            if (stack.isEmpty())
                return ItemStack.EMPTY;

            MarketEntry entry = findEntry(stack);
            if (entry == null)
                return stack;

            int unitPriceCents = unitPriceCents(entry);
            if (unitPriceCents <= 0)
                return stack;

            if (simulate)
                return ItemStack.EMPTY;

            ServerLevel serverLevel = (ServerLevel) level;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            int totalCents = unitPriceCents * stack.getCount();

            MoneyData.get(serverLevel.getServer()).addMoney(serverLevel.getServer(), ownerId, totalCents);
            MarketData.get(serverLevel.getServer()).applySell(id, stack.getCount());

            return ItemStack.EMPTY;
        }

        private int unitPriceCents(MarketEntry entry)
        {
            return (int) Math.floor(entry.currentPrice * 100);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate)
        {
            return ItemStack.EMPTY;
        }
    }
}
