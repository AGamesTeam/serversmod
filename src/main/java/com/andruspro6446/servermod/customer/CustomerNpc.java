package com.andruspro6446.servermod.customer;

import com.andruspro6446.servermod.business.QueuePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// A physical customer that spawns near a listed business, walks to one of its queue points, lines up, and
// waits to be served by the owner (right-click, see mobInteract). Never despawns on its own - its lifecycle
// (timeout, being served, leaving after a decision) is fully driven by CustomerNpcManager, which is also
// where the review it eventually leaves gets generated.
//
// Not every customer is spawned already bound to a business: some start in WANDERING - ambient foot traffic
// with no destination yet, roaming until they either recall a shop they already know of or spot a Business
// Sign, at which point CustomerNpcManager commits them to an actual visit (see commitToShoppingTrip).
public class CustomerNpc extends PathfinderMob
{
    public enum State { WANDERING, TRAVELING, WAITING, BEING_SERVED, LEAVING }

    private UUID businessOwnerId;
    private BlockPos queueAnchorPos;
    private Direction queueFacing = Direction.NORTH;
    private final Map<ResourceLocation, Integer> basket = new LinkedHashMap<>();
    private int totalCents;
    private State state = State.TRAVELING;
    private long spawnedAtTick;
    private long becameFrontAtTick = -1;
    private float damageTakenEnRoute;
    private int hostilesSeenEnRoute;
    private CustomerTrait trait = CustomerTrait.NONE;
    // Only meaningful while state == WANDERING: a business this NPC already "knows about" from a past visit,
    // that it might eventually wander off toward (see CustomerNpcManager.tickWanderDecisions). Null means it
    // hasn't heard of anywhere yet, so it's only looking for a visible Business Sign.
    private UUID awareOfOwnerId;
    // Absolute game tick after which a still-undecided wanderer gives up and despawns quietly.
    private long wanderExpiryTick;
    // Set once, when the offer is first presented (see CustomerNpcManager.presentOffer) - a haggling customer
    // proposes haggledTotalCents instead of totalCents, and the owner can accept that lower offer or hold firm
    // at the full listed price.
    private boolean haggling = false;
    private int haggledTotalCents;
    // The City or Country whose territory this NPC spawned in, if any - its "home" for the local-pride review
    // flavor and the diplomatic discount (see CustomerNpcManager). Null if it spawned outside any claimed
    // government territory. Resolved once at spawn, not re-checked as the NPC moves.
    private UUID homeGovernmentId;

    // Transient (not saved) - cleared/set as a dialogue is sent/resolved. Safe to lose on restart: nobody
    // will be mid-dialogue across a restart anyway.
    private boolean awaitingResponse = false;

    // How far around a queue point to look for other customers of the same line - generous relative to the
    // queue capacity so nobody genuinely in line is ever missed.
    private static final int QUEUE_SCAN_RADIUS = 16;

    public CustomerNpc(EntityType<? extends CustomerNpc> type, Level level)
    {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void registerGoals()
    {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WalkToQueueGoal());
        this.goalSelector.addGoal(1, new WanderGoal());
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
    }

    @Override
    protected void customServerAiStep()
    {
        super.customServerAiStep();
        if (state == State.TRAVELING && tickCount % 40 == 0)
        {
            boolean hostileNearby = !level().getEntitiesOfClass(Monster.class, getBoundingBox().inflate(12)).isEmpty();
            if (hostileNearby)
                hostilesSeenEnRoute = Math.min(50, hostilesSeenEnRoute + 1);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        boolean result = super.hurt(source, amount);
        if (result && state == State.TRAVELING)
            damageTakenEnRoute += amount;
        return result;
    }

    // Never despawns on its own - CustomerNpcManager fully controls this entity's lifecycle (timeout,
    // being served, leaving), so vanilla's distance/idle despawn logic must never kick in mid-queue.
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
        if (level().isClientSide)
            return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.PASS;

        if (state == State.WANDERING)
        {
            serverPlayer.sendSystemMessage(Component.literal("(Just a passerby, not shopping anywhere in particular right now.)"));
            return InteractionResult.CONSUME;
        }
        if (state != State.WAITING || !isFrontOfLine())
        {
            serverPlayer.sendSystemMessage(Component.literal("(" + (getCustomName() != null ? getCustomName().getString() : "This customer")
                    + " is still waiting their turn in line.)"));
            return InteractionResult.CONSUME;
        }
        if (businessOwnerId == null || !businessOwnerId.equals(serverPlayer.getUUID()))
        {
            serverPlayer.sendSystemMessage(Component.literal("This customer is here for someone else's shop."));
            return InteractionResult.CONSUME;
        }
        if (awaitingResponse)
            return InteractionResult.CONSUME;

        CustomerNpcManager.presentOffer((ServerLevel) level(), this, serverPlayer);
        return InteractionResult.CONSUME;
    }

    public String getCustomerDisplayName()
    {
        return trait.displayPrefix + getBaseName();
    }

    // The name without any trait prefix (⭐/📹 etc.) - traits are re-rolled fresh every visit, so tracking a
    // business's regulars (see BusinessData.recordRegularVisit) has to key on this, not the display name, or
    // the same regular would look like a different person every time they happened to roll VIP/Influencer.
    public String getBaseName()
    {
        return getCustomName() != null ? getCustomName().getString() : "Customer";
    }

    public void leaveWithPoof()
    {
        if (level() instanceof ServerLevel serverLevel)
            serverLevel.sendParticles(ParticleTypes.POOF, getX(), getY() + 1.0, getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        discard();
    }

    // ---------- state used by CustomerNpcManager / WalkToQueueGoal ----------

    public UUID getBusinessOwnerId() { return businessOwnerId; }
    public void setBusinessOwnerId(UUID businessOwnerId) { this.businessOwnerId = businessOwnerId; }

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

    public Map<ResourceLocation, Integer> getBasket() { return basket; }
    public int getTotalCents() { return totalCents; }
    public void setTotalCents(int totalCents) { this.totalCents = totalCents; }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public long getSpawnedAtTick() { return spawnedAtTick; }
    public void setSpawnedAtTick(long spawnedAtTick) { this.spawnedAtTick = spawnedAtTick; }

    public long getBecameFrontAtTick() { return becameFrontAtTick; }

    public float getDamageTakenEnRoute() { return damageTakenEnRoute; }
    public int getHostilesSeenEnRoute() { return hostilesSeenEnRoute; }

    public CustomerTrait getTrait() { return trait; }
    public void setTrait(CustomerTrait trait) { this.trait = trait; }

    public UUID getAwareOfOwnerId() { return awareOfOwnerId; }
    public void setAwareOfOwnerId(UUID awareOfOwnerId) { this.awareOfOwnerId = awareOfOwnerId; }

    public long getWanderExpiryTick() { return wanderExpiryTick; }
    public void setWanderExpiryTick(long wanderExpiryTick) { this.wanderExpiryTick = wanderExpiryTick; }

    public boolean isHaggling() { return haggling; }
    public void setHaggling(boolean haggling) { this.haggling = haggling; }
    public int getHaggledTotalCents() { return haggledTotalCents; }
    public void setHaggledTotalCents(int haggledTotalCents) { this.haggledTotalCents = haggledTotalCents; }

    public UUID getHomeGovernmentId() { return homeGovernmentId; }
    public void setHomeGovernmentId(UUID homeGovernmentId) { this.homeGovernmentId = homeGovernmentId; }

    // Converts a WANDERING npc with no destination into an active shopper, as if it had been spawned fresh
    // for this business - resets the en-route damage/hostile counters so a rough walk taken before deciding
    // where to shop doesn't unfairly count against wherever it ends up choosing.
    public void commitToShoppingTrip(UUID ownerId, QueuePos queuePos, Map<ResourceLocation, Integer> newBasket, int newTotalCents)
    {
        this.businessOwnerId = ownerId;
        setQueuePos(queuePos);
        basket.clear();
        basket.putAll(newBasket);
        this.totalCents = newTotalCents;
        this.awareOfOwnerId = null;
        this.damageTakenEnRoute = 0;
        this.hostilesSeenEnRoute = 0;
        this.spawnedAtTick = level().getGameTime();
        this.state = State.TRAVELING;
    }

    public boolean isAwaitingResponse() { return awaitingResponse; }
    public void setAwaitingResponse(boolean awaitingResponse) { this.awaitingResponse = awaitingResponse; }

    // No reserved/numbered slot at all - "am I at the front" is answered fresh every time by physically
    // checking whether any other still-active customer of this same queue is currently standing closer to the
    // front than I am. Since a served/declined/timed-out customer is discarded from the world immediately
    // (see leaveWithPoof), this can never end up "waiting on" someone who's actually already gone - there's no
    // stale reservation to go stale in the first place.
    public boolean isFrontOfLine()
    {
        return queueAnchorPos != null && countCustomersAheadInQueue() == 0;
    }

    // How many other live customers of this same queue point are currently closer to the front than this one.
    int countCustomersAheadInQueue()
    {
        if (queueAnchorPos == null || !(level() instanceof ServerLevel))
            return 0;

        double myDistSq = queueAnchorPos.distToCenterSqr(getX(), getY(), getZ());
        int ahead = 0;
        for (CustomerNpc other : level().getEntitiesOfClass(CustomerNpc.class, new AABB(queueAnchorPos).inflate(QUEUE_SCAN_RADIUS)))
        {
            if (other == this || !isSameQueue(other) || !other.isActiveInQueue())
                continue;
            double otherDistSq = queueAnchorPos.distToCenterSqr(other.getX(), other.getY(), other.getZ());
            if (otherDistSq < myDistSq || (otherDistSq == myDistSq && other.spawnedAtTick < spawnedAtTick))
                ahead++;
        }
        return ahead;
    }

    private boolean isSameQueue(CustomerNpc other)
    {
        return queueAnchorPos.equals(other.queueAnchorPos) && queueFacing == other.queueFacing;
    }

    private boolean isActiveInQueue()
    {
        return state == State.TRAVELING || state == State.WAITING || state == State.BEING_SERVED;
    }

    // ---------- persistence ----------

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        if (businessOwnerId != null)
            tag.putUUID("BusinessOwner", businessOwnerId);
        if (queueAnchorPos != null)
        {
            tag.putInt("QueueX", queueAnchorPos.getX());
            tag.putInt("QueueY", queueAnchorPos.getY());
            tag.putInt("QueueZ", queueAnchorPos.getZ());
            tag.putString("QueueFacing", queueFacing.getSerializedName());
        }
        tag.putInt("TotalCents", totalCents);
        tag.putString("CustomerState", state.name());
        tag.putLong("SpawnedAtTick", spawnedAtTick);
        tag.putLong("BecameFrontAtTick", becameFrontAtTick);
        tag.putFloat("DamageEnRoute", damageTakenEnRoute);
        tag.putInt("HostilesEnRoute", hostilesSeenEnRoute);
        tag.putString("Trait", trait.name());
        if (awareOfOwnerId != null)
            tag.putUUID("AwareOfOwner", awareOfOwnerId);
        tag.putLong("WanderExpiryTick", wanderExpiryTick);
        tag.putBoolean("Haggling", haggling);
        tag.putInt("HaggledTotalCents", haggledTotalCents);
        if (homeGovernmentId != null)
            tag.putUUID("HomeGovernment", homeGovernmentId);

        CompoundTag basketTag = new CompoundTag();
        basket.forEach((id, qty) -> basketTag.putInt(id.toString(), qty));
        tag.put("Basket", basketTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        businessOwnerId = tag.hasUUID("BusinessOwner") ? tag.getUUID("BusinessOwner") : null;
        if (tag.contains("QueueX"))
        {
            queueAnchorPos = new BlockPos(tag.getInt("QueueX"), tag.getInt("QueueY"), tag.getInt("QueueZ"));
            Direction facing = Direction.byName(tag.getString("QueueFacing"));
            queueFacing = facing != null ? facing : Direction.NORTH;
        }
        totalCents = tag.getInt("TotalCents");
        try
        {
            state = State.valueOf(tag.getString("CustomerState"));
        }
        catch (IllegalArgumentException e)
        {
            state = State.TRAVELING;
        }
        spawnedAtTick = tag.getLong("SpawnedAtTick");
        becameFrontAtTick = tag.getLong("BecameFrontAtTick");
        damageTakenEnRoute = tag.getFloat("DamageEnRoute");
        hostilesSeenEnRoute = tag.getInt("HostilesEnRoute");
        try
        {
            trait = CustomerTrait.valueOf(tag.getString("Trait"));
        }
        catch (IllegalArgumentException e)
        {
            trait = CustomerTrait.NONE;
        }
        awareOfOwnerId = tag.hasUUID("AwareOfOwner") ? tag.getUUID("AwareOfOwner") : null;
        wanderExpiryTick = tag.getLong("WanderExpiryTick");
        haggling = tag.getBoolean("Haggling");
        haggledTotalCents = tag.getInt("HaggledTotalCents");
        homeGovernmentId = tag.hasUUID("HomeGovernment") ? tag.getUUID("HomeGovernment") : null;

        basket.clear();
        CompoundTag basketTag = tag.getCompound("Basket");
        for (String key : basketTag.getAllKeys())
        {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null)
                basket.put(id, basketTag.getInt(key));
        }
    }

    // Always tries to walk all the way to the front (the queue point itself), stopping only where an actual,
    // currently-alive customer ahead of it physically blocks the way - recomputed fresh every check, never a
    // fixed/reserved slot number. Flips TRAVELING -> WAITING on arrival.
    private class WalkToQueueGoal extends Goal
    {
        // Distance (in blocks) below which a move is treated as a short in-line shuffle rather than real
        // travel - see the direct-nudge note below.
        private static final double SHUFFLE_RANGE = 3.0;

        private int recalcCooldown = 0;
        private BlockPos cachedTarget;

        WalkToQueueGoal()
        {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse()
        {
            return queueAnchorPos != null && (state == State.TRAVELING || state == State.WAITING);
        }

        @Override
        public boolean canContinueToUse()
        {
            return canUse();
        }

        @Override
        public void tick()
        {
            // Recomputing who's ahead is throttled (it's a small entity scan), but stepping toward whatever
            // the last computed target was happens every tick below - decoupling the two is what lets the
            // direct nudge (for short in-line moves) actually move smoothly instead of once per cooldown.
            if (recalcCooldown-- <= 0)
            {
                recalcCooldown = 10;
                int ahead = countCustomersAheadInQueue();
                cachedTarget = queueAnchorPos.relative(queueFacing.getOpposite(), ahead);

                if (ahead == 0 && becameFrontAtTick < 0)
                    becameFrontAtTick = level().getGameTime();
                else if (ahead != 0)
                    becameFrontAtTick = -1;
            }
            if (cachedTarget == null)
                return;

            double tx = cachedTarget.getX() + 0.5;
            double ty = cachedTarget.getY();
            double tz = cachedTarget.getZ() + 0.5;
            double distSq = distanceToSqr(tx, ty, tz);

            // Slots are exactly 1 block apart, so this must stay well under distSq 1.0 - otherwise a customer
            // sitting at their old slot when the person ahead leaves is already "close enough" to their new
            // (one-block-closer) slot and never steps up, leaving a permanent gap in the line.
            if (distSq <= 0.36)
            {
                getNavigation().stop();
                if (state == State.TRAVELING)
                    state = State.WAITING;
                getLookControl().setLookAt(queueAnchorPos.getX() + 0.5, getEyeY(), queueAnchorPos.getZ() + 0.5);
                return;
            }

            if (state == State.WAITING)
                state = State.TRAVELING;

            if (distSq <= SHUFFLE_RANGE * SHUFFLE_RANGE)
            {
                // A short in-line step is always a straight, obstacle-free hop of a block or so - asking full
                // pathfinding to (re)start one once the mob's already stopped and settled is unreliable (a
                // vanilla PathNavigation quirk: very short repaths often silently fail to produce a path), so
                // this just nudges velocity toward the slot directly every tick instead.
                double dx = tx - getX();
                double dz = tz - getZ();
                double horizLen = Math.sqrt(dx * dx + dz * dz);
                if (horizLen > 1.0e-4)
                {
                    double step = Math.min(horizLen, 0.13) / horizLen;
                    setDeltaMovement(dx * step, getDeltaMovement().y, dz * step);
                    getLookControl().setLookAt(tx, getEyeY(), tz);
                }
            }
            else
            {
                getNavigation().moveTo(tx, ty, tz, 0.5);
            }
        }
    }

    // Plain aimless wandering, active only while state == WANDERING - written directly against
    // LandRandomPos/navigation (the same utility vanilla's own RandomStrollGoal uses internally) rather than
    // wrapping that goal, since composing it produced NPCs that just stood still: RandomStrollGoal tracks its
    // own "was I just doing something else" state internally, which gets confused when driven indirectly like
    // that instead of being the goal selector's direct pick.
    private class WanderGoal extends Goal
    {
        private int recalcCooldown = 0;

        WanderGoal()
        {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse()
        {
            return state == State.WANDERING;
        }

        @Override
        public boolean canContinueToUse()
        {
            return state == State.WANDERING;
        }

        @Override
        public void tick()
        {
            if (recalcCooldown-- > 0)
                return;

            if (!getNavigation().isDone())
            {
                recalcCooldown = 10;
                return;
            }

            // Paused between walks (not constantly on the move) reads as someone actually looking around,
            // rather than beelining from spot to spot.
            recalcCooldown = 40 + random.nextInt(60);
            Vec3 target = LandRandomPos.getPos(CustomerNpc.this, 10, 7);
            if (target != null)
                getNavigation().moveTo(target.x, target.y, target.z, 0.5);
        }
    }
}
