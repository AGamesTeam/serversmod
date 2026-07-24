package com.andruspro6446.servermod.business;

import com.andruspro6446.servermod.review.Review;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// A player-owned business: its own money balance and virtual item stock, kept separate from the owner's
// personal account. Businesses buy from the shared market, optionally manufacture bought items into pricier
// products, and can resell them - either back to the market, or, if listed, directly to other players at
// whatever price the owner sets.
public class Business
{
    public enum Status { ACTIVE, GRACE, SUSPENDED }

    public final UUID ownerId;
    public String name;
    public int balanceCents;
    public Status status = Status.ACTIVE;
    public long nextBillingAtMillis;
    public long graceDeadlineMillis;
    public boolean listed;
    public final Map<ResourceLocation, Integer> inventory = new LinkedHashMap<>();
    public final Map<ResourceLocation, Integer> listingPricesCents = new LinkedHashMap<>();
    // Every Sell Barrel this business owns - the real, physical containers its storefront actually sells
    // from. Kept here (not just discoverable by scanning the world) so billing and stock lookups are cheap.
    public final Set<BarrelPos> barrels = new LinkedHashSet<>();
    // Where customer NPCs walk to and line up - see customer.CustomerNpcManager. A business can have several,
    // acting as independent queue lines.
    public final List<QueuePos> queuePoints = new ArrayList<>();
    // Every review ever left on this storefront (NPC visits and player-submitted), newest first. Only an
    // admin can delete one - see BusinessData.removeReview.
    public final List<Review> reviews = new ArrayList<>();
    // Completed website purchases a buyer hasn't yet reviewed - one entry per unreviewed order, consumed the
    // moment that buyer submits their one review for it.
    public final List<PendingOrderReview> pendingPlayerReviews = new ArrayList<>();
    // Per-business opt-out from physical customer NPCs, independent of the server-wide admin toggle (both
    // must be true for this business to actually get customers - see BusinessData.physicalNpcEnabled()).
    // Defaults to true so an owner doesn't lose customers silently just because the admin flipped the
    // server-wide setting on.
    public boolean npcCustomersEnabled = true;
    // Displayed on this business's Business Sign (see block.BusinessSignBlock) and checked by customer NPCs
    // when they arrive - hours are the real-world local hour of day (0-23); open == close means open 24/7.
    // manuallyClosed always overrides the schedule (an owner closing up for the day/a holiday).
    public int openHour = 9;
    public int closeHour = 21;
    public boolean manuallyClosed = false;
    // A rolling history of completed sales (NPC visits and web purchases alike) - see BusinessData.addOrder,
    // capped so it can't grow unbounded.
    public final List<Order> orders = new ArrayList<>();
    // Whether a Business Sign has ever been placed for this business (see block.BusinessSignBlock) - once
    // true it stays true, since tracking every sign's position just to detect "none left" isn't worth the
    // bookkeeping. Along with adAwarenessUntilMillis, this is what makes a business "discoverable" at all -
    // see BusinessData.isDiscoverable - a store nobody's ever heard of doesn't get customers.
    public boolean hasSignPlaced = false;
    // Timestamp until which paid advertising keeps this business discoverable even without a placed sign -
    // see BusinessData.runAdvert.
    public long adAwarenessUntilMillis = 0;
    // An employee auto-serves the front-of-line customer whenever the owner isn't online - see
    // customer.CustomerNpcManager.tickEmployeeService. Costs a recurring wage folded into the weekly bill
    // (see BusinessData.employeeWageCentsPerWeek/amountDueCents), only while actually hired.
    public boolean employeeHired = false;

    // One-time storefront upgrades (see BusinessData's cost settings) - each nudges customer NPC reviews in
    // some way, see review.ReviewGenerator:
    //   Awning        - flat leniency bonus on every completed visit's review.
    //   Price Board   - prices are visible up front, so customers notice overpricing less.
    //   Loyalty Card  - every LOYALTY_CARD_INTERVALth completed sale gets an extra-generous review.
    //   Guard Dog     - cuts theft risk for storefronts not protected by the owner's own claimed land.
    public boolean hasAwning = false;
    public boolean hasPriceBoard = false;
    public boolean hasLoyaltyCard = false;
    public boolean hasGuardDog = false;
    // Counts up toward the Loyalty Card's every-Nth-sale bonus; reset once it triggers.
    public int salesSinceLoyaltyBonus = 0;
    // Customers this business has served before, recognized by name on return visits - see
    // BusinessData.recordRegularVisit/pickRegularName.
    public final List<RegularCustomer> regulars = new ArrayList<>();

    // Governments this business has paid a license fee to operate in - see Government.licenseFeeCents and
    // CustomerNpcManager.isEligibleForCustomers (a business with an unpaid required license anywhere it
    // operates gets no customer NPCs until it pays up).
    public final Set<UUID> licensedGovernmentIds = new LinkedHashSet<>();
    // Names of governments that have publicly endorsed this business - see BusinessData/TerritoryData's
    // endorsement action. Purely a permanent reputation badge; doesn't affect tax.
    public final Set<String> endorsements = new LinkedHashSet<>();
    // If set, an official has designated this (still player-owned) business as affiliated with a government -
    // see business.SalesTax, which takes an extra stateProfitSharePercent cut on every sale straight to that
    // government's treasury, regardless of whether it would otherwise tax this business by barrel location.
    public UUID stateAffiliationGovernmentId = null;
    public int stateProfitSharePercent = 0;

    public Business(UUID ownerId, String name, long nextBillingAtMillis)
    {
        this.ownerId = ownerId;
        this.name = name;
        this.nextBillingAtMillis = nextBillingAtMillis;
    }

    public int stockOf(ResourceLocation item)
    {
        return inventory.getOrDefault(item, 0);
    }

    public void addStock(ResourceLocation item, int amount)
    {
        inventory.merge(item, amount, Integer::sum);
    }

    // Removes up to `amount` of an item from stock. Returns false (removing nothing) if stock is short.
    public boolean removeStock(ResourceLocation item, int amount)
    {
        int have = stockOf(item);
        if (have < amount)
            return false;
        if (have == amount)
            inventory.remove(item);
        else
            inventory.put(item, have - amount);
        return true;
    }
}
