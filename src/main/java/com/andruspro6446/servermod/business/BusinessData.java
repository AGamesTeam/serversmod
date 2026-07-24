package com.andruspro6446.servermod.business;

import com.andruspro6446.servermod.api.BusinessTypeRegistry;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.review.Review;
import com.andruspro6446.servermod.review.ReviewSource;
import com.andruspro6446.servermod.territory.Government;
import com.andruspro6446.servermod.territory.PublicSquare;
import com.andruspro6446.servermod.territory.TerritoryData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Tracks every player-owned business - balance, virtual stock, storefront listing, its Sell Barrels - plus
// the global business rules an admin controls: registration/weekly fees, the Sell Barrel daily fee,
// manufacturing recipes, the missed-payment policy, and whether players get to see wholesale market prices.
// One business per owner. All access must happen on the main server thread.
public class BusinessData extends SavedData
{
    private static final String DATA_NAME = "servermod_business";
    public static final long BILLING_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000; // 7 real days, i.e. "weekly"

    private final Map<UUID, Business> businesses = new LinkedHashMap<>();
    private final List<Recipe> recipes = new ArrayList<>();

    private int registrationFeeCents = 50000;
    private int weeklyFeeCents = 5000;
    private int sellBarrelDailyFeeCents = 100;
    private MissedPaymentPolicy missedPaymentPolicy = MissedPaymentPolicy.SUSPEND;
    private int graceDays = 3;
    private boolean showMarketPricesToPlayers = false;

    // Physical customer NPCs: spawn near a listed business, walk to a queue point, and wait to be served by
    // the owner (see customer.CustomerNpcManager). Off by default, same posture as the old simulated system
    // this replaces. Spawn rate and per-visit spend are further scaled by each business's recent review score.
    private boolean physicalNpcEnabled = false;
    private int npcSpawnIntervalMinutes = 5;
    private int npcBaseMaxSpendCents = 5000;
    // Admin-configurable max distance (in blocks) a customer NPC can spawn from one of a business's queue
    // points - was previously a Forge config value (requiring a restart to change); moved here so it's
    // adjustable live from the admin web panel, like every other NPC-customer setting.
    private int npcSpawnRadiusBlocks = 300;
    // Every business gets a Business Sign (see block.BusinessSignBlock) on registration, showing customers its
    // hours and current open/closed status - and used by customer NPCs when deciding whether to complain
    // about arriving at a closed shop. This is its own recurring fee, expressed as a monthly amount but folded
    // into the existing weekly billing cycle like everything else (see amountDueCents).
    private int signFeeCentsPerMonth = 100;
    // Cost of a single advertising campaign, and how long it keeps a business discoverable - see runAdvert.
    private int advertCostCents = 1000;
    private int advertDurationHours = 24;
    private static final int MAX_ORDERS_KEPT = 200;

    // Ambient foot traffic: NPCs that spawn with no destination and wander until they either recall a
    // business they already know of, or spot a Business Sign - see customer.CustomerNpcManager.tickWanderers.
    // Off by default, same posture as every other NPC-customer setting.
    private boolean wanderersEnabled = false;
    private int wandererSpawnIntervalMinutes = 1;
    private int maxWanderers = 30;

    // Weekly wage for a hired employee (see Business.employeeHired / customer.CustomerNpcManager.tickEmployeeService).
    private int employeeWageCentsPerWeek = 2000;

    // One-time storefront upgrade costs (see Business.hasAwning etc.).
    private int awningCostCents = 5000;
    private int priceBoardCostCents = 5000;
    private int loyaltyCardCostCents = 10000;
    private int guardDogCostCents = 15000;
    public static final int LOYALTY_CARD_INTERVAL = 5;
    public static final int MAX_REGULARS_TRACKED = 20;

    // A storefront not protected by the owner's own claimed land can rarely get robbed instead of bought
    // from - see customer.CustomerNpcManager.tickTheftRisk. Off by default.
    private boolean theftEnabled = false;
    private double theftChancePercent = 2.0;

    public static BusinessData create()
    {
        return new BusinessData();
    }

    public static BusinessData load(CompoundTag tag)
    {
        BusinessData data = new BusinessData();

        if (tag.contains("registrationFeeCents"))
            data.registrationFeeCents = tag.getInt("registrationFeeCents");
        // "weeklyFeeCents" is the current key; fall back to the old "monthlyFeeCents" key so a save made
        // before the fee cadence changed from monthly to weekly doesn't silently lose an admin-set value.
        if (tag.contains("weeklyFeeCents"))
            data.weeklyFeeCents = tag.getInt("weeklyFeeCents");
        else if (tag.contains("monthlyFeeCents"))
            data.weeklyFeeCents = tag.getInt("monthlyFeeCents");
        if (tag.contains("sellBarrelDailyFeeCents"))
            data.sellBarrelDailyFeeCents = tag.getInt("sellBarrelDailyFeeCents");
        if (tag.contains("graceDays"))
            data.graceDays = tag.getInt("graceDays");
        if (tag.contains("showMarketPricesToPlayers"))
            data.showMarketPricesToPlayers = tag.getBoolean("showMarketPricesToPlayers");
        if (tag.contains("physicalNpcEnabled"))
            data.physicalNpcEnabled = tag.getBoolean("physicalNpcEnabled");
        if (tag.contains("npcSpawnIntervalMinutes"))
            data.npcSpawnIntervalMinutes = tag.getInt("npcSpawnIntervalMinutes");
        if (tag.contains("npcBaseMaxSpendCents"))
            data.npcBaseMaxSpendCents = tag.getInt("npcBaseMaxSpendCents");
        if (tag.contains("npcSpawnRadiusBlocks"))
            data.npcSpawnRadiusBlocks = tag.getInt("npcSpawnRadiusBlocks");
        if (tag.contains("signFeeCentsPerMonth"))
            data.signFeeCentsPerMonth = tag.getInt("signFeeCentsPerMonth");
        if (tag.contains("advertCostCents"))
            data.advertCostCents = tag.getInt("advertCostCents");
        if (tag.contains("advertDurationHours"))
            data.advertDurationHours = tag.getInt("advertDurationHours");
        if (tag.contains("wanderersEnabled"))
            data.wanderersEnabled = tag.getBoolean("wanderersEnabled");
        if (tag.contains("wandererSpawnIntervalMinutes"))
            data.wandererSpawnIntervalMinutes = tag.getInt("wandererSpawnIntervalMinutes");
        if (tag.contains("maxWanderers"))
            data.maxWanderers = tag.getInt("maxWanderers");
        if (tag.contains("employeeWageCentsPerWeek"))
            data.employeeWageCentsPerWeek = tag.getInt("employeeWageCentsPerWeek");
        if (tag.contains("awningCostCents"))
            data.awningCostCents = tag.getInt("awningCostCents");
        if (tag.contains("priceBoardCostCents"))
            data.priceBoardCostCents = tag.getInt("priceBoardCostCents");
        if (tag.contains("loyaltyCardCostCents"))
            data.loyaltyCardCostCents = tag.getInt("loyaltyCardCostCents");
        if (tag.contains("guardDogCostCents"))
            data.guardDogCostCents = tag.getInt("guardDogCostCents");
        if (tag.contains("theftEnabled"))
            data.theftEnabled = tag.getBoolean("theftEnabled");
        if (tag.contains("theftChancePercent"))
            data.theftChancePercent = tag.getDouble("theftChancePercent");
        try
        {
            if (tag.contains("missedPaymentPolicy"))
                data.missedPaymentPolicy = MissedPaymentPolicy.valueOf(tag.getString("missedPaymentPolicy"));
        }
        catch (IllegalArgumentException e)
        {
            data.missedPaymentPolicy = MissedPaymentPolicy.SUSPEND;
        }

        CompoundTag businessesTag = tag.getCompound("businesses");
        for (String key : businessesTag.getAllKeys())
        {
            UUID ownerId = UUID.fromString(key);
            CompoundTag bTag = businessesTag.getCompound(key);

            Business business = new Business(ownerId, bTag.getString("name"), bTag.getLong("nextBillingAtMillis"));
            if (bTag.contains("type"))
            {
                ResourceLocation typeId = ResourceLocation.tryParse(bTag.getString("type"));
                if (typeId != null)
                    business.type = typeId;
            }
            CompoundTag savedExtraData = bTag.getCompound("extraData");
            for (String extraKey : savedExtraData.getAllKeys())
                business.extraData.put(extraKey, savedExtraData.get(extraKey));
            business.balanceCents = bTag.getInt("balanceCents");
            business.listed = bTag.getBoolean("listed");
            business.graceDeadlineMillis = bTag.getLong("graceDeadlineMillis");
            // Defaults to true (not just Java's default `false`) for saves made before this field existed, so
            // existing businesses don't silently opt out of customers the moment this update lands.
            business.npcCustomersEnabled = !bTag.contains("npcCustomersEnabled") || bTag.getBoolean("npcCustomersEnabled");
            if (bTag.contains("openHour"))
                business.openHour = bTag.getInt("openHour");
            if (bTag.contains("closeHour"))
                business.closeHour = bTag.getInt("closeHour");
            business.manuallyClosed = bTag.getBoolean("manuallyClosed");
            business.hasSignPlaced = bTag.getBoolean("hasSignPlaced");
            business.adAwarenessUntilMillis = bTag.getLong("adAwarenessUntilMillis");
            business.employeeHired = bTag.getBoolean("employeeHired");
            business.hasAwning = bTag.getBoolean("hasAwning");
            business.hasPriceBoard = bTag.getBoolean("hasPriceBoard");
            business.hasLoyaltyCard = bTag.getBoolean("hasLoyaltyCard");
            business.hasGuardDog = bTag.getBoolean("hasGuardDog");
            business.salesSinceLoyaltyBonus = bTag.getInt("salesSinceLoyaltyBonus");
            try
            {
                business.status = Business.Status.valueOf(bTag.getString("status"));
            }
            catch (IllegalArgumentException e)
            {
                business.status = Business.Status.ACTIVE;
            }

            readItemAmountMap(bTag.getCompound("inventory")).forEach(business.inventory::put);
            readItemAmountMap(bTag.getCompound("listingPricesCents")).forEach(business.listingPricesCents::put);

            ListTag barrelsTag = bTag.getList("barrels", Tag.TAG_COMPOUND);
            for (int i = 0; i < barrelsTag.size(); i++)
            {
                BarrelPos barrel = readBarrelPos(barrelsTag.getCompound(i));
                if (barrel != null)
                    business.barrels.add(barrel);
            }

            ListTag queuePointsTag = bTag.getList("queuePoints", Tag.TAG_COMPOUND);
            for (int i = 0; i < queuePointsTag.size(); i++)
            {
                QueuePos queuePos = readQueuePos(queuePointsTag.getCompound(i));
                if (queuePos != null)
                    business.queuePoints.add(queuePos);
            }

            ListTag reviewsTag = bTag.getList("reviews", Tag.TAG_COMPOUND);
            for (int i = 0; i < reviewsTag.size(); i++)
            {
                Review review = readReview(reviewsTag.getCompound(i));
                if (review != null)
                    business.reviews.add(review);
            }

            ListTag pendingReviewsTag = bTag.getList("pendingPlayerReviews", Tag.TAG_COMPOUND);
            for (int i = 0; i < pendingReviewsTag.size(); i++)
            {
                PendingOrderReview pending = readPendingOrderReview(pendingReviewsTag.getCompound(i));
                if (pending != null)
                    business.pendingPlayerReviews.add(pending);
            }

            ListTag ordersTag = bTag.getList("orders", Tag.TAG_COMPOUND);
            for (int i = 0; i < ordersTag.size(); i++)
            {
                Order order = readOrder(ordersTag.getCompound(i));
                if (order != null)
                    business.orders.add(order);
            }

            ListTag regularsTag = bTag.getList("regulars", Tag.TAG_COMPOUND);
            for (int i = 0; i < regularsTag.size(); i++)
            {
                RegularCustomer regular = readRegularCustomer(regularsTag.getCompound(i));
                if (regular != null)
                    business.regulars.add(regular);
            }

            ListTag licensedTag = bTag.getList("licensedGovernmentIds", Tag.TAG_STRING);
            for (int i = 0; i < licensedTag.size(); i++)
                business.licensedGovernmentIds.add(UUID.fromString(licensedTag.getString(i)));

            ListTag endorsementsTag = bTag.getList("endorsements", Tag.TAG_STRING);
            for (int i = 0; i < endorsementsTag.size(); i++)
                business.endorsements.add(endorsementsTag.getString(i));

            if (bTag.contains("stateAffiliationGovernmentId"))
                business.stateAffiliationGovernmentId = UUID.fromString(bTag.getString("stateAffiliationGovernmentId"));
            business.stateProfitSharePercent = bTag.getInt("stateProfitSharePercent");

            data.businesses.put(ownerId, business);
        }

        ListTag recipesTag = tag.getList("recipes", Tag.TAG_COMPOUND);
        for (int i = 0; i < recipesTag.size(); i++)
        {
            CompoundTag rTag = recipesTag.getCompound(i);
            ResourceLocation output = ResourceLocation.tryParse(rTag.getString("output"));
            if (output == null)
                continue;
            Recipe recipe = new Recipe(UUID.fromString(rTag.getString("id")), output, rTag.getInt("outputCount"));
            readItemAmountMap(rTag.getCompound("inputs")).forEach(recipe.inputs::put);
            data.recipes.add(recipe);
        }

        return data;
    }

    private static Map<ResourceLocation, Integer> readItemAmountMap(CompoundTag tag)
    {
        Map<ResourceLocation, Integer> map = new LinkedHashMap<>();
        for (String key : tag.getAllKeys())
        {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null)
                map.put(id, tag.getInt(key));
        }
        return map;
    }

    private static CompoundTag writeItemAmountMap(Map<ResourceLocation, Integer> map)
    {
        CompoundTag tag = new CompoundTag();
        map.forEach((id, amount) -> tag.putInt(id.toString(), amount));
        return tag;
    }

    private static CompoundTag writeBarrelPos(BarrelPos barrel)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", barrel.dimension().location().toString());
        tag.putInt("x", barrel.pos().getX());
        tag.putInt("y", barrel.pos().getY());
        tag.putInt("z", barrel.pos().getZ());
        return tag;
    }

    private static BarrelPos readBarrelPos(CompoundTag tag)
    {
        ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dim"));
        if (dimId == null)
            return null;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        return new BarrelPos(dimension, new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }

    private static CompoundTag writeQueuePos(QueuePos queuePos)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", queuePos.dimension().location().toString());
        tag.putInt("x", queuePos.pos().getX());
        tag.putInt("y", queuePos.pos().getY());
        tag.putInt("z", queuePos.pos().getZ());
        tag.putString("facing", queuePos.facing().getSerializedName());
        return tag;
    }

    private static QueuePos readQueuePos(CompoundTag tag)
    {
        ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dim"));
        if (dimId == null)
            return null;
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        Direction facing = Direction.byName(tag.getString("facing"));
        if (facing == null)
            facing = Direction.NORTH;
        return new QueuePos(dimension, new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), facing);
    }

    private static CompoundTag writeReview(Review review)
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", review.id);
        tag.putString("source", review.source.name());
        tag.putString("authorName", review.authorName);
        if (review.authorPlayerId != null)
            tag.putUUID("authorPlayerId", review.authorPlayerId);
        tag.putDouble("stars", review.stars);
        tag.putString("text", review.text == null ? "" : review.text);
        tag.putLong("timestamp", review.timestampMillis);
        if (review.ownerReply != null)
            tag.putString("ownerReply", review.ownerReply);
        ListTag tagsTag = new ListTag();
        for (String t : review.tags)
            tagsTag.add(net.minecraft.nbt.StringTag.valueOf(t));
        tag.put("tags", tagsTag);
        return tag;
    }

    private static Review readReview(CompoundTag tag)
    {
        try
        {
            UUID id = tag.getUUID("id");
            ReviewSource source = ReviewSource.valueOf(tag.getString("source"));
            String authorName = tag.getString("authorName");
            UUID authorPlayerId = tag.hasUUID("authorPlayerId") ? tag.getUUID("authorPlayerId") : null;
            double stars = tag.getDouble("stars");
            String text = tag.getString("text");
            long timestamp = tag.getLong("timestamp");
            Review review = new Review(id, source, authorName, authorPlayerId, stars, text, timestamp);
            if (tag.contains("ownerReply"))
                review.ownerReply = tag.getString("ownerReply");
            ListTag tagsTag = tag.getList("tags", Tag.TAG_STRING);
            for (int i = 0; i < tagsTag.size(); i++)
                review.tags.add(tagsTag.getString(i));
            return review;
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static CompoundTag writePendingOrderReview(PendingOrderReview pending)
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("orderId", pending.orderId);
        tag.putUUID("buyerId", pending.buyerId);
        tag.putString("itemSummary", pending.itemSummary);
        tag.putLong("timestamp", pending.timestampMillis);
        return tag;
    }

    private static PendingOrderReview readPendingOrderReview(CompoundTag tag)
    {
        if (!tag.hasUUID("orderId") || !tag.hasUUID("buyerId"))
            return null;
        return new PendingOrderReview(tag.getUUID("orderId"), tag.getUUID("buyerId"), tag.getString("itemSummary"), tag.getLong("timestamp"));
    }

    private static CompoundTag writeOrder(Order order)
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", order.id);
        tag.putString("source", order.source.name());
        tag.putString("buyerName", order.buyerName);
        tag.putString("itemSummary", order.itemSummary);
        tag.putInt("totalCents", order.totalCents);
        tag.putLong("timestamp", order.timestampMillis);
        return tag;
    }

    private static Order readOrder(CompoundTag tag)
    {
        try
        {
            return new Order(tag.getUUID("id"), ReviewSource.valueOf(tag.getString("source")), tag.getString("buyerName"),
                    tag.getString("itemSummary"), tag.getInt("totalCents"), tag.getLong("timestamp"));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static CompoundTag writeRegularCustomer(RegularCustomer regular)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", regular.name);
        tag.putInt("visitCount", regular.visitCount);
        tag.putLong("lastVisit", regular.lastVisitMillis);
        return tag;
    }

    private static RegularCustomer readRegularCustomer(CompoundTag tag)
    {
        if (!tag.contains("name"))
            return null;
        return new RegularCustomer(tag.getString("name"), tag.getInt("visitCount"), tag.getLong("lastVisit"));
    }

    @Override
    public CompoundTag save(CompoundTag tag)
    {
        tag.putInt("registrationFeeCents", registrationFeeCents);
        tag.putInt("weeklyFeeCents", weeklyFeeCents);
        tag.putInt("sellBarrelDailyFeeCents", sellBarrelDailyFeeCents);
        tag.putInt("graceDays", graceDays);
        tag.putBoolean("showMarketPricesToPlayers", showMarketPricesToPlayers);
        tag.putBoolean("physicalNpcEnabled", physicalNpcEnabled);
        tag.putInt("npcSpawnIntervalMinutes", npcSpawnIntervalMinutes);
        tag.putInt("npcBaseMaxSpendCents", npcBaseMaxSpendCents);
        tag.putInt("npcSpawnRadiusBlocks", npcSpawnRadiusBlocks);
        tag.putInt("signFeeCentsPerMonth", signFeeCentsPerMonth);
        tag.putInt("advertCostCents", advertCostCents);
        tag.putInt("advertDurationHours", advertDurationHours);
        tag.putBoolean("wanderersEnabled", wanderersEnabled);
        tag.putInt("wandererSpawnIntervalMinutes", wandererSpawnIntervalMinutes);
        tag.putInt("maxWanderers", maxWanderers);
        tag.putInt("employeeWageCentsPerWeek", employeeWageCentsPerWeek);
        tag.putInt("awningCostCents", awningCostCents);
        tag.putInt("priceBoardCostCents", priceBoardCostCents);
        tag.putInt("loyaltyCardCostCents", loyaltyCardCostCents);
        tag.putInt("guardDogCostCents", guardDogCostCents);
        tag.putBoolean("theftEnabled", theftEnabled);
        tag.putDouble("theftChancePercent", theftChancePercent);
        tag.putString("missedPaymentPolicy", missedPaymentPolicy.name());

        CompoundTag businessesTag = new CompoundTag();
        businesses.forEach((ownerId, business) -> {
            CompoundTag bTag = new CompoundTag();
            bTag.putString("name", business.name);
            bTag.putString("type", business.type.toString());
            bTag.put("extraData", business.extraData.copy());
            bTag.putInt("balanceCents", business.balanceCents);
            bTag.putString("status", business.status.name());
            bTag.putLong("nextBillingAtMillis", business.nextBillingAtMillis);
            bTag.putLong("graceDeadlineMillis", business.graceDeadlineMillis);
            bTag.putBoolean("listed", business.listed);
            bTag.putBoolean("npcCustomersEnabled", business.npcCustomersEnabled);
            bTag.putInt("openHour", business.openHour);
            bTag.putInt("closeHour", business.closeHour);
            bTag.putBoolean("manuallyClosed", business.manuallyClosed);
            bTag.putBoolean("hasSignPlaced", business.hasSignPlaced);
            bTag.putLong("adAwarenessUntilMillis", business.adAwarenessUntilMillis);
            bTag.putBoolean("employeeHired", business.employeeHired);
            bTag.putBoolean("hasAwning", business.hasAwning);
            bTag.putBoolean("hasPriceBoard", business.hasPriceBoard);
            bTag.putBoolean("hasLoyaltyCard", business.hasLoyaltyCard);
            bTag.putBoolean("hasGuardDog", business.hasGuardDog);
            bTag.putInt("salesSinceLoyaltyBonus", business.salesSinceLoyaltyBonus);
            bTag.put("inventory", writeItemAmountMap(business.inventory));
            bTag.put("listingPricesCents", writeItemAmountMap(business.listingPricesCents));

            ListTag barrelsTag = new ListTag();
            for (BarrelPos barrel : business.barrels)
                barrelsTag.add(writeBarrelPos(barrel));
            bTag.put("barrels", barrelsTag);

            ListTag queuePointsTag = new ListTag();
            for (QueuePos queuePos : business.queuePoints)
                queuePointsTag.add(writeQueuePos(queuePos));
            bTag.put("queuePoints", queuePointsTag);

            ListTag reviewsTag = new ListTag();
            for (Review review : business.reviews)
                reviewsTag.add(writeReview(review));
            bTag.put("reviews", reviewsTag);

            ListTag pendingReviewsTag = new ListTag();
            for (PendingOrderReview pending : business.pendingPlayerReviews)
                pendingReviewsTag.add(writePendingOrderReview(pending));
            bTag.put("pendingPlayerReviews", pendingReviewsTag);

            ListTag ordersTag = new ListTag();
            for (Order order : business.orders)
                ordersTag.add(writeOrder(order));
            bTag.put("orders", ordersTag);

            ListTag regularsTag = new ListTag();
            for (RegularCustomer regular : business.regulars)
                regularsTag.add(writeRegularCustomer(regular));
            bTag.put("regulars", regularsTag);

            ListTag licensedTag = new ListTag();
            for (UUID govId : business.licensedGovernmentIds)
                licensedTag.add(StringTag.valueOf(govId.toString()));
            bTag.put("licensedGovernmentIds", licensedTag);

            ListTag endorsementsTag = new ListTag();
            for (String name : business.endorsements)
                endorsementsTag.add(StringTag.valueOf(name));
            bTag.put("endorsements", endorsementsTag);

            if (business.stateAffiliationGovernmentId != null)
                bTag.putString("stateAffiliationGovernmentId", business.stateAffiliationGovernmentId.toString());
            bTag.putInt("stateProfitSharePercent", business.stateProfitSharePercent);

            businessesTag.put(ownerId.toString(), bTag);
        });
        tag.put("businesses", businessesTag);

        ListTag recipesTag = new ListTag();
        for (Recipe recipe : recipes)
        {
            CompoundTag rTag = new CompoundTag();
            rTag.putString("id", recipe.id.toString());
            rTag.putString("output", recipe.output.toString());
            rTag.putInt("outputCount", recipe.outputCount);
            rTag.put("inputs", writeItemAmountMap(recipe.inputs));
            recipesTag.add(rTag);
        }
        tag.put("recipes", recipesTag);

        return tag;
    }

    public static BusinessData get(MinecraftServer server)
    {
        return server.overworld().getDataStorage().computeIfAbsent(BusinessData::load, BusinessData::create, DATA_NAME);
    }

    // ---------- settings ----------

    public int registrationFeeCents() { return registrationFeeCents; }
    public int weeklyFeeCents() { return weeklyFeeCents; }
    public int sellBarrelDailyFeeCents() { return sellBarrelDailyFeeCents; }
    public MissedPaymentPolicy missedPaymentPolicy() { return missedPaymentPolicy; }
    public int graceDays() { return graceDays; }
    public boolean showMarketPricesToPlayers() { return showMarketPricesToPlayers; }
    public boolean physicalNpcEnabled() { return physicalNpcEnabled; }
    public int npcSpawnIntervalMinutes() { return npcSpawnIntervalMinutes; }
    public int npcBaseMaxSpendCents() { return npcBaseMaxSpendCents; }
    public int npcSpawnRadiusBlocks() { return npcSpawnRadiusBlocks; }
    public int signFeeCentsPerMonth() { return signFeeCentsPerMonth; }
    public int advertCostCents() { return advertCostCents; }
    public int advertDurationHours() { return advertDurationHours; }
    public boolean wanderersEnabled() { return wanderersEnabled; }
    public int wandererSpawnIntervalMinutes() { return wandererSpawnIntervalMinutes; }
    public int maxWanderers() { return maxWanderers; }
    public int employeeWageCentsPerWeek() { return employeeWageCentsPerWeek; }
    public int awningCostCents() { return awningCostCents; }
    public int priceBoardCostCents() { return priceBoardCostCents; }
    public int loyaltyCardCostCents() { return loyaltyCardCostCents; }
    public int guardDogCostCents() { return guardDogCostCents; }
    public boolean theftEnabled() { return theftEnabled; }
    public double theftChancePercent() { return theftChancePercent; }

    public void setRegistrationFeeCents(int cents) { registrationFeeCents = Math.max(0, cents); setDirty(); }
    public void setWeeklyFeeCents(int cents) { weeklyFeeCents = Math.max(0, cents); setDirty(); }
    public void setSellBarrelDailyFeeCents(int cents) { sellBarrelDailyFeeCents = Math.max(0, cents); setDirty(); }
    public void setMissedPaymentPolicy(MissedPaymentPolicy policy) { missedPaymentPolicy = policy; setDirty(); }
    public void setGraceDays(int days) { graceDays = Math.max(0, days); setDirty(); }
    public void setShowMarketPricesToPlayers(boolean show) { showMarketPricesToPlayers = show; setDirty(); }
    public void setPhysicalNpcEnabled(boolean enabled) { physicalNpcEnabled = enabled; setDirty(); }
    public void setNpcSpawnIntervalMinutes(int minutes) { npcSpawnIntervalMinutes = Math.max(1, minutes); setDirty(); }
    public void setNpcBaseMaxSpendCents(int cents) { npcBaseMaxSpendCents = Math.max(0, cents); setDirty(); }
    public void setNpcSpawnRadiusBlocks(int blocks) { npcSpawnRadiusBlocks = Math.max(1, blocks); setDirty(); }
    public void setSignFeeCentsPerMonth(int cents) { signFeeCentsPerMonth = Math.max(0, cents); setDirty(); }
    public void setAdvertCostCents(int cents) { advertCostCents = Math.max(0, cents); setDirty(); }
    public void setAdvertDurationHours(int hours) { advertDurationHours = Math.max(1, hours); setDirty(); }
    public void setWanderersEnabled(boolean enabled) { wanderersEnabled = enabled; setDirty(); }
    public void setWandererSpawnIntervalMinutes(int minutes) { wandererSpawnIntervalMinutes = Math.max(1, minutes); setDirty(); }
    public void setMaxWanderers(int max) { maxWanderers = Math.max(0, max); setDirty(); }
    public void setEmployeeWageCentsPerWeek(int cents) { employeeWageCentsPerWeek = Math.max(0, cents); setDirty(); }
    public void setAwningCostCents(int cents) { awningCostCents = Math.max(0, cents); setDirty(); }
    public void setPriceBoardCostCents(int cents) { priceBoardCostCents = Math.max(0, cents); setDirty(); }
    public void setLoyaltyCardCostCents(int cents) { loyaltyCardCostCents = Math.max(0, cents); setDirty(); }
    public void setGuardDogCostCents(int cents) { guardDogCostCents = Math.max(0, cents); setDirty(); }
    public void setTheftEnabled(boolean enabled) { theftEnabled = enabled; setDirty(); }
    public void setTheftChancePercent(double percent) { theftChancePercent = Math.max(0, percent); setDirty(); }

    // The total a business owes at its next billing: the flat weekly fee, each Sell Barrel's daily fee accrued
    // over the week, and the Business Sign's monthly fee prorated to a week. Computed fresh (not stored) so a
    // barrel placed/broken (or a fee changed) mid-week is reflected at the next bill instead of drifting out
    // of sync.
    public int amountDueCents(Business business)
    {
        int signWeeklyCents = (int) Math.round(signFeeCentsPerMonth * 7.0 / 30.0);
        int employeeWeeklyCents = business.employeeHired ? employeeWageCentsPerWeek : 0;
        int typeWeeklyCents = BusinessTypeRegistry.getOrShop(business.type).extraWeeklyFeeCents(business);
        return weeklyFeeCents + business.barrels.size() * sellBarrelDailyFeeCents * 7 + signWeeklyCents + employeeWeeklyCents + typeWeeklyCents;
    }

    // ---------- businesses ----------

    public Business getByOwner(UUID ownerId)
    {
        return businesses.get(ownerId);
    }

    public List<Business> getAll()
    {
        return new ArrayList<>(businesses.values());
    }

    public Business register(UUID ownerId, String name)
    {
        return register(ownerId, name, BusinessTypes.SHOP_ID);
    }

    // Registers a business of a specific type (see api.BusinessTypeRegistry) - used by addons that offer
    // their own registration flow instead of the default Shop one.
    public Business register(UUID ownerId, String name, ResourceLocation typeId)
    {
        Business business = new Business(ownerId, name, System.currentTimeMillis() + BILLING_INTERVAL_MILLIS);
        business.type = typeId;
        businesses.put(ownerId, business);
        setDirty();
        BusinessTypeRegistry.getOrShop(typeId).onRegistered(business);
        return business;
    }

    // Adds (or subtracts, if negative) from a business's balance. Never goes below zero.
    public void adjustBalance(Business business, int deltaCents)
    {
        business.balanceCents = Math.max(0, business.balanceCents + deltaCents);
        setDirty();
    }

    public void setListed(Business business, boolean listed)
    {
        business.listed = listed;
        setDirty();
    }

    // Per-business opt-out from physical customer NPCs (the server-wide physicalNpcEnabled() toggle is a
    // separate master switch - see customer.CustomerNpcManager.tickSpawning).
    public void setNpcCustomersEnabledForBusiness(Business business, boolean enabled)
    {
        business.npcCustomersEnabled = enabled;
        setDirty();
    }

    public void setEmployeeHired(Business business, boolean hired)
    {
        business.employeeHired = hired;
        setDirty();
    }

    public void setBusinessHours(Business business, int openHour, int closeHour)
    {
        business.openHour = Math.floorMod(openHour, 24);
        business.closeHour = Math.floorMod(closeHour, 24);
        setDirty();
    }

    public void setManuallyClosed(Business business, boolean closed)
    {
        business.manuallyClosed = closed;
        setDirty();
    }

    // Whether the business's Business Sign would currently read "OPEN" - a manual "closed for now" override
    // always wins, otherwise it's whatever the owner's configured hours say for the current real-world local
    // hour (handles hours that wrap past midnight, e.g. open 20 - close 4).
    public boolean isOpenNow(Business business)
    {
        if (business.manuallyClosed)
            return false;
        int hour = java.time.LocalTime.now().getHour();
        int open = business.openHour;
        int close = business.closeHour;
        if (open == close)
            return true; // open 24 hours
        if (open < close)
            return hour >= open && hour < close;
        return hour >= open || hour < close; // wraps past midnight
    }

    // ---------- visibility & advertising ----------

    // A business nobody's ever heard of shouldn't get customers just for existing - it needs a placed
    // Business Sign, or an active paid advert, to actually be "found" by anyone (see
    // customer.CustomerNpcManager.tickSpawning).
    public boolean isDiscoverable(MinecraftServer server, Business business)
    {
        if (business.hasSignPlaced || System.currentTimeMillis() < business.adAwarenessUntilMillis)
            return true;

        TerritoryData territory = TerritoryData.get(server);
        List<Government> governments = new ArrayList<>(territory.getAllCities());
        governments.addAll(territory.getAllCountries());
        for (QueuePos queuePos : business.queuePoints)
            for (Government government : governments)
                for (PublicSquare square : government.publicSquares)
                    if (square.contains(queuePos.dimension(), queuePos.pos()))
                        return true;
        return false;
    }

    public void markSignPlaced(UUID ownerId)
    {
        Business business = businesses.get(ownerId);
        if (business == null || business.hasSignPlaced)
            return;
        business.hasSignPlaced = true;
        setDirty();
    }

    // Buys (or extends) a period of paid-advertising visibility for a business, independent of whether it has
    // a sign placed. Caller is responsible for charging the business balance first.
    public void runAdvert(Business business, int hours)
    {
        long now = System.currentTimeMillis();
        long extendFrom = Math.max(now, business.adAwarenessUntilMillis);
        business.adAwarenessUntilMillis = extendFrom + hours * 3600_000L;
        setDirty();
    }

    // ---------- orders ----------

    public void addOrder(Business business, Order order)
    {
        business.orders.add(0, order);
        while (business.orders.size() > MAX_ORDERS_KEPT)
            business.orders.remove(business.orders.size() - 1);
        setDirty();
    }

    // A price <= 0 removes the item from the storefront listing instead of setting a price.
    public void setListingPrice(Business business, ResourceLocation item, int priceCents)
    {
        if (priceCents <= 0)
            business.listingPricesCents.remove(item);
        else
            business.listingPricesCents.put(item, priceCents);
        setDirty();
    }

    public void addStock(Business business, ResourceLocation item, int amount)
    {
        business.addStock(item, amount);
        setDirty();
    }

    public boolean removeStock(Business business, ResourceLocation item, int amount)
    {
        boolean removed = business.removeStock(item, amount);
        if (removed)
            setDirty();
        return removed;
    }

    // ---------- sell barrels ----------

    public void registerBarrel(UUID ownerId, ResourceKey<Level> dimension, BlockPos pos)
    {
        Business business = businesses.get(ownerId);
        if (business == null)
            return;
        business.barrels.add(new BarrelPos(dimension, pos));
        setDirty();
    }

    public void unregisterBarrel(UUID ownerId, ResourceKey<Level> dimension, BlockPos pos)
    {
        Business business = businesses.get(ownerId);
        if (business == null)
            return;
        if (business.barrels.remove(new BarrelPos(dimension, pos)))
            setDirty();
    }

    // ---------- queue points ----------

    public void addQueuePoint(Business business, QueuePos queuePos)
    {
        business.queuePoints.add(queuePos);
        setDirty();
    }

    // Removes whichever queue point is nearest `pos` in the same dimension, within a couple blocks (so
    // players don't need to hit the exact designated block). Returns false if none matched.
    public boolean removeQueuePointNear(Business business, ResourceKey<Level> dimension, BlockPos pos)
    {
        QueuePos closest = null;
        double closestDistSq = 9.0; // 3 blocks
        for (QueuePos queuePos : business.queuePoints)
        {
            if (!queuePos.dimension().equals(dimension))
                continue;
            double distSq = queuePos.pos().distSqr(pos);
            if (distSq <= closestDistSq)
            {
                closest = queuePos;
                closestDistSq = distSq;
            }
        }
        if (closest == null)
            return false;
        business.queuePoints.remove(closest);
        setDirty();
        return true;
    }

    // ---------- reviews ----------

    public void addReview(Business business, Review review)
    {
        business.reviews.add(0, review);
        setDirty();
    }

    // Admin-only deletion (enforced by the caller - see web.BusinessHandler.handleAdminRemoveReview).
    public boolean removeReview(Business business, UUID reviewId)
    {
        boolean removed = business.reviews.removeIf(r -> r.id.equals(reviewId));
        if (removed)
            setDirty();
        return removed;
    }

    // The owner's one public reply to a review, shown right under it - overwrites any previous reply.
    // Returns false if that review doesn't exist.
    public boolean setReviewReply(Business business, UUID reviewId, String replyText)
    {
        for (Review review : business.reviews)
        {
            if (!review.id.equals(reviewId))
                continue;
            review.ownerReply = replyText;
            setDirty();
            return true;
        }
        return false;
    }

    public void addPendingOrderReview(Business business, PendingOrderReview pending)
    {
        business.pendingPlayerReviews.add(pending);
        setDirty();
    }

    // Removes a buyer's pending review for a specific order once they've submitted it, so they can't leave
    // more than one review for the same order. Returns false if that order isn't (or no longer is) pending.
    public boolean consumePendingOrderReview(Business business, UUID buyerId, UUID orderId)
    {
        boolean removed = business.pendingPlayerReviews.removeIf(p -> p.orderId.equals(orderId) && p.buyerId.equals(buyerId));
        if (removed)
            setDirty();
        return removed;
    }

    // Average star rating over reviews left within the configurable recency window, so spawn rate/basket size
    // (see customer.CustomerNpcManager) only ever respond to recent sentiment, not a whole history. Falls back
    // to a neutral 3.0 baseline when there are no recent reviews, so a new (or currently review-less) business
    // isn't punished for having no reviews yet.
    public double recentReviewScore(Business business, long recentWindowMillis)
    {
        long cutoff = System.currentTimeMillis() - recentWindowMillis;
        double sum = 0;
        int count = 0;
        for (Review review : business.reviews)
        {
            if (review.timestampMillis < cutoff)
                continue;
            sum += review.stars;
            count++;
        }
        return count == 0 ? 3.0 : sum / count;
    }

    // Caller is responsible for validating/deducting the cost first (see web.BusinessHandler.handleBuyLicense).
    public void addLicensedGovernment(Business business, UUID governmentId)
    {
        business.licensedGovernmentIds.add(governmentId);
        setDirty();
    }

    // ---------- storefront upgrades ----------

    // Caller is responsible for validating/deducting the cost first - these just flip the flag.
    public void setHasAwning(Business business, boolean has) { business.hasAwning = has; setDirty(); }
    public void setHasPriceBoard(Business business, boolean has) { business.hasPriceBoard = has; setDirty(); }
    public void setHasLoyaltyCard(Business business, boolean has) { business.hasLoyaltyCard = has; setDirty(); }
    public void setHasGuardDog(Business business, boolean has) { business.hasGuardDog = has; setDirty(); }

    // Counts a completed sale toward the Loyalty Card's every-Nth-sale bonus. Returns true (and resets the
    // counter) exactly on the sale that hits the threshold - only meaningful if business.hasLoyaltyCard.
    public boolean tickLoyaltyCounter(Business business)
    {
        business.salesSinceLoyaltyBonus++;
        if (business.salesSinceLoyaltyBonus < LOYALTY_CARD_INTERVAL)
        {
            setDirty();
            return false;
        }
        business.salesSinceLoyaltyBonus = 0;
        setDirty();
        return true;
    }

    // ---------- regulars ----------

    // Read-only lookup - how many times this business has recorded a completed sale to this name before.
    public int regularVisitCount(Business business, String name)
    {
        for (RegularCustomer regular : business.regulars)
            if (regular.name.equals(name))
                return regular.visitCount;
        return 0;
    }

    // Called on every completed sale: creates or updates the matching regular, evicting whoever's been away
    // longest if the roster's full and this is a brand new name. Returns the visit count after this sale.
    public int recordRegularVisit(Business business, String name)
    {
        long now = System.currentTimeMillis();
        for (RegularCustomer regular : business.regulars)
        {
            if (!regular.name.equals(name))
                continue;
            regular.visitCount++;
            regular.lastVisitMillis = now;
            setDirty();
            return regular.visitCount;
        }

        if (business.regulars.size() >= MAX_REGULARS_TRACKED)
        {
            RegularCustomer oldest = business.regulars.stream().min(Comparator.comparingLong(r -> r.lastVisitMillis)).orElse(null);
            if (oldest != null)
                business.regulars.remove(oldest);
        }
        business.regulars.add(new RegularCustomer(name, 1, now));
        setDirty();
        return 1;
    }

    // Picks a name at random from this business's tracked regulars, so a freshly-spawned customer can
    // occasionally be recognized as someone who's shopped here before. Null if none are tracked yet.
    public String pickRegularName(Business business, java.util.Random random)
    {
        if (business.regulars.isEmpty())
            return null;
        return business.regulars.get(random.nextInt(business.regulars.size())).name;
    }

    // ---------- recipes ----------

    public List<Recipe> getRecipes()
    {
        return new ArrayList<>(recipes);
    }

    public Recipe getRecipe(UUID id)
    {
        return recipes.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
    }

    public void addRecipe(Recipe recipe)
    {
        recipes.add(recipe);
        setDirty();
    }

    public boolean removeRecipe(UUID id)
    {
        boolean removed = recipes.removeIf(r -> r.id.equals(id));
        if (removed)
            setDirty();
        return removed;
    }

    // ---------- billing ----------

    // Charges every active/grace business its weekly fee (plus its Sell Barrels' accrued daily fees) if due,
    // applying the configured missed-payment policy when a business can't afford it. Called periodically
    // from the server tick.
    public void tickBilling(MinecraftServer server)
    {
        long now = System.currentTimeMillis();
        List<UUID> toDissolve = new ArrayList<>();

        for (Business business : businesses.values())
        {
            if (business.status == Business.Status.SUSPENDED)
                continue;
            if (business.nextBillingAtMillis > now)
                continue;

            int due = amountDueCents(business);
            if (business.balanceCents >= due)
            {
                business.balanceCents -= due;
                business.nextBillingAtMillis = now + BILLING_INTERVAL_MILLIS;
                business.status = Business.Status.ACTIVE;
                business.graceDeadlineMillis = 0;
                setDirty();
                continue;
            }

            switch (missedPaymentPolicy)
            {
                case DISSOLVE -> toDissolve.add(business.ownerId);
                case SUSPEND -> { business.status = Business.Status.SUSPENDED; setDirty(); }
                case GRACE_THEN_SUSPEND -> {
                    if (business.status != Business.Status.GRACE)
                    {
                        business.status = Business.Status.GRACE;
                        business.graceDeadlineMillis = now + graceDays * 24L * 60 * 60 * 1000;
                        setDirty();
                    }
                    else if (now >= business.graceDeadlineMillis)
                    {
                        business.status = Business.Status.SUSPENDED;
                        setDirty();
                    }
                }
            }
        }

        for (UUID ownerId : toDissolve)
        {
            Business business = businesses.get(ownerId);
            if (business == null)
                continue;
            BusinessTypeRegistry.getOrShop(business.type).onDissolved(business);
            if (business.balanceCents > 0)
                MoneyData.get(server).addMoney(server, ownerId, business.balanceCents);
            businesses.remove(ownerId);
        }
        if (!toDissolve.isEmpty())
            setDirty();
    }

    // Pays off the current amount due immediately from the business's own balance and reactivates it.
    public boolean reactivate(Business business)
    {
        int due = amountDueCents(business);
        if (business.balanceCents < due)
            return false;
        business.balanceCents -= due;
        business.status = Business.Status.ACTIVE;
        business.graceDeadlineMillis = 0;
        business.nextBillingAtMillis = System.currentTimeMillis() + BILLING_INTERVAL_MILLIS;
        setDirty();
        return true;
    }
}
