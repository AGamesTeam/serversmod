package com.andruspro6446.servermod.customer;

import com.andruspro6446.servermod.business.QueuePos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

// A purely cosmetic stand-in for a hired employee (see Business.employeeHired) - stands one block behind the
// counter at a queue point, facing the line, so a staffed storefront actually looks staffed. The real
// auto-serve behavior lives entirely in CustomerNpcManager.tickEmployeeService; this entity does none of that
// itself, it's just the visual. Spawned/despawned/kept in sync with hiring and queue-point changes by
// CustomerNpcManager.tickEmployeeVisuals, never placed or removed any other way.
public class EmployeeNpc extends PathfinderMob
{
    private BlockPos queueAnchorPos;
    private Direction queueFacing = Direction.NORTH;

    public EmployeeNpc(EntityType<? extends EmployeeNpc> type, Level level)
    {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void registerGoals()
    {
        // Deliberately no movement goals at all - an employee just stands at their spot and looks at whoever
        // walks up, it never wanders or paths anywhere.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
    }

    // Controlled entirely by CustomerNpcManager.tickEmployeeVisuals, not vanilla despawn rules.
    @Override
    public void checkDespawn()
    {
    }

    @Override
    public boolean isPersistenceRequired()
    {
        return true;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand)
    {
        if (!level().isClientSide)
            player.displayClientMessage(Component.literal("(Just the staff keeping the counter running.)").withStyle(ChatFormatting.GRAY), true);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    public QueuePos getQueuePos()
    {
        if (queueAnchorPos == null || !(level() instanceof ServerLevel serverLevel))
            return null;
        return new QueuePos(serverLevel.dimension(), queueAnchorPos, queueFacing);
    }

    public void setQueuePos(QueuePos queuePos)
    {
        this.queueAnchorPos = queuePos.pos();
        this.queueFacing = queuePos.facing();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        if (queueAnchorPos != null)
        {
            tag.putInt("QueueX", queueAnchorPos.getX());
            tag.putInt("QueueY", queueAnchorPos.getY());
            tag.putInt("QueueZ", queueAnchorPos.getZ());
            tag.putString("QueueFacing", queueFacing.getSerializedName());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        if (tag.contains("QueueX"))
        {
            queueAnchorPos = new BlockPos(tag.getInt("QueueX"), tag.getInt("QueueY"), tag.getInt("QueueZ"));
            Direction facing = Direction.byName(tag.getString("QueueFacing"));
            queueFacing = facing != null ? facing : Direction.NORTH;
        }
    }
}
