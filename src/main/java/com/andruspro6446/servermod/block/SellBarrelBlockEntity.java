package com.andruspro6446.servermod.block;

import com.andruspro6446.servermod.ServerMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

// A real, physical 27-slot container - like a vanilla Barrel - that a business owner places and stocks by
// hand or via a hopper. Its live contents are what a business's public web storefront actually sells from
// (see BusinessHandler); nothing about "stock" here is virtual. Ownership is set to whoever placed it, and
// BusinessData tracks its position so the storefront/billing logic can find every barrel a business owns.
public class SellBarrelBlockEntity extends BlockEntity implements Container, MenuProvider
{
    public static final int SLOTS = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private UUID ownerId;
    private final LazyOptional<IItemHandler> itemHandler = LazyOptional.of(() -> new InvWrapper(this));

    public SellBarrelBlockEntity(BlockPos pos, BlockState state)
    {
        super(ServerMod.SELL_BARREL_BLOCK_ENTITY.get(), pos, state);
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

    // ---------- Container ----------

    @Override
    public int getContainerSize()
    {
        return SLOTS;
    }

    @Override
    public boolean isEmpty()
    {
        for (ItemStack stack : items)
            if (!stack.isEmpty())
                return false;
        return true;
    }

    @Override
    @Nonnull
    public ItemStack getItem(int slot)
    {
        return items.get(slot);
    }

    @Override
    @Nonnull
    public ItemStack removeItem(int slot, int amount)
    {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty())
            setChanged();
        return result;
    }

    @Override
    @Nonnull
    public ItemStack removeItemNoUpdate(int slot)
    {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack)
    {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize())
            stack.setCount(getMaxStackSize());
        setChanged();
    }

    @Override
    public boolean stillValid(Player player)
    {
        if (level == null || level.getBlockEntity(worldPosition) != this)
            return false;
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent()
    {
        items.clear();
    }

    // ---------- MenuProvider ----------

    @Override
    @Nonnull
    public Component getDisplayName()
    {
        return Component.translatable("container.servermod.sell_barrel");
    }

    @Override
    @Nullable
    public AbstractContainerMenu createMenu(int containerId, @Nonnull Inventory playerInventory, @Nonnull Player player)
    {
        return ChestMenu.threeRows(containerId, playerInventory, this);
    }

    // ---------- persistence ----------

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
        if (ownerId != null)
            tag.putUUID("Owner", ownerId);
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    // ---------- hopper support ----------

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side)
    {
        if (cap == ForgeCapabilities.ITEM_HANDLER)
            return itemHandler.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps()
    {
        super.invalidateCaps();
        itemHandler.invalidate();
    }

    @Override
    public void setRemoved()
    {
        super.setRemoved();
        itemHandler.invalidate();
    }
}
