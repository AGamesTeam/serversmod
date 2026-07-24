package com.andruspro6446.servermod.customer;

import com.andruspro6446.servermod.Config;
import com.andruspro6446.servermod.ServerMod;
import com.andruspro6446.servermod.block.BusinessSignBlockEntity;
import com.andruspro6446.servermod.business.BarrelPos;
import com.andruspro6446.servermod.business.BarrelStock;
import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.business.BusinessTypes;
import com.andruspro6446.servermod.business.Order;
import com.andruspro6446.servermod.business.QueuePos;
import com.andruspro6446.servermod.business.SalesTax;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.review.Review;
import com.andruspro6446.servermod.review.ReviewGenerator;
import com.andruspro6446.servermod.review.ReviewSource;
import com.andruspro6446.servermod.territory.City;
import com.andruspro6446.servermod.territory.Country;
import com.andruspro6446.servermod.territory.Government;
import com.andruspro6446.servermod.territory.LandClaim;
import com.andruspro6446.servermod.territory.TerritoryData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// Runtime-only (no SavedData): live CustomerNpc entities are the source of truth for who's queued where and
// what they're carrying, so a restart just needs everything re-derived by scanning loaded entities - nothing
// to rebuild from scratch. Owns the whole customer lifecycle: rolling spawns for eligible businesses,
// recomputing each queue's line order every tick, timing out customers nobody ever served, and the
// chat-dialogue purchase flow (see presentOffer/resolveAccept/resolveDecline, driven by CustomerNpc.mobInteract
// and the "/business npcaccept|npcdecline" commands).
public final class CustomerNpcManager
{
    private static final Random RANDOM = new Random();

    private static final String[] NAME_POOL = {
            "Alex", "Jordan", "Casey", "Riley", "Morgan", "Taylor", "Avery", "Quinn",
            "Skyler", "Reese", "Rowan", "Sawyer", "Dakota", "Emerson", "Finley", "Harper"
    };

    private static final int VILLAGE_CHECK_RADIUS = 48;
    // If any nearby villager is a farmer, a business selling any of these gets its basket selection biased
    // toward them - a rough stand-in for "farmers specifically want seeds/bonemeal from a shop near their village."
    private static final Set<ResourceLocation> FARM_GOODS = Set.of(
            new ResourceLocation("minecraft:wheat_seeds"), new ResourceLocation("minecraft:beetroot_seeds"),
            new ResourceLocation("minecraft:melon_seeds"), new ResourceLocation("minecraft:pumpkin_seeds"),
            new ResourceLocation("minecraft:bone_meal"), new ResourceLocation("minecraft:wheat"),
            new ResourceLocation("minecraft:carrot"), new ResourceLocation("minecraft:potato"),
            new ResourceLocation("minecraft:bread"));

    // Ambient wanderers: how far they'll spawn from a random online player, how long they'll wander before
    // giving up if they never find/recall anywhere to shop, how far they can spot a Business Sign, the chance
    // one already "knows" a business at spawn, and the chance-per-check it acts on that knowledge (checked
    // once per tickQueues cycle, ~1/second).
    private static final int WANDERER_SPAWN_RADIUS = 60;
    private static final long WANDER_LIFESPAN_TICKS = 12000; // 10 minutes
    private static final int SIGN_SIGHT_RADIUS = 24;
    private static final double WANDERER_PRE_AWARE_CHANCE = 0.25;
    private static final double WANDERER_DECISION_CHANCE_PER_CHECK = 0.15;

    // Chance a freshly-spawned customer is recognized as a business's own tracked regular instead of getting
    // a brand new name (see BusinessData.pickRegularName), and the chance any given customer tries to haggle
    // the price down a bit before accepting (see presentOffer/resolveHaggleAccept).
    private static final double REGULAR_REUSE_CHANCE = 0.3;
    private static final double HAGGLE_CHANCE = 0.15;
    private static final double HAGGLE_DISCOUNT_MIN = 0.10;
    private static final double HAGGLE_DISCOUNT_MAX = 0.25;

    private CustomerNpcManager()
    {
    }

    // A business only counts as a place customers can find at all if it's actually open for business right
    // now - listed, active, staffed with a queue/barrel, within hours, discoverable (sign/advert/public
    // square), and licensed everywhere it's required to be. Shared by the normal spawn roll and the ambient
    // wanderer "already knows"/"spotted a sign" checks.
    private static boolean isEligibleForCustomers(MinecraftServer server, BusinessData data, Business business)
    {
        if (!business.listed || business.status == Business.Status.SUSPENDED || !business.npcCustomersEnabled)
            return false;
        if (business.queuePoints.isEmpty() || business.barrels.isEmpty())
            return false;
        if (!data.isOpenNow(business))
            return false;
        if (!data.isDiscoverable(server, business))
            return false;
        for (Government government : SalesTax.applicableGovernments(server, business))
            if (government.licenseFeeCents > 0 && !business.licensedGovernmentIds.contains(government.id))
                return false;
        return true;
    }

    // ---------- ticking ----------

    // Called roughly once per second from ServerMod's tick loop: gives up on any customer that's been waiting
    // at the front too long. Queue order itself needs no recomputing here at all - each customer works out
    // whether it's at the front live, every time it matters (see CustomerNpc.isFrontOfLine).
    public static void tickQueues(MinecraftServer server)
    {
        List<CustomerNpc> all = scanAllCustomerNpcs(server);
        tickClosedStoreCheck(server, all);
        tickEmployeeService(server, all);
        tickTimeouts(server, all);
        tickWanderDecisions(server, all);
        tickEmployeeVisuals(server);
    }

    // Keeps each business's employee visual(s) - one per queue point, standing at the counter - in sync with
    // Business.employeeHired and the current set of queue points. Fully reconciled from scratch every call
    // (cheap: a handful of businesses/queue points at most) rather than tracked through every place hiring or
    // queue points could change, so it can never drift out of sync with either.
    private static void tickEmployeeVisuals(MinecraftServer server)
    {
        BusinessData data = BusinessData.get(server);

        Set<QueuePos> shouldHaveEmployee = new HashSet<>();
        for (Business business : data.getAll())
            if (business.employeeHired)
                shouldHaveEmployee.addAll(business.queuePoints);

        Map<QueuePos, EmployeeNpc> existing = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels())
            for (Entity entity : level.getAllEntities())
                if (entity instanceof EmployeeNpc employee && employee.getQueuePos() != null)
                    existing.putIfAbsent(employee.getQueuePos(), employee);

        for (Map.Entry<QueuePos, EmployeeNpc> entry : existing.entrySet())
            if (!shouldHaveEmployee.contains(entry.getKey()))
                entry.getValue().discard();

        for (QueuePos queuePos : shouldHaveEmployee)
        {
            if (existing.containsKey(queuePos))
                continue;
            ServerLevel level = server.getLevel(queuePos.dimension());
            if (level != null)
                spawnEmployee(level, queuePos);
        }
    }

    // Stands one block past the counter from the customers' side, facing back down the line.
    private static void spawnEmployee(ServerLevel level, QueuePos queuePos)
    {
        BlockPos pos = queuePos.pos().relative(queuePos.facing(), 1);
        if (!level.hasChunkAt(pos))
            return;

        EmployeeNpc employee = ServerMod.EMPLOYEE_NPC.get().create(level);
        if (employee == null)
            return;

        employee.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, queuePos.facing().getOpposite().toYRot(), 0.0F);
        employee.setQueuePos(queuePos);
        level.addFreshEntity(employee);
    }

    // A customer that spawned while a business was open but arrives (or is still waiting) after it's since
    // closed - hours ran out, or the owner manually closed up - checks the sign, leaves without buying, and
    // leaves a review about it instead. Doesn't interrupt an active dialogue (BEING_SERVED); that's left to
    // resolve on its own via resolveAccept/resolveDecline.
    private static void tickClosedStoreCheck(MinecraftServer server, List<CustomerNpc> all)
    {
        BusinessData data = BusinessData.get(server);
        for (CustomerNpc npc : all)
        {
            if (npc.getState() != CustomerNpc.State.TRAVELING && npc.getState() != CustomerNpc.State.WAITING)
                continue;

            Business business = data.getByOwnerAndType(npc.getBusinessOwnerId(), BusinessTypes.SHOP_ID);
            if (business == null || data.isOpenNow(business))
                continue;

            Review review = ReviewGenerator.generate(server, business, npc.getCustomerDisplayName(), ReviewGenerator.Outcome.CLOSED,
                    npc.getBasket(), 0, 0, npc.getDamageTakenEnRoute(), npc.getHostilesSeenEnRoute(), npc.getTrait());
            data.addReview(business, review);
            npc.leaveWithPoof();
        }
    }

    // Called every `npcSpawnIntervalMinutes` from ServerMod's tick loop: rolls a spawn attempt for each
    // eligible listed business, following the same "re-read live settings each check" convention the old
    // simulated system used.
    public static void tickSpawning(MinecraftServer server)
    {
        BusinessData data = BusinessData.get(server);
        tickTheftRisk(server, data);

        if (!data.physicalNpcEnabled())
            return;

        List<CustomerNpc> all = scanAllCustomerNpcs(server);
        Map<UUID, Integer> liveCountByOwner = new LinkedHashMap<>();
        Map<QueuePos, Integer> liveCountByQueue = new LinkedHashMap<>();
        for (CustomerNpc npc : all)
        {
            liveCountByOwner.merge(npc.getBusinessOwnerId(), 1, Integer::sum);
            QueuePos qp = npc.getQueuePos();
            if (qp != null)
                liveCountByQueue.merge(qp, 1, Integer::sum);
        }

        for (Business business : data.getAll())
        {
            if (!isEligibleForCustomers(server, data, business))
                continue;

            int capacity = business.queuePoints.size() * Config.customerQueueLineCapacity;
            if (liveCountByOwner.getOrDefault(business.ownerId, 0) >= capacity)
                continue;

            Map<ResourceLocation, Integer> stock = BarrelStock.barrelStock(server, business);
            List<ResourceLocation> candidates = business.listingPricesCents.entrySet().stream()
                    .filter(e -> e.getValue() > 0 && stock.getOrDefault(e.getKey(), 0) > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            if (candidates.isEmpty())
                continue;

            QueuePos target = pickLeastOccupiedQueue(business, liveCountByQueue);
            if (target == null)
                continue;
            ServerLevel level = server.getLevel(target.dimension());
            if (level == null)
                continue;

            double reviewScore = data.recentReviewScore(business, Config.reviewRecencyWindowHours * 3600_000L);
            double reviewMultiplier = scoreToMultiplier(reviewScore);
            boolean farmerNearby = nearbyFarmerVillager(level, target.pos());
            double villageMultiplier = nearbyVillagerCount(level, target.pos()) > 0 ? 1.3 : 1.0;
            double timeMultiplier = level.isDay() ? 1.0 : 0.6;
            double weatherMultiplier = level.isThundering() ? 0.5 : level.isRaining() ? 0.75 : 1.0;

            if (RANDOM.nextDouble() > 0.85 * reviewMultiplier * villageMultiplier * timeMultiplier * weatherMultiplier)
                continue;

            trySpawnCustomer(server, level, business, target, candidates, stock, data.npcBaseMaxSpendCents(),
                    reviewMultiplier, data.npcSpawnRadiusBlocks(), farmerNearby);
        }
    }

    // A storefront not protected by the owner's own claimed land can rarely get robbed instead of bought
    // from - pulled straight from a random stocked barrel, no payment. A Guard Dog upgrade cuts the chance
    // down sharply. Runs on the same interval as the normal customer spawn roll; off by default.
    private static void tickTheftRisk(MinecraftServer server, BusinessData data)
    {
        if (!data.theftEnabled())
            return;

        for (Business business : data.getAll())
        {
            if (!business.listed || business.status == Business.Status.SUSPENDED || business.barrels.isEmpty())
                continue;
            if (isProtectedByOwnLandClaim(server, business))
                continue;

            double chance = data.theftChancePercent() / 100.0 * (business.hasGuardDog ? 0.2 : 1.0);
            // Operating out of territory currently at odds with wherever this business is taxed makes it a
            // much easier target - see SalesTax.inTradeWarZone.
            if (SalesTax.inTradeWarZone(server, business))
                chance *= 3.0;
            if (RANDOM.nextDouble() > chance)
                continue;

            Map<ResourceLocation, Integer> stock = BarrelStock.barrelStock(server, business);
            List<ResourceLocation> stocked = stock.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
            if (stocked.isEmpty())
                continue;

            ResourceLocation item = stocked.get(RANDOM.nextInt(stocked.size()));
            int available = stock.get(item);
            int stolen = 1 + RANDOM.nextInt(available);
            if (!BarrelStock.removeFromBarrels(server, business, item, stolen))
                continue;

            ServerPlayer owner = server.getPlayerList().getPlayer(business.ownerId);
            if (owner != null)
                owner.sendSystemMessage(Component.literal("A shifty-looking customer swiped " + stolen + "x " + item
                        + " from your unguarded storefront!").withStyle(ChatFormatting.RED));
        }
    }

    private static boolean isProtectedByOwnLandClaim(MinecraftServer server, Business business)
    {
        TerritoryData territory = TerritoryData.get(server);
        for (BarrelPos barrel : business.barrels)
        {
            LandClaim claim = territory.findLandClaimAt(barrel.dimension(), barrel.pos().getX(), barrel.pos().getZ());
            if (claim != null && claim.ownerId.equals(business.ownerId))
                return true;
        }
        return false;
    }

    private static int nearbyVillagerCount(ServerLevel level, BlockPos center)
    {
        AABB area = new AABB(center).inflate(VILLAGE_CHECK_RADIUS);
        return level.getEntitiesOfClass(Villager.class, area).size();
    }

    private static boolean nearbyFarmerVillager(ServerLevel level, BlockPos center)
    {
        AABB area = new AABB(center).inflate(VILLAGE_CHECK_RADIUS);
        return level.getEntitiesOfClass(Villager.class, area).stream()
                .anyMatch(v -> v.getVillagerData().getProfession() == VillagerProfession.FARMER);
    }

    private static double scoreToMultiplier(double score)
    {
        double clamped = Math.max(0, Math.min(5, score));
        if (clamped <= 3.0)
            return lerp(Config.customerMinRateMultiplier, 1.0, clamped / 3.0);
        return lerp(1.0, Config.customerMaxRateMultiplier, (clamped - 3.0) / 2.0);
    }

    private static double lerp(double from, double to, double t)
    {
        return from + (to - from) * t;
    }

    private static QueuePos pickLeastOccupiedQueue(Business business, Map<QueuePos, Integer> liveCountByQueue)
    {
        QueuePos best = null;
        int bestCount = Integer.MAX_VALUE;
        for (QueuePos qp : business.queuePoints)
        {
            int count = liveCountByQueue.getOrDefault(qp, 0);
            if (count < bestCount)
            {
                best = qp;
                bestCount = count;
            }
        }
        return best;
    }

    private static void tickTimeouts(MinecraftServer server, List<CustomerNpc> all)
    {
        BusinessData data = BusinessData.get(server);
        for (CustomerNpc npc : all)
        {
            // Also covers BEING_SERVED (a dialogue was opened via presentOffer but never resolved with
            // Accept/Decline - the owner walked away, missed the chat message, etc.) - otherwise a customer
            // that got this far would be stuck waiting forever with no safety net once mobInteract stops
            // responding to further right-clicks (see CustomerNpc.mobInteract's awaitingResponse check).
            boolean frontAndIdle = (npc.getState() == CustomerNpc.State.WAITING || npc.getState() == CustomerNpc.State.BEING_SERVED)
                    && npc.isFrontOfLine();
            if (!frontAndIdle || npc.getBecameFrontAtTick() < 0)
                continue;
            long waited = npc.level().getGameTime() - npc.getBecameFrontAtTick();
            if (waited < Config.customerServiceTimeoutTicks)
                continue;

            Business business = data.getByOwnerAndType(npc.getBusinessOwnerId(), BusinessTypes.SHOP_ID);
            if (business != null)
            {
                Review review = ReviewGenerator.generate(server, business, npc.getCustomerDisplayName(), ReviewGenerator.Outcome.TIMED_OUT,
                        npc.getBasket(), 0, waited, npc.getDamageTakenEnRoute(), npc.getHostilesSeenEnRoute(), npc.getTrait());
                data.addReview(business, review);
            }
            npc.leaveWithPoof();
        }
    }

    // ---------- ambient wandering ----------

    // Called every `wandererSpawnIntervalMinutes` from ServerMod's tick loop: spawns one ambient wanderer near
    // a random online player, with no business assigned yet. Off by default.
    // Tries to spawn one wanderer near *every* online player each check (not just one lucky player picked at
    // random) - a busy multiplayer server would otherwise only ever get ambient traffic near whoever the RNG
    // happened to favor that round, leaving everyone else's area empty. Still capped overall by maxWanderers.
    public static void tickWanderers(MinecraftServer server)
    {
        BusinessData data = BusinessData.get(server);
        if (!data.wanderersEnabled())
            return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty())
            return;

        int currentWanderers = (int) scanAllCustomerNpcs(server).stream().filter(n -> n.getState() == CustomerNpc.State.WANDERING).count();
        int capacity = data.maxWanderers();
        List<Business> eligible = data.getAll().stream().filter(b -> isEligibleForCustomers(server, data, b)).toList();

        for (ServerPlayer nearPlayer : players)
        {
            if (currentWanderers >= capacity)
                break;
            if (spawnWandererNear(nearPlayer, eligible))
                currentWanderers++;
        }
    }

    private static boolean spawnWandererNear(ServerPlayer nearPlayer, List<Business> eligible)
    {
        ServerLevel level = nearPlayer.serverLevel();
        BlockPos spawnPos = findSpawnPosition(level, nearPlayer.blockPosition(), WANDERER_SPAWN_RADIUS);
        if (spawnPos == null)
            return false;

        CustomerNpc npc = ServerMod.CUSTOMER_NPC.get().create(level);
        if (npc == null)
            return false;

        npc.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, RANDOM.nextFloat() * 360.0F, 0.0F);
        npc.setState(CustomerNpc.State.WANDERING);
        npc.setSpawnedAtTick(level.getGameTime());
        npc.setWanderExpiryTick(level.getGameTime() + WANDER_LIFESPAN_TICKS);
        npc.setTrait(CustomerTrait.pickRandom(RANDOM));
        npc.setHomeGovernmentId(determineHomeGovernment(level.getServer(), level.dimension(), spawnPos));
        npc.setCustomName(Component.literal(NAME_POOL[RANDOM.nextInt(NAME_POOL.length)]));
        npc.setCustomNameVisible(true);

        // Some wanderers already have a shop in mind before they've even spotted a sign - a past customer
        // just out and about, who might decide to swing by again (see tickWanderDecisions).
        if (!eligible.isEmpty() && RANDOM.nextDouble() < WANDERER_PRE_AWARE_CHANCE)
            npc.setAwareOfOwnerId(eligible.get(RANDOM.nextInt(eligible.size())).ownerId);

        level.addFreshEntity(npc);
        return true;
    }

    // Called once per second (from tickQueues) for every currently-wandering customer: gives up (and quietly
    // vanishes, no review - nothing actually happened) once its wander lifespan runs out; otherwise either
    // rolls a chance to head to a business it already knows of, or looks for a visible Business Sign to spot.
    private static void tickWanderDecisions(MinecraftServer server, List<CustomerNpc> all)
    {
        BusinessData data = BusinessData.get(server);
        for (CustomerNpc npc : all)
        {
            if (npc.getState() != CustomerNpc.State.WANDERING)
                continue;
            if (!(npc.level() instanceof ServerLevel level))
                continue;

            if (level.getGameTime() > npc.getWanderExpiryTick())
            {
                npc.leaveWithPoof();
                continue;
            }

            UUID awareOf = npc.getAwareOfOwnerId();
            if (awareOf != null)
            {
                Business known = data.getByOwnerAndType(awareOf, BusinessTypes.SHOP_ID);
                if (known == null || !isEligibleForCustomers(server, data, known))
                {
                    npc.setAwareOfOwnerId(null);
                    continue;
                }
                if (RANDOM.nextDouble() < WANDERER_DECISION_CHANCE_PER_CHECK)
                    tryBeginShoppingTrip(server, data, level, npc, known);
                continue;
            }

            BusinessSignBlockEntity sign = findVisibleSign(level, npc, SIGN_SIGHT_RADIUS);
            if (sign == null || sign.getOwnerId() == null)
                continue;
            Business spotted = data.getByOwnerAndType(sign.getOwnerId(), BusinessTypes.SHOP_ID);
            if (spotted == null || !isEligibleForCustomers(server, data, spotted))
                continue;

            tryBeginShoppingTrip(server, data, level, npc, spotted);
        }
    }

    // Converts a wandering npc that just decided (or spotted a sign) into an active shopper - picks a queue
    // point and basket exactly like a fresh spawn would, just starting from wherever it already is instead of
    // a freshly-sampled position. If the business turns out too busy or out of stock right now, the npc just
    // keeps wandering and may try again later.
    private static void tryBeginShoppingTrip(MinecraftServer server, BusinessData data, ServerLevel level, CustomerNpc npc, Business business)
    {
        List<CustomerNpc> allNpcs = scanAllCustomerNpcs(server);
        long currentForBusiness = allNpcs.stream().filter(n -> business.ownerId.equals(n.getBusinessOwnerId())).count();
        int capacity = business.queuePoints.size() * Config.customerQueueLineCapacity;
        if (currentForBusiness >= capacity)
            return;

        Map<QueuePos, Integer> liveCountByQueue = new LinkedHashMap<>();
        for (CustomerNpc other : allNpcs)
        {
            QueuePos qp = other.getQueuePos();
            if (qp != null)
                liveCountByQueue.merge(qp, 1, Integer::sum);
        }
        QueuePos target = pickLeastOccupiedQueue(business, liveCountByQueue);
        if (target == null)
            return;

        Map<ResourceLocation, Integer> stock = BarrelStock.barrelStock(server, business);
        List<ResourceLocation> candidates = business.listingPricesCents.entrySet().stream()
                .filter(e -> e.getValue() > 0 && stock.getOrDefault(e.getKey(), 0) > 0)
                .map(Map.Entry::getKey)
                .toList();
        if (candidates.isEmpty())
            return;

        double reviewScore = data.recentReviewScore(business, Config.reviewRecencyWindowHours * 3600_000L);
        double reviewMultiplier = scoreToMultiplier(reviewScore);
        boolean farmerNearby = nearbyFarmerVillager(level, target.pos());
        Map<ResourceLocation, Integer> basket = buildBasket(business, candidates, stock, data.npcBaseMaxSpendCents(),
                reviewMultiplier * npc.getTrait().spendMultiplier, farmerNearby);
        if (basket.isEmpty())
            return;

        int totalCents = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : basket.entrySet())
            totalCents += business.listingPricesCents.get(entry.getKey()) * entry.getValue();
        totalCents = applyDiplomaticDiscount(server, business, npc.getHomeGovernmentId(), totalCents);

        npc.commitToShoppingTrip(business.ownerId, target, basket, totalCents);
    }

    // Scans loaded chunks within `radius` blocks for a Business Sign the npc has a clear line of sight to -
    // cheap enough to run per-wanderer once a second since block entities are sparse and only nearby, already
    // loaded chunks are checked.
    private static BusinessSignBlockEntity findVisibleSign(ServerLevel level, CustomerNpc npc, int radius)
    {
        BlockPos center = npc.blockPosition();
        ChunkPos npcChunk = new ChunkPos(center);
        int chunkRadius = (radius >> 4) + 1;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++)
        {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++)
            {
                LevelChunk chunk = level.getChunkSource().getChunkNow(npcChunk.x + dx, npcChunk.z + dz);
                if (chunk == null)
                    continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values())
                {
                    if (!(blockEntity instanceof BusinessSignBlockEntity sign) || sign.getOwnerId() == null)
                        continue;
                    if (sign.getBlockPos().distSqr(center) > (double) radius * radius)
                        continue;
                    if (hasLineOfSight(level, npc, sign.getBlockPos()))
                        return sign;
                }
            }
        }
        return null;
    }

    private static boolean hasLineOfSight(ServerLevel level, CustomerNpc npc, BlockPos target)
    {
        Vec3 from = npc.getEyePosition();
        Vec3 to = Vec3.atCenterOf(target);
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---------- spawning ----------

    private static void trySpawnCustomer(MinecraftServer server, ServerLevel level, Business business, QueuePos queuePos,
                                          List<ResourceLocation> candidates, Map<ResourceLocation, Integer> stock,
                                          int baseMaxSpendCents, double reviewMultiplier, int spawnRadiusBlocks, boolean farmerNearby)
    {
        BlockPos spawnPos = findSpawnPosition(level, queuePos.pos(), spawnRadiusBlocks);
        if (spawnPos == null)
            return;

        CustomerTrait trait = CustomerTrait.pickRandom(RANDOM);
        Map<ResourceLocation, Integer> basket = buildBasket(business, candidates, stock, baseMaxSpendCents,
                reviewMultiplier * trait.spendMultiplier, farmerNearby);
        if (basket.isEmpty())
            return;

        int totalCents = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : basket.entrySet())
            totalCents += business.listingPricesCents.get(entry.getKey()) * entry.getValue();

        CustomerNpc npc = ServerMod.CUSTOMER_NPC.get().create(level);
        if (npc == null)
            return;

        UUID homeGovernmentId = determineHomeGovernment(server, level.dimension(), spawnPos);
        totalCents = applyDiplomaticDiscount(server, business, homeGovernmentId, totalCents);

        npc.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, RANDOM.nextFloat() * 360.0F, 0.0F);
        npc.setBusinessOwnerId(business.ownerId);
        npc.setQueuePos(queuePos);
        npc.getBasket().putAll(basket);
        npc.setTotalCents(totalCents);
        npc.setSpawnedAtTick(level.getGameTime());
        npc.setTrait(trait);
        npc.setHomeGovernmentId(homeGovernmentId);
        npc.setCustomName(Component.literal(pickCustomerName(server, business)));
        npc.setCustomNameVisible(true);

        level.addFreshEntity(npc);
    }

    // The City or Country whose territory a point falls in, if any - city preferred over country since a
    // city usually sits inside a country's larger territory and is the more specific "home."
    private static UUID determineHomeGovernment(MinecraftServer server, ResourceKey<Level> dim, BlockPos pos)
    {
        TerritoryData territory = TerritoryData.get(server);
        City city = territory.findCityAt(dim, pos.getX(), pos.getZ());
        if (city != null)
            return city.id;
        Country country = territory.findCountryAt(dim, pos.getX(), pos.getZ());
        return country != null ? country.id : null;
    }

    // The flip side of SalesTax's foreign-tax surcharge: a customer whose home government has been
    // unilaterally declared an ally by any government that actually taxes this business gets a small discount,
    // as if the business were extending the same goodwill its government shows that ally.
    private static final double DIPLOMATIC_DISCOUNT = 0.9;

    private static int applyDiplomaticDiscount(MinecraftServer server, Business business, UUID homeGovernmentId, int totalCents)
    {
        if (homeGovernmentId == null || totalCents <= 0)
            return totalCents;
        for (Government taxing : SalesTax.applicableGovernments(server, business))
            if (taxing.alliedGovernmentIds.contains(homeGovernmentId))
                return (int) Math.round(totalCents * DIPLOMATIC_DISCOUNT);
        return totalCents;
    }

    // Occasionally reuses the name of a customer this business has already served before - see
    // BusinessData.pickRegularName - so a familiar face can recognizably return, rather than every visit
    // getting a brand new random name.
    private static String pickCustomerName(MinecraftServer server, Business business)
    {
        if (RANDOM.nextDouble() < REGULAR_REUSE_CHANCE)
        {
            String regularName = BusinessData.get(server).pickRegularName(business, RANDOM);
            if (regularName != null)
                return regularName;
        }
        return NAME_POOL[RANDOM.nextInt(NAME_POOL.length)];
    }

    private static BlockPos findSpawnPosition(ServerLevel level, BlockPos center, int radius)
    {
        for (int attempt = 0; attempt < 20; attempt++)
        {
            int dx = RANDOM.nextInt(radius * 2 + 1) - radius;
            int dz = RANDOM.nextInt(radius * 2 + 1) - radius;
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            if (!level.hasChunkAt(new BlockPos(x, 0, z)))
                continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (isSafeSpawn(level, pos))
                return pos;
        }
        return null;
    }

    private static boolean isSafeSpawn(ServerLevel level, BlockPos pos)
    {
        // MOTION_BLOCKING_NO_LEAVES counts water as "blocking", so over open ocean `pos` lands in the air
        // just above the surface and passes the air/lava checks below trivially - the ground itself (`below`)
        // must be checked for water too, not just lava, or customers spawn floating on top of the sea.
        BlockPos below = pos.below();
        if (!level.getBlockState(pos).isAir()
                || !level.getBlockState(pos.above()).isAir()
                || level.getBlockState(below).isAir()
                || !level.getFluidState(below).isEmpty()
                || !level.getFluidState(pos).isEmpty())
            return false;

        // Never spawn on someone's claimed land (individual player parcels) - a City or Country claim doesn't
        // block spawning, only a LandClaim does; those are public/shared territory in a way a personal land
        // claim isn't.
        return TerritoryData.get(level.getServer()).findLandClaimAt(level.dimension(), pos.getX(), pos.getZ()) == null;
    }

    private static Map<ResourceLocation, Integer> buildBasket(Business business, List<ResourceLocation> candidates,
                                                               Map<ResourceLocation, Integer> stock, int baseMaxSpendCents,
                                                               double multiplier, boolean farmerNearby)
    {
        int maxSpend = (int) (baseMaxSpendCents * multiplier);
        Map<ResourceLocation, Integer> basket = new LinkedHashMap<>();
        if (maxSpend <= 0)
            return basket;

        // A farmer villager nearby biases the basket toward farm goods (seeds, bonemeal, produce) this
        // business happens to sell, ahead of everything else - see FARM_GOODS.
        List<ResourceLocation> shuffled;
        if (farmerNearby && candidates.stream().anyMatch(FARM_GOODS::contains))
        {
            List<ResourceLocation> farmFirst = new ArrayList<>(candidates.stream().filter(FARM_GOODS::contains).toList());
            List<ResourceLocation> rest = new ArrayList<>(candidates.stream().filter(c -> !FARM_GOODS.contains(c)).toList());
            Collections.shuffle(farmFirst, RANDOM);
            Collections.shuffle(rest, RANDOM);
            shuffled = new ArrayList<>(farmFirst);
            shuffled.addAll(rest);
        }
        else
        {
            shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled, RANDOM);
        }
        int itemTypes = 1 + RANDOM.nextInt(Math.min(3, shuffled.size()));

        int remainingBudget = maxSpend;
        for (int i = 0; i < itemTypes && i < shuffled.size(); i++)
        {
            ResourceLocation item = shuffled.get(i);
            int price = business.listingPricesCents.get(item);
            int available = stock.getOrDefault(item, 0);
            int affordable = price > 0 ? remainingBudget / price : 0;
            int maxQty = Math.min(available, affordable);
            if (maxQty <= 0)
                continue;

            int qty = 1 + RANDOM.nextInt(maxQty);
            basket.put(item, qty);
            remainingBudget -= qty * price;
        }
        return basket;
    }

    // ---------- chat dialogue ----------

    // Sends the owner the purchase offer as clickable chat text. Re-validates the basket against current
    // stock/prices first, since time may have passed since the NPC joined the queue.
    public static void presentOffer(ServerLevel level, CustomerNpc npc, ServerPlayer player)
    {
        MinecraftServer server = level.getServer();
        BusinessData data = BusinessData.get(server);
        Business business = data.getByOwnerAndType(npc.getBusinessOwnerId(), BusinessTypes.SHOP_ID);
        if (business == null)
        {
            npc.leaveWithPoof();
            return;
        }

        if (!revalidateBasket(server, business, npc))
        {
            player.sendSystemMessage(Component.literal(npc.getCustomerDisplayName() + " looks disappointed - you're all out of what they wanted."));
            npc.leaveWithPoof();
            return;
        }

        npc.setAwaitingResponse(true);
        npc.setState(CustomerNpc.State.BEING_SERVED);

        String summary = summarizeBasket(npc.getBasket());
        Component decline = Component.literal("[Decline]").setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/business npcdecline " + npc.getUUID()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Turn them away"))));

        boolean haggling = RANDOM.nextDouble() < HAGGLE_CHANCE;
        npc.setHaggling(haggling);

        if (haggling)
        {
            double discount = HAGGLE_DISCOUNT_MIN + RANDOM.nextDouble() * (HAGGLE_DISCOUNT_MAX - HAGGLE_DISCOUNT_MIN);
            int haggledCents = Math.max(1, (int) Math.round(npc.getTotalCents() * (1 - discount)));
            npc.setHaggledTotalCents(haggledCents);

            Component acceptLower = Component.literal("[Accept " + Money.format(haggledCents) + "]").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/business npchaggle " + npc.getUUID()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Take their offer"))));
            Component holdFirm = Component.literal("[Hold Firm " + Money.format(npc.getTotalCents()) + "]").setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/business npcaccept " + npc.getUUID()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Sell at the full listed price anyway"))));

            player.sendSystemMessage(Component.literal("[ServerMod] ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(npc.getCustomerDisplayName() + " wants to buy " + summary + " - listed at "
                            + Money.format(npc.getTotalCents()) + ", but offers " + Money.format(haggledCents) + " instead.")));
            player.sendSystemMessage(acceptLower.copy().append(Component.literal("   ")).append(holdFirm)
                    .append(Component.literal("   ")).append(decline));
        }
        else
        {
            Component accept = Component.literal("[Accept]").setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/business npcaccept " + npc.getUUID()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Sell to them"))));

            player.sendSystemMessage(Component.literal("[ServerMod] ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(npc.getCustomerDisplayName() + " would like to buy " + summary + " - " + Money.format(npc.getTotalCents()) + " total.")));
            player.sendSystemMessage(accept.copy().append(Component.literal("   ")).append(decline));
        }
    }

    // Re-validates an npc's basket against current stock/prices, trimming it (and its total) down in place -
    // shared by presentOffer (chat dialogue) and tickEmployeeService (auto-serve while the owner's offline).
    // Returns false if nothing in the basket is available any more.
    private static boolean revalidateBasket(MinecraftServer server, Business business, CustomerNpc npc)
    {
        Map<ResourceLocation, Integer> stock = BarrelStock.barrelStock(server, business);
        Map<ResourceLocation, Integer> validBasket = new LinkedHashMap<>();
        int totalCents = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : npc.getBasket().entrySet())
        {
            Integer price = business.listingPricesCents.get(entry.getKey());
            if (price == null || price <= 0)
                continue;
            int qty = Math.min(entry.getValue(), stock.getOrDefault(entry.getKey(), 0));
            if (qty <= 0)
                continue;
            validBasket.put(entry.getKey(), qty);
            totalCents += price * qty;
        }
        if (validBasket.isEmpty())
            return false;

        npc.getBasket().clear();
        npc.getBasket().putAll(validBasket);
        npc.setTotalCents(totalCents);
        return true;
    }

    private static String summarizeBasket(Map<ResourceLocation, Integer> basket)
    {
        StringBuilder sb = new StringBuilder();
        basket.forEach((item, qty) -> {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(qty).append("x ").append(item);
        });
        return sb.toString();
    }

    // Invoked from "/business npcaccept <uuid>", clicked from the chat dialogue above.
    public static String resolveAccept(MinecraftServer server, ServerPlayer player, UUID npcId)
    {
        CustomerNpc npc = findNpc(server, npcId);
        if (npc == null)
            return "That customer is no longer here.";

        BusinessData data = BusinessData.get(server);
        Business business = requireOwnedByPlayer(data, npc, player);
        if (business == null)
            return "That's not your customer to serve.";

        String message = completeSale(server, data, business, npc);
        return message != null ? message : "Stock just changed - you no longer have enough to fulfil this order.";
    }

    // Invoked from "/business npchaggle <uuid>", clicked from the chat dialogue when a haggling customer's
    // lower offer is accepted - completes the sale at their proposed price instead of the full listed total.
    public static String resolveHaggleAccept(MinecraftServer server, ServerPlayer player, UUID npcId)
    {
        CustomerNpc npc = findNpc(server, npcId);
        if (npc == null)
            return "That customer is no longer here.";
        if (!npc.isHaggling())
            return "That customer isn't haggling - use Accept instead.";

        BusinessData data = BusinessData.get(server);
        Business business = requireOwnedByPlayer(data, npc, player);
        if (business == null)
            return "That's not your customer to serve.";

        npc.setTotalCents(npc.getHaggledTotalCents());
        String message = completeSale(server, data, business, npc);
        return message != null ? message : "Stock just changed - you no longer have enough to fulfil this order.";
    }

    // Removes stock, pays the business, logs the order, and generates a review - shared by a player manually
    // accepting (resolveAccept) and an employee auto-serving while the owner's offline (tickEmployeeService).
    // Returns null (leaving the npc WAITING to be re-served) if stock changed since the basket was validated.
    private static String completeSale(MinecraftServer server, BusinessData data, Business business, CustomerNpc npc)
    {
        for (Map.Entry<ResourceLocation, Integer> entry : npc.getBasket().entrySet())
        {
            if (!BarrelStock.removeFromBarrels(server, business, entry.getKey(), entry.getValue()))
            {
                npc.setAwaitingResponse(false);
                npc.setState(CustomerNpc.State.WAITING);
                return null;
            }
        }

        // The customer still pays the full listed price - any government sales tax comes out of the
        // business's own cut, not the buyer's.
        data.adjustBalance(business, SalesTax.applyAndGetNet(server, business, npc.getTotalCents()));
        data.addOrder(business, new Order(UUID.randomUUID(), ReviewSource.NPC, npc.getCustomerDisplayName(),
                summarizeBasket(npc.getBasket()), npc.getTotalCents(), System.currentTimeMillis()));

        // Regulars are tracked by base name (not the trait-prefixed display name - traits re-roll every visit
        // and shouldn't affect whether this reads as "the same customer"). More visits = more benefit of the
        // doubt in their review, same idea as the Awning/Loyalty Card upgrades.
        int visitCount = data.recordRegularVisit(business, npc.getBaseName());
        double regularBonus = Math.min(0.5, (visitCount - 1) * 0.05);
        double extraLeniency = regularBonus + (business.hasAwning ? 0.15 : 0);
        if (business.hasLoyaltyCard && data.tickLoyaltyCounter(business))
            extraLeniency += 0.3;
        double priceSensitivityScale = business.hasPriceBoard ? 0.7 : 1.0;

        long waited = npc.getBecameFrontAtTick() >= 0 ? npc.level().getGameTime() - npc.getBecameFrontAtTick() : 0;
        Boolean isLocalCustomer = npc.getHomeGovernmentId() == null ? null
                : SalesTax.applicableGovernments(server, business).stream().anyMatch(g -> g.id.equals(npc.getHomeGovernmentId()));
        boolean crossedTradeWarZone = SalesTax.inTradeWarZone(server, business);
        Review review = ReviewGenerator.generate(server, business, npc.getCustomerDisplayName(), ReviewGenerator.Outcome.COMPLETED,
                npc.getBasket(), npc.getTotalCents(), waited, npc.getDamageTakenEnRoute(), npc.getHostilesSeenEnRoute(),
                npc.getTrait(), new ReviewGenerator.ReviewModifiers(extraLeniency, priceSensitivityScale), isLocalCustomer, crossedTradeWarZone);
        data.addReview(business, review);

        // An Influencer visit that went well is worth free advertising - as if they'd posted about the place.
        if (npc.getTrait().boostsVisibilityOnComplete && review.stars >= 3.5)
            data.runAdvert(business, data.advertDurationHours());

        String message = "Sold to " + npc.getCustomerDisplayName() + " for " + Money.format(npc.getTotalCents()) + ".";
        npc.leaveWithPoof();
        return message;
    }

    // Auto-serves any front-of-line customer whenever the business has a hired employee - see
    // Business.employeeHired. That's the whole point of hiring someone: the owner never has to personally
    // handle every single transaction, online or not. Only steps in for WAITING customers (not ones already
    // BEING_SERVED - if the owner already opened the chat dialogue themselves, the employee leaves it to them;
    // the timeout safety net still covers a dialogue that's opened and then abandoned).
    private static void tickEmployeeService(MinecraftServer server, List<CustomerNpc> all)
    {
        BusinessData data = BusinessData.get(server);
        for (CustomerNpc npc : all)
        {
            if (npc.getState() != CustomerNpc.State.WAITING || !npc.isFrontOfLine())
                continue;

            Business business = data.getByOwnerAndType(npc.getBusinessOwnerId(), BusinessTypes.SHOP_ID);
            if (business == null || !business.employeeHired)
                continue;

            if (!revalidateBasket(server, business, npc))
            {
                npc.leaveWithPoof();
                continue;
            }
            completeSale(server, data, business, npc);
        }
    }

    // Invoked from "/business npcdecline <uuid>", clicked from the chat dialogue above.
    public static String resolveDecline(MinecraftServer server, ServerPlayer player, UUID npcId)
    {
        CustomerNpc npc = findNpc(server, npcId);
        if (npc == null)
            return "That customer is no longer here.";

        BusinessData data = BusinessData.get(server);
        Business business = requireOwnedByPlayer(data, npc, player);
        if (business == null)
            return "That's not your customer to turn away.";

        Review review = ReviewGenerator.generate(server, business, npc.getCustomerDisplayName(), ReviewGenerator.Outcome.DECLINED,
                npc.getBasket(), 0, 0, npc.getDamageTakenEnRoute(), npc.getHostilesSeenEnRoute(), npc.getTrait());
        data.addReview(business, review);

        String message = "Turned " + npc.getCustomerDisplayName() + " away.";
        npc.leaveWithPoof();
        return message;
    }

    private static Business requireOwnedByPlayer(BusinessData data, CustomerNpc npc, ServerPlayer player)
    {
        if (npc.getBusinessOwnerId() == null || !npc.getBusinessOwnerId().equals(player.getUUID()))
            return null;
        return data.getByOwnerAndType(npc.getBusinessOwnerId(), BusinessTypes.SHOP_ID);
    }

    // ---------- lookups ----------

    private static CustomerNpc findNpc(MinecraftServer server, UUID id)
    {
        for (CustomerNpc npc : scanAllCustomerNpcs(server))
            if (npc.getUUID().equals(id))
                return npc;
        return null;
    }

    private static List<CustomerNpc> scanAllCustomerNpcs(MinecraftServer server)
    {
        List<CustomerNpc> all = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels())
            for (Entity entity : level.getAllEntities())
                if (entity instanceof CustomerNpc npc)
                    all.add(npc);
        return all;
    }
}
