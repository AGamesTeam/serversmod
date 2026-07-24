package com.andruspro6446.servermod.web;

import com.andruspro6446.servermod.ServerMod;
import com.andruspro6446.servermod.api.BusinessTypeRegistry;
import com.andruspro6446.servermod.business.BarrelStock;
import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.business.BusinessType;
import com.andruspro6446.servermod.business.BusinessTypes;
import com.andruspro6446.servermod.business.MissedPaymentPolicy;
import com.andruspro6446.servermod.business.Order;
import com.andruspro6446.servermod.business.PendingOrderReview;
import com.andruspro6446.servermod.business.SalesTax;
import com.andruspro6446.servermod.business.Recipe;
import com.andruspro6446.servermod.market.MailboxData;
import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.market.MarketEntry;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.review.Review;
import com.andruspro6446.servermod.review.ReviewSource;
import com.andruspro6446.servermod.review.ReviewTags;
import com.andruspro6446.servermod.territory.Government;
import com.andruspro6446.servermod.territory.TerritoryData;
import com.andruspro6446.servermod.util.InventoryUtil;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// Routes and renders everything under /user/business (a player's own business dashboard), /businesses and
// /business/view (the public storefront directory), and the business-related additions to /admin. Split out
// of WebHandler since the business feature is sizable in its own right; shares the same HTTP plumbing via
// WebSupport and runs on the same MinecraftServer instance.
public class BusinessHandler
{
    private static final int DIRECTORY_PAGE_SIZE = 20;

    private final MinecraftServer server;

    public BusinessHandler(MinecraftServer server)
    {
        this.server = server;
    }

    // ---------- my business dashboard ----------

    public void renderDashboard(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String msgHtml = WebSupport.messageBanner(query);

        // Built entirely on the main thread: Business/BusinessData hold live, mutable state (maps included)
        // that could otherwise be concurrently mutated by another request or the billing tick while this
        // renders, risking anything from a stale read to a ConcurrentModificationException mid-iteration.
        String body = MainThreadExecutor.run(server, () -> {
            BusinessData data = BusinessData.get(server);
            Business business = data.getByOwner(session.playerId);
            return business == null ? registerFormHtml(data, msgHtml) : dashboardHtml(data, business, msgHtml);
        });
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - My Business", body));
    }

    private String registerFormHtml(BusinessData data, String msgHtml)
    {
        return """
                <div class="topbar"><h1>My Business</h1><div class="topbar-links"><a href="/businesses">Businesses</a><a href="/user">Back to panel</a></div></div>
                %s
                <div class="card">
                    <h2>&#127970; Register a business</h2>
                    <p class="sub">Registration costs %s once, plus %s every week after that (more if you run Sell Barrels -
                    see below). As a business you can buy items from the market with your own funds, manufacture them
                    into pricier products, and resell either back to the market or - if you list it - directly to
                    other players at your own price.</p>
                    <form method="post" action="/user/business/register">
                        <label>Business name</label>
                        <input type="text" name="name" maxlength="32" required>
                        %s
                        <input type="submit" value="Register (%s)">
                    </form>
                </div>
                """.formatted(msgHtml, Money.format(data.registrationFeeCents()), Money.format(data.weeklyFeeCents()),
                businessTypeSelectHtml(), Money.format(data.registrationFeeCents()));
    }

    // Only rendered as a dropdown when an addon has actually registered a second type - a vanilla install (no
    // addons) sees the exact same one-field form it always has, nothing to choose. See
    // com.andruspro6446.servermod.api.BusinessTypeRegistry.
    private String businessTypeSelectHtml()
    {
        Collection<BusinessType> types = BusinessTypeRegistry.all();
        if (types.size() <= 1)
            return "";

        StringBuilder options = new StringBuilder();
        for (BusinessType type : types)
            options.append("<option value=\"%s\">%s</option>".formatted(Html.escape(type.id().toString()), Html.escape(type.displayName())));
        return """
                <label>Business type</label>
                <select name="type">%s</select>
                """.formatted(options);
    }

    // Blank for a plain Shop (the common case, and the only case on a server with no addons installed) -
    // only businesses of an addon-registered type get a visible tag, so a vanilla dashboard looks exactly as
    // it always has.
    private String businessTypeBadgeHtml(Business business)
    {
        if (business.type.equals(BusinessTypes.SHOP_ID))
            return "";
        BusinessType type = BusinessTypeRegistry.getOrShop(business.type);
        return " <span class=\"badge flat\">%s</span>".formatted(Html.escape(type.displayName()));
    }

    private String dashboardHtml(BusinessData data, Business business, String msgHtml)
    {
        String statusHtml = statusBadgeHtml(business);
        int dueCents = data.amountDueCents(business);
        String reactivateHtml = business.status == Business.Status.SUSPENDED || business.status == Business.Status.GRACE
                ? """
                        <form method="post" action="/user/business/reactivate" style="margin-top:10px">
                            <input type="submit" value="Pay %s and reactivate">
                        </form>
                        """.formatted(Money.format(dueCents))
                : "";

        long daysLeft = Math.max(0, (business.nextBillingAtMillis - System.currentTimeMillis()) / (24L * 60 * 60 * 1000));
        String billingNote = business.status == Business.Status.SUSPENDED
                ? "Suspended - fund the business and reactivate to resume."
                : "Next bill: " + Money.format(dueCents) + " in " + daysLeft + " day" + (daysLeft == 1 ? "" : "s")
                        + (business.barrels.isEmpty() ? "." : " (includes " + business.barrels.size() + " Sell Barrel"
                        + (business.barrels.size() == 1 ? "" : "s") + " at " + Money.format(data.sellBarrelDailyFeeCents()) + "/day each).");

        boolean suspended = business.status == Business.Status.SUSPENDED;
        String disabledAttr = suspended ? " disabled" : "";

        StringBuilder inventoryRows = new StringBuilder();
        business.inventory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> inventoryRows.append(inventoryRowHtml(business, entry.getKey(), entry.getValue())));
        String inventoryTable = business.inventory.isEmpty()
                ? "<p class=\"sub\">Your business doesn't own any stock yet - buy something below.</p>"
                : "<table><thead><tr><th>Item</th><th>Stock</th><th>Sell to market</th><th>Withdraw to me</th></tr></thead><tbody>%s</tbody></table>".formatted(inventoryRows);

        List<Recipe> recipes = data.getRecipes();
        StringBuilder recipeOptions = new StringBuilder();
        for (Recipe recipe : recipes)
            recipeOptions.append("<option value=\"%s\">%s</option>".formatted(recipe.id, Html.escape(recipeSummary(recipe))));
        String manufactureCard = recipes.isEmpty()
                ? ""
                : """
                        <div class="card">
                            <h2>&#9881;&#65039; Manufacture</h2>
                            <p class="sub">Consumes inputs from your stock and produces the output, admin-defined recipes.</p>
                            <form method="post" action="/user/business/manufacture">
                                <label>Recipe</label>
                                <select name="recipe">%s</select>
                                <label>How many times</label>
                                <input type="number" name="multiplier" min="1" value="1" required>
                                <input type="submit" value="Manufacture"%s>
                            </form>
                        </div>
                        """.formatted(recipeOptions, disabledAttr);

        String storefrontCard = storefrontCardHtml(data, business);
        String hoursCard = businessHoursCardHtml(data, business);
        String ordersCard = ordersAndAdvertCardHtml(data, business);
        String upgradesCard = upgradesCardHtml(data, business);

        return """
                <div class="topbar"><h1>%s%s</h1><div class="topbar-links"><a href="/businesses">Businesses</a><a href="/business/view?owner=%s">Preview storefront</a><a href="/user">Back to panel</a></div></div>
                %s
                <div class="card">
                    <p class="sub">Status</p>
                    <div style="margin:4px 0 10px">%s</div>
                    <p class="sub">Business balance</p>
                    <div class="balance">%s</div>
                    <p class="hint">%s</p>
                    %s
                </div>
                <div class="card">
                    <h2>&#128176; Fund / withdraw money</h2>
                    <p class="sub">Move money between your personal balance and the business.</p>
                    <form method="post" action="/user/business/fund">
                        <label>Fund business ($)</label>
                        <input type="number" step="0.01" min="0.01" name="amount" required>
                        <input type="submit" value="Fund">
                    </form>
                    <form method="post" action="/user/business/withdraw" style="margin-top:10px">
                        <label>Withdraw to personal balance ($)</label>
                        <input type="number" step="0.01" min="0.01" name="amount" required>
                        <input type="submit" value="Withdraw">
                    </form>
                </div>
                <div class="card">
                    <h2>&#128722; Buy for business</h2>
                    <p class="sub">Buys from the shared market using the business balance, into your virtual stock below.</p>
                    <form method="post" action="/user/business/buy">
                        <label>Item</label>
                        <div class="combo"><input type="text" name="item" id="biz-buy-item" placeholder="Search items..." required%s></div>
                        <label>Amount</label>
                        <input type="number" name="amount" min="1" value="1" required>
                        <input type="submit" value="Buy"%s>
                    </form>
                </div>
                %s
                <div class="card">
                    <h2>&#128230; Stock (virtual)</h2>
                    <p class="sub">What you've bought or manufactured but not yet carried to a Sell Barrel. Withdraw it to your
                    personal inventory to physically stock a barrel.</p>
                    %s
                </div>
                %s
                %s
                %s
                %s
                <script>ServerModUI.initItemPicker(document.getElementById('biz-buy-item'), '/user/market.json');</script>
                """.formatted(Html.escape(business.name), businessTypeBadgeHtml(business), business.ownerId, msgHtml, statusHtml, Money.format(business.balanceCents),
                billingNote, reactivateHtml, disabledAttr, disabledAttr, manufactureCard, inventoryTable, storefrontCard, hoursCard,
                ordersCard, upgradesCard);
    }

    // Business Sign hours + the per-business physical-NPC-customers opt-out (the admin's server-wide toggle
    // in the admin panel is a separate master switch - both need to be on for customers to actually spawn).
    private String businessHoursCardHtml(BusinessData data, Business business)
    {
        boolean open = data.isOpenNow(business);
        String statusBadge = open ? "<span class=\"badge up\">OPEN now</span>" : "<span class=\"badge down\">CLOSED now</span>";

        return """
                <div class="card">
                    <h2>&#128220; Business Sign &amp; hours</h2>
                    <p class="sub">Shown on your Business Sign (given to you on registration) and checked by customer NPCs -
                    one shows up while you're closed and they'll leave a review about it. Costs %s/month, folded into your weekly bill.</p>
                    <div style="margin:4px 0 10px">%s</div>
                    <form method="post" action="/user/business/hours">
                        <label>Open hour (0-23)</label>
                        <input type="number" name="openHour" min="0" max="23" value="%d" required>
                        <label>Close hour (0-23)</label>
                        <input type="number" name="closeHour" min="0" max="23" value="%d" required>
                        <input type="submit" value="Save hours">
                    </form>
                    <form method="post" action="/user/business/toggle-closed" style="margin-top:10px">
                        <input type="hidden" name="closed" value="%s">
                        <input type="submit" value="%s">
                    </form>
                    <p class="sub" style="margin-top:16px">Customer NPCs at your storefront are currently <b>%s</b>
                    (the admin can also disable them server-wide).</p>
                    <form method="post" action="/user/business/npc-customers">
                        <input type="hidden" name="enabled" value="%s">
                        <input type="submit" value="%s">
                    </form>
                    <p class="sub" style="margin-top:16px">%s. An employee auto-serves whoever's at the front of the
                    line whenever you're not online yourself, so customers never just wait for nobody. Costs %s/week, folded into your weekly bill.</p>
                    <form method="post" action="/user/business/employee">
                        <input type="hidden" name="hired" value="%s">
                        <input type="submit" value="%s">
                    </form>
                </div>
                """.formatted(Money.format(data.signFeeCentsPerMonth()), statusBadge, business.openHour, business.closeHour,
                business.manuallyClosed ? "false" : "true", business.manuallyClosed ? "Reopen now" : "Close for now",
                business.npcCustomersEnabled ? "enabled" : "disabled",
                business.npcCustomersEnabled ? "false" : "true", business.npcCustomersEnabled ? "Disable customer NPCs" : "Enable customer NPCs",
                business.employeeHired ? "You currently <b>have an employee hired</b>" : "You don't currently have an employee",
                Money.format(data.employeeWageCentsPerWeek()),
                business.employeeHired ? "false" : "true", business.employeeHired ? "Let employee go" : "Hire an employee");
    }

    private static final int ORDERS_SHOWN = 20;

    // A brand-new business is invisible to customer NPCs until it's "discoverable" - either it's placed a
    // Business Sign, or the owner has paid for an advert (see BusinessData.isDiscoverable/runAdvert). Also
    // shows the recent order history (both NPC visits and web purchases) so an owner can see what's selling.
    private String ordersAndAdvertCardHtml(BusinessData data, Business business)
    {
        long adRemainingMs = business.adAwarenessUntilMillis - System.currentTimeMillis();
        String discoveryNote = business.hasSignPlaced
                ? "<p class=\"sub\">Your Business Sign makes you permanently discoverable to customer NPCs.</p>"
                : adRemainingMs > 0
                        ? "<p class=\"sub\">Advertising active - discoverable for %d more hour%s.</p>".formatted(
                                Math.max(1, adRemainingMs / 3600_000L), (adRemainingMs / 3600_000L) == 1 ? "" : "s")
                        : "<p class=\"sub\"><b>Nobody knows about your store yet.</b> Place your Business Sign, or run an advert below, "
                                + "before customer NPCs can find you.</p>";

        StringBuilder rows = new StringBuilder();
        business.orders.stream().limit(ORDERS_SHOWN).forEach(order -> rows.append("""
                <tr>
                    <td>%s</td>
                    <td>%s %s</td>
                    <td class="item-id">%s</td>
                    <td>%s</td>
                </tr>
                """.formatted(java.time.Instant.ofEpochMilli(order.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                        .withNano(0), Html.escape(order.buyerName), order.source == ReviewSource.NPC ? "<span class=\"badge flat\">NPC</span>" : "<span class=\"badge up\">Player</span>",
                Html.escape(order.itemSummary), Money.format(order.totalCents))));
        String ordersTable = business.orders.isEmpty()
                ? "<p class=\"sub\">No orders yet.</p>"
                : "<table><thead><tr><th>Time</th><th>Buyer</th><th>Item</th><th>Total</th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        // Always offered, regardless of hasSignPlaced - that flag only tracks "has this business ever placed
        // a sign" for permanent-discoverability purposes (see BusinessData.isDiscoverable) and deliberately
        // never resets, so it can't be used to gate this: an owner whose sign was later destroyed still needs
        // a way to get a replacement, and one who's never had one at all (pre-dates the feature) needs a first
        // one - since they're already paying the same sign maintenance fee either way, it's free either time.
        String claimSignForm = """
                <form method="post" action="/user/business/claim-sign" style="margin-top:10px">
                    <input type="submit" value="Get a Business Sign (free)">
                </form>
                """;

        // Transparency note: money leaving on every sale should never feel like an unexplained bug - see
        // business.SalesTax.describeApplicableTaxes.
        List<String> taxLines = SalesTax.describeApplicableTaxes(server, business);
        String taxNote = taxLines.isEmpty() ? "" : "<p class=\"sub\">Sales tax on every transaction here: "
                + Html.escape(String.join(", ", taxLines)) + "</p>";

        // Unlicensed-but-required governments block customer NPCs entirely (see
        // CustomerNpcManager.isEligibleForCustomers) - shown right next to the tax note so it doesn't read as
        // "customers just stopped coming" for no visible reason.
        StringBuilder licenseSb = new StringBuilder();
        for (Government government : SalesTax.applicableGovernments(server, business))
        {
            if (government.licenseFeeCents <= 0 || business.licensedGovernmentIds.contains(government.id))
                continue;
            licenseSb.append("""
                    <p class="sub"><b>License required from %s (%s)</b> or customer NPCs won't spawn here.
                    <form method="post" action="/user/business/license" style="display:inline">
                        <input type="hidden" name="govId" value="%s">
                        <button type="submit" class="btn"%s>Buy license</button>
                    </form></p>
                    """.formatted(Html.escape(government.name), Money.format(government.licenseFeeCents), government.id,
                    business.balanceCents < government.licenseFeeCents ? " disabled" : ""));
        }
        String licenseNote = licenseSb.toString();

        return """
                <div class="card">
                    <h2>&#128226; Discovery &amp; orders</h2>
                    %s
                    %s
                    %s
                    %s
                    <form method="post" action="/user/business/advertise">
                        <input type="submit" value="Run an advert (%s for %d hours)"%s>
                    </form>
                    <h2 style="margin-top:20px;font-size:1em">Recent orders <span class="count-pill">%d</span></h2>
                    %s
                </div>
                """.formatted(discoveryNote, taxNote, licenseNote, claimSignForm, Money.format(data.advertCostCents()), data.advertDurationHours(),
                business.balanceCents < data.advertCostCents() ? " disabled" : "", business.orders.size(), ordersTable);
    }

    // One-time storefront upgrades (see Business.hasAwning etc. / review.ReviewGenerator for their effects).
    private String upgradesCardHtml(BusinessData data, Business business)
    {
        StringBuilder rows = new StringBuilder();
        rows.append(upgradeRowHtml(business, "Awning", "Slightly more forgiving reviews overall.",
                business.hasAwning, data.awningCostCents(), "awning"));
        rows.append(upgradeRowHtml(business, "Price Board", "Prices shown outside - customers notice overpricing less.",
                business.hasPriceBoard, data.priceBoardCostCents(), "priceboard"));
        rows.append(upgradeRowHtml(business, "Loyalty Card", "Every " + BusinessData.LOYALTY_CARD_INTERVAL + "th sale gets an extra-generous review.",
                business.hasLoyaltyCard, data.loyaltyCardCostCents(), "loyaltycard"));
        rows.append(upgradeRowHtml(business, "Guard Dog", "Cuts theft risk sharply for storefronts not on your own claimed land.",
                business.hasGuardDog, data.guardDogCostCents(), "guarddog"));

        return """
                <div class="card">
                    <h2>&#11088; Storefront upgrades</h2>
                    <p class="sub">One-time purchases, paid from your business balance.</p>
                    <table><thead><tr><th>Upgrade</th><th>Effect</th><th></th></tr></thead><tbody>%s</tbody></table>
                </div>
                """.formatted(rows);
    }

    private String upgradeRowHtml(Business business, String label, String effect, boolean owned, int costCents, String type)
    {
        String action = owned
                ? "<span class=\"badge up\">Owned</span>"
                : """
                        <form method="post" action="/user/business/upgrade" class="row-form">
                            <input type="hidden" name="type" value="%s">
                            <button type="submit" class="btn"%s>Buy (%s)</button>
                        </form>
                        """.formatted(type, business.balanceCents < costCents ? " disabled" : "", Money.format(costCents));
        return """
                <tr>
                    <td>%s</td>
                    <td class="sub">%s</td>
                    <td>%s</td>
                </tr>
                """.formatted(Html.escape(label), Html.escape(effect), action);
    }

    private String inventoryRowHtml(Business business, ResourceLocation item, int qty)
    {
        boolean suspended = business.status == Business.Status.SUSPENDED;
        return """
                <tr>
                    <td class="item-id">%s</td>
                    <td>%d</td>
                    <td>
                        <form method="post" action="/user/business/sell" class="row-form">
                            <input type="hidden" name="item" value="%s">
                            <input type="number" name="amount" min="1" max="%d" value="%d">
                            <button type="submit" class="btn"%s>Sell</button>
                        </form>
                    </td>
                    <td>
                        <form method="post" action="/user/business/withdraw-item" class="row-form">
                            <input type="hidden" name="item" value="%s">
                            <input type="number" name="amount" min="1" max="%d" value="%d">
                            <button type="submit" class="btn">Withdraw</button>
                        </form>
                    </td>
                </tr>
                """.formatted(Html.escape(item.toString()), qty, Html.escape(item.toString()), qty, qty,
                suspended ? " disabled" : "", Html.escape(item.toString()), qty, qty);
    }

    // Sell Barrels are the physical containers a business's public storefront actually sells from - their
    // real, live contents (not the virtual stock above) are what's for sale. Requires at least one barrel.
    private String storefrontCardHtml(BusinessData data, Business business)
    {
        Map<ResourceLocation, Integer> stock = barrelStock(business);
        int dailyTotal = business.barrels.size() * data.sellBarrelDailyFeeCents();

        // Shows every item that's either physically in a barrel or already listed (even at 0 stock), so an
        // owner can set/see a price ahead of actually stocking it - listed-but-out-of-stock items still show
        // up (as "0 in stock") on the public storefront view.
        Set<ResourceLocation> allItems = new TreeSet<>(stock.keySet());
        allItems.addAll(business.listingPricesCents.keySet());

        StringBuilder rows = new StringBuilder();
        for (ResourceLocation item : allItems)
            rows.append(barrelStockRowHtml(business, item, stock.getOrDefault(item, 0)));
        String table = allItems.isEmpty()
                ? "<p class=\"sub\">Nothing in your Sell Barrels or listed yet.</p>"
                : "<table><thead><tr><th>Item</th><th>In barrels</th><th>Storefront price</th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        String barrelNote = business.barrels.isEmpty()
                ? "<p class=\"sub\"><b>You need at least one Sell Barrel</b> placed and owned by you before you can enable your storefront. "
                        + "Place a Sell Barrel block - it registers to you automatically.</p>"
                : "<p class=\"sub\">You own %d Sell Barrel%s, costing %s/day each (%s/day total, billed weekly with your fee).</p>"
                        .formatted(business.barrels.size(), business.barrels.size() == 1 ? "" : "s",
                                Money.format(data.sellBarrelDailyFeeCents()), Money.format(dailyTotal));

        boolean canEnable = !business.barrels.isEmpty();
        return """
                <div class="card">
                    <h2>&#127970; Storefront</h2>
                    %s
                    %s
                    <p class="sub">%s</p>
                    <form method="post" action="/user/business/listed">
                        <input type="hidden" name="listed" value="%s">
                        <input type="submit" value="%s"%s>
                    </form>
                    <label>Add or update a listing (works even before you've stocked the item)</label>
                    <form method="post" action="/user/business/listing" class="row-form">
                        <div class="combo" style="flex:1"><input type="text" name="item" id="listing-item" placeholder="Search items..." required></div>
                        <input type="number" step="0.01" min="0" name="price" placeholder="price" required>
                        <button type="submit" class="btn">Set</button>
                    </form>
                    <script>ServerModUI.initItemPicker(document.getElementById('listing-item'), '/user/market.json');</script>
                </div>
                """.formatted(barrelNote, table,
                business.listed ? "Your storefront is <b>enabled</b> - listed items are visible to any player on the Businesses page."
                        : "Your storefront is <b>disabled</b> - nobody can buy from you on the website.",
                business.listed ? "false" : "true", business.listed ? "Disable storefront" : "Enable storefront",
                (!business.listed && !canEnable) ? " disabled" : "");
    }

    private String barrelStockRowHtml(Business business, ResourceLocation item, int qty)
    {
        Integer priceCents = business.listingPricesCents.get(item);
        String priceValue = priceCents != null ? "%.2f".formatted(priceCents / 100.0) : "";
        return """
                <tr>
                    <td class="item-id">%s</td>
                    <td>%d</td>
                    <td>
                        <form method="post" action="/user/business/listing" class="row-form">
                            <input type="hidden" name="item" value="%s">
                            <input type="number" step="0.01" min="0" name="price" value="%s" placeholder="not listed">
                            <button type="submit" class="btn">Set</button>
                        </form>
                    </td>
                </tr>
                """.formatted(Html.escape(item.toString()), qty, Html.escape(item.toString()), priceValue);
    }

    // ---------- sell barrels ----------

    // Sums a business's Sell Barrels' live, real contents. Must only be called from the main thread.
    private Map<ResourceLocation, Integer> barrelStock(Business business)
    {
        return BarrelStock.barrelStock(server, business);
    }

    // Physically removes up to `amount` of an item across a business's barrels. Returns false (removing
    // nothing) if the combined real stock is short. Must only be called from the main thread.
    private boolean removeFromBarrels(Business business, ResourceLocation item, int amount)
    {
        return BarrelStock.removeFromBarrels(server, business, item, amount);
    }

    private String recipeSummary(Recipe recipe)
    {
        StringBuilder sb = new StringBuilder();
        recipe.inputs.forEach((id, count) -> {
            if (sb.length() > 0)
                sb.append(" + ");
            sb.append(count).append("x ").append(id);
        });
        return sb + " -> " + recipe.outputCount + "x " + recipe.output;
    }

    private String statusBadgeHtml(Business business)
    {
        return switch (business.status)
        {
            case ACTIVE -> "<span class=\"badge up\">ACTIVE</span>";
            case GRACE -> "<span class=\"badge flat\">GRACE PERIOD</span>";
            case SUSPENDED -> "<span class=\"badge down\">SUSPENDED</span>";
        };
    }

    // ---------- my business actions ----------

    public void handleRegister(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String name = form.getOrDefault("name", "").trim();
            if (name.isEmpty())
                throw new WebActionException("Enter a business name.");

            // Falls back to Shop for a vanilla form post (no "type" field at all) or an unrecognized id -
            // never lets a malformed/stale request through as some half-registered type.
            ResourceLocation requestedType = ResourceLocation.tryParse(form.getOrDefault("type", ""));
            ResourceLocation typeId = requestedType != null && BusinessTypeRegistry.get(requestedType) != null
                    ? requestedType : BusinessTypes.SHOP_ID;

            String message = MainThreadExecutor.run(server, () -> doRegister(session.playerId, name, typeId));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    private String doRegister(UUID playerId, String name, ResourceLocation typeId)
    {
        BusinessData data = BusinessData.get(server);
        if (data.getByOwner(playerId) != null)
            throw new WebActionException("You already have a business.");

        int fee = data.registrationFeeCents();
        int balance = MoneyData.get(server).getMoney(playerId);
        if (balance < fee)
            throw new WebActionException("You need " + Money.format(fee) + " but only have " + Money.format(balance) + ".");

        MoneyData.get(server).addMoney(server, playerId, -fee);
        data.register(playerId, name, typeId);

        ResourceLocation signId = ForgeRegistries.ITEMS.getKey(ServerMod.BUSINESS_SIGN_BLOCK_ITEM.get());
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null)
            InventoryUtil.giveOrDrop(online, new ItemStack(ServerMod.BUSINESS_SIGN_BLOCK_ITEM.get()));
        else
            MailboxData.get(server).addPending(playerId, signId, 1);

        return "Registered \"" + name + "\" for " + Money.format(fee) + ". Check your inventory for a Business Sign to place at your storefront.";
    }

    public void handleFund(HttpExchange exchange) throws IOException
    {
        handleTransfer(exchange, true);
    }

    public void handleWithdraw(HttpExchange exchange) throws IOException
    {
        handleTransfer(exchange, false);
    }

    private void handleTransfer(HttpExchange exchange, boolean fund) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            int amountCents = Money.toCents(WebSupport.parsePositiveDouble(form.get("amount")));
            String message = MainThreadExecutor.run(server, () -> fund ? doFund(session.playerId, amountCents) : doWithdraw(session.playerId, amountCents));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    private Business requireOwnBusiness(UUID playerId)
    {
        Business business = BusinessData.get(server).getByOwner(playerId);
        if (business == null)
            throw new WebActionException("You don't have a business yet.");
        return business;
    }

    private void requireActive(Business business)
    {
        if (business.status == Business.Status.SUSPENDED)
            throw new WebActionException("Your business is suspended. Fund it and reactivate first.");
    }

    private String doFund(UUID playerId, int amountCents)
    {
        Business business = requireOwnBusiness(playerId);
        int balance = MoneyData.get(server).getMoney(playerId);
        if (balance < amountCents)
            throw new WebActionException("You need " + Money.format(amountCents) + " but only have " + Money.format(balance) + ".");

        MoneyData.get(server).addMoney(server, playerId, -amountCents);
        BusinessData.get(server).adjustBalance(business, amountCents);
        return "Funded " + Money.format(amountCents) + " to " + business.name + ".";
    }

    private String doWithdraw(UUID playerId, int amountCents)
    {
        Business business = requireOwnBusiness(playerId);
        if (business.balanceCents < amountCents)
            throw new WebActionException("The business only has " + Money.format(business.balanceCents) + ".");

        BusinessData.get(server).adjustBalance(business, -amountCents);
        MoneyData.get(server).addMoney(server, playerId, amountCents);
        return "Withdrew " + Money.format(amountCents) + " from " + business.name + ".";
    }

    public void handleBuy(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            int amount = WebSupport.parsePositiveInt(form.get("amount"));
            String message = MainThreadExecutor.run(server, () -> doBusinessBuy(session.playerId, id, amount));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    private String doBusinessBuy(UUID playerId, ResourceLocation id, int amount)
    {
        Business business = requireOwnBusiness(playerId);
        requireActive(business);

        MarketEntry entry = MarketData.get(server).getEntry(id);
        if (entry == null)
            throw new WebActionException("That item isn't tradeable.");

        int unitPriceCents = Math.max(1, (int) Math.ceil(entry.currentPrice * 100));
        int costCents = unitPriceCents * amount;
        if (business.balanceCents < costCents)
            throw new WebActionException("The business needs " + Money.format(costCents) + " but only has " + Money.format(business.balanceCents) + ".");

        BusinessData data = BusinessData.get(server);
        data.adjustBalance(business, -costCents);
        MarketData.get(server).applyBuy(id, amount);
        data.addStock(business, id, amount);

        return "Bought " + amount + "x " + id + " for " + Money.format(costCents) + ".";
    }

    public void handleSell(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            int amount = WebSupport.parsePositiveInt(form.get("amount"));
            String message = MainThreadExecutor.run(server, () -> doBusinessSell(session.playerId, id, amount));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    private String doBusinessSell(UUID playerId, ResourceLocation id, int amount)
    {
        Business business = requireOwnBusiness(playerId);
        requireActive(business);

        if (business.stockOf(id) < amount)
            throw new WebActionException("The business only has " + business.stockOf(id) + "x that item.");

        MarketEntry entry = MarketData.get(server).getEntry(id);
        if (entry == null)
            throw new WebActionException("That item isn't tradeable.");

        int unitPriceCents = (int) Math.floor(entry.currentPrice * 100);
        int totalCents = Math.max(0, unitPriceCents * amount);

        BusinessData data = BusinessData.get(server);
        data.removeStock(business, id, amount);
        MarketData.get(server).applySell(id, amount);
        data.adjustBalance(business, totalCents);

        return "Sold " + amount + "x " + id + " for " + Money.format(totalCents) + ".";
    }

    public void handleManufacture(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            UUID recipeId = parseUuid(form.get("recipe"));
            int multiplier = WebSupport.parsePositiveInt(form.get("multiplier"));
            String message = MainThreadExecutor.run(server, () -> doManufacture(session.playerId, recipeId, multiplier));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    private UUID parseUuid(String raw)
    {
        try
        {
            return UUID.fromString(raw);
        }
        catch (Exception e)
        {
            throw new WebActionException("Invalid recipe.");
        }
    }

    private String doManufacture(UUID playerId, UUID recipeId, int multiplier)
    {
        Business business = requireOwnBusiness(playerId);
        requireActive(business);

        BusinessData data = BusinessData.get(server);
        Recipe recipe = data.getRecipe(recipeId);
        if (recipe == null)
            throw new WebActionException("Unknown recipe.");

        for (Map.Entry<ResourceLocation, Integer> input : recipe.inputs.entrySet())
        {
            int needed = input.getValue() * multiplier;
            if (business.stockOf(input.getKey()) < needed)
                throw new WebActionException("Not enough " + input.getKey() + " (need " + needed + ", have " + business.stockOf(input.getKey()) + ").");
        }

        for (Map.Entry<ResourceLocation, Integer> input : recipe.inputs.entrySet())
            data.removeStock(business, input.getKey(), input.getValue() * multiplier);

        int outputAmount = recipe.outputCount * multiplier;
        data.addStock(business, recipe.output, outputAmount);

        return "Manufactured " + outputAmount + "x " + recipe.output + ".";
    }

    public void handleReactivate(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        try
        {
            String message = MainThreadExecutor.run(server, () -> doReactivate(session.playerId));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    private String doReactivate(UUID playerId)
    {
        Business business = requireOwnBusiness(playerId);
        BusinessData data = BusinessData.get(server);
        if (!data.reactivate(business))
            throw new WebActionException("The business needs " + Money.format(data.amountDueCents(business)) + " to reactivate; fund it first.");
        return business.name + " is active again.";
    }

    public void handleSetListed(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            boolean listed = "true".equals(form.get("listed"));
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                if (listed && business.barrels.isEmpty())
                    throw new WebActionException("Place at least one Sell Barrel (owned by you) before enabling your storefront.");
                BusinessData.get(server).setListed(business, listed);
                return listed ? "Storefront enabled." : "Storefront disabled.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleSetHours(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            int openHour = (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("openHour", "0"));
            int closeHour = (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("closeHour", "0"));
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                BusinessData.get(server).setBusinessHours(business, openHour, closeHour);
                return "Hours updated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleToggleClosed(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            boolean closed = "true".equals(form.get("closed"));
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                BusinessData.get(server).setManuallyClosed(business, closed);
                return closed ? "Marked as closed for now." : "Reopened.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleSetNpcCustomersEnabled(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            boolean enabled = "true".equals(form.get("enabled"));
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                BusinessData.get(server).setNpcCustomersEnabledForBusiness(business, enabled);
                return enabled ? "Customer NPCs enabled for your storefront." : "Customer NPCs disabled for your storefront.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleSetEmployeeHired(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            boolean hired = "true".equals(form.get("hired"));
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                BusinessData.get(server).setEmployeeHired(business, hired);
                return hired ? "Employee hired." : "Employee let go.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleReplyToReview(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            UUID reviewId = parseUuid(form.get("reviewId"));
            String reply = form.getOrDefault("reply", "").trim();
            if (reply.isEmpty())
                throw new WebActionException("Enter a reply.");

            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                if (!BusinessData.get(server).setReviewReply(business, reviewId, reply))
                    throw new WebActionException("That review wasn't found.");
                return "Reply posted.";
            });
            WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + session.playerId, message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + session.playerId, e.getMessage(), false);
        }
    }

    public void handleBuyLicense(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                UUID govId;
                try { govId = UUID.fromString(form.get("govId")); }
                catch (Exception e) { throw new WebActionException("Invalid government id."); }

                Government government = TerritoryData.get(server).getGovernmentById(govId);
                if (government == null)
                    throw new WebActionException("Unknown government.");
                if (business.licensedGovernmentIds.contains(govId))
                    throw new WebActionException("You already have a license there.");
                if (business.balanceCents < government.licenseFeeCents)
                    throw new WebActionException("The business needs " + Money.format(government.licenseFeeCents) + " for that license.");

                BusinessData data = BusinessData.get(server);
                data.adjustBalance(business, -government.licenseFeeCents);
                TerritoryData.get(server).addIncome(government, government.licenseFeeCents);
                data.addLicensedGovernment(business, govId);
                return "Bought a license from " + government.name + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handlePurchaseUpgrade(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String type = form.getOrDefault("type", "");
            String message = MainThreadExecutor.run(server, () -> {
                BusinessData data = BusinessData.get(server);
                Business business = requireOwnBusiness(session.playerId);

                String label;
                int cost;
                switch (type)
                {
                    case "awning" -> {
                        if (business.hasAwning) throw new WebActionException("You already have an Awning.");
                        label = "Awning"; cost = data.awningCostCents();
                    }
                    case "priceboard" -> {
                        if (business.hasPriceBoard) throw new WebActionException("You already have a Price Board.");
                        label = "Price Board"; cost = data.priceBoardCostCents();
                    }
                    case "loyaltycard" -> {
                        if (business.hasLoyaltyCard) throw new WebActionException("You already have a Loyalty Card.");
                        label = "Loyalty Card"; cost = data.loyaltyCardCostCents();
                    }
                    case "guarddog" -> {
                        if (business.hasGuardDog) throw new WebActionException("You already have a Guard Dog.");
                        label = "Guard Dog"; cost = data.guardDogCostCents();
                    }
                    default -> throw new WebActionException("Unknown upgrade.");
                }

                if (business.balanceCents < cost)
                    throw new WebActionException("The business needs " + Money.format(cost) + " for that.");

                data.adjustBalance(business, -cost);
                switch (type)
                {
                    case "awning" -> data.setHasAwning(business, true);
                    case "priceboard" -> data.setHasPriceBoard(business, true);
                    case "loyaltycard" -> data.setHasLoyaltyCard(business, true);
                    case "guarddog" -> data.setHasGuardDog(business, true);
                }
                return "Purchased " + label + " for " + Money.format(cost) + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleRunAdvert(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                BusinessData data = BusinessData.get(server);
                Business business = requireOwnBusiness(session.playerId);
                int cost = data.advertCostCents();
                if (business.balanceCents < cost)
                    throw new WebActionException("The business needs " + Money.format(cost) + " to run an advert.");
                data.adjustBalance(business, -cost);
                data.runAdvert(business, data.advertDurationHours());
                return "Advert running for " + data.advertDurationHours() + " hours - customer NPCs can now find you.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleClaimSign(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                requireOwnBusiness(session.playerId); // just validates the caller has a business at all

                ServerPlayer online = server.getPlayerList().getPlayer(session.playerId);
                if (online != null)
                    InventoryUtil.giveOrDrop(online, new ItemStack(ServerMod.BUSINESS_SIGN_BLOCK_ITEM.get()));
                else
                    MailboxData.get(server).addPending(session.playerId,
                            ForgeRegistries.ITEMS.getKey(ServerMod.BUSINESS_SIGN_BLOCK_ITEM.get()), 1);
                return "Check your inventory for a Business Sign to place at your storefront.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    public void handleWithdrawItem(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            int amount = WebSupport.parsePositiveInt(form.get("amount"));
            String message = MainThreadExecutor.run(server, () -> doWithdrawItem(session.playerId, id, amount));
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    // Moves items from a business's virtual stock into the owner's real inventory, so they can be carried to
    // a Sell Barrel by hand (or dropped into one via hopper automation) to actually go up for sale.
    private String doWithdrawItem(UUID playerId, ResourceLocation id, int amount)
    {
        Business business = requireOwnBusiness(playerId);
        if (business.stockOf(id) < amount)
            throw new WebActionException("The business only has " + business.stockOf(id) + "x that item.");

        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null)
            throw new WebActionException("Unknown item.");

        BusinessData.get(server).removeStock(business, id, amount);

        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null)
            InventoryUtil.giveOrDrop(online, new ItemStack(item, amount));
        else
            MailboxData.get(server).addPending(playerId, id, amount);

        return "Withdrew " + amount + "x " + id + " to your personal inventory"
                + (online == null ? " (will be delivered next time you're online)" : "") + ".";
    }

    public void handleSetListing(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            double price = WebSupport.parseNonNegativeDouble(form.getOrDefault("price", "0"));
            int cents = Money.toCents(price);
            String message = MainThreadExecutor.run(server, () -> {
                Business business = requireOwnBusiness(session.playerId);
                BusinessData.get(server).setListingPrice(business, id, cents);
                return cents <= 0 ? "Removed " + id + " from the storefront." : "Listed " + id + " at " + Money.format(cents) + " each.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/business", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/business", e.getMessage(), false);
        }
    }

    // ---------- public directory & storefront ----------

    private record DirectoryRow(UUID ownerId, String name, int itemsListed, double avgStars, int reviewCount,
                                 int totalOrders, int ordersLast7Days, int ordersPrevious7Days, long totalRevenueCents,
                                 Set<String> governmentNames, Set<String> endorsements) {}

    private DirectoryRow toDirectoryRow(Business business)
    {
        double avgStars = business.reviews.isEmpty() ? 0 : business.reviews.stream().mapToDouble(r -> r.stars).average().orElse(0);
        long now = System.currentTimeMillis();
        long sevenDays = 7L * 24 * 60 * 60 * 1000;
        int last7 = (int) business.orders.stream().filter(o -> o.timestampMillis >= now - sevenDays).count();
        int prev7 = (int) business.orders.stream()
                .filter(o -> o.timestampMillis < now - sevenDays && o.timestampMillis >= now - 2 * sevenDays).count();
        long revenue = business.orders.stream().mapToLong(o -> o.totalCents).sum();
        Set<String> governmentNames = SalesTax.applicableGovernments(server, business).stream()
                .map(g -> g.name).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return new DirectoryRow(business.ownerId, business.name, business.listingPricesCents.size(),
                avgStars, business.reviews.size(), business.orders.size(), last7, prev7, revenue,
                governmentNames, business.endorsements);
    }

    // Small reputation badges computed from review/order history - Top Rated (highest average, needs at
    // least 3 reviews so one lucky/unlucky visit can't decide it), Most Popular (most orders all-time), and
    // Fastest Growing (biggest jump in orders this week vs the week before). At most one business earns each,
    // plus one badge per government that's publicly endorsed this business (see Business.endorsements).
    private String reputationBadgesHtml(DirectoryRow row, UUID topRatedOwner, UUID mostPopularOwner, UUID fastestGrowingOwner)
    {
        StringBuilder sb = new StringBuilder();
        if (row.ownerId().equals(topRatedOwner))
            sb.append(" <span class=\"badge up\">&#11088; Top Rated</span>");
        if (row.ownerId().equals(mostPopularOwner))
            sb.append(" <span class=\"badge flat\">&#128293; Most Popular</span>");
        if (row.ownerId().equals(fastestGrowingOwner))
            sb.append(" <span class=\"badge up\">&#128200; Fastest Growing</span>");
        for (String government : row.endorsements())
            sb.append(" <span class=\"badge up\">&#9989; ").append(Html.escape(government)).append(" Endorsed</span>");
        return sb.toString();
    }

    public void renderDirectory(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        List<DirectoryRow> all = MainThreadExecutor.run(server, () -> BusinessData.get(server).getAll().stream()
                .filter(b -> b.listed && !b.listingPricesCents.isEmpty())
                .map(this::toDirectoryRow)
                .toList());

        // Reputation badges (see reputationBadgesHtml) - computed against the whole directory, not just the
        // current page, so they stay stable while paging/searching.
        UUID topRatedOwner = all.stream().filter(r -> r.reviewCount() >= 3)
                .max(Comparator.comparingDouble(DirectoryRow::avgStars)).map(DirectoryRow::ownerId).orElse(null);
        UUID mostPopularOwner = all.stream().filter(r -> r.totalOrders() > 0)
                .max(Comparator.comparingInt(DirectoryRow::totalOrders)).map(DirectoryRow::ownerId).orElse(null);
        UUID fastestGrowingOwner = all.stream().filter(r -> r.ordersLast7Days() > r.ordersPrevious7Days() && r.ordersLast7Days() >= 2)
                .max(Comparator.comparingInt(r -> r.ordersLast7Days() - r.ordersPrevious7Days())).map(DirectoryRow::ownerId).orElse(null);

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String q = query.getOrDefault("q", "");
        WebSupport.Page<DirectoryRow> page = WebSupport.paginate(all, q, WebSupport.parsePageParam(query.get("page")),
                DIRECTORY_PAGE_SIZE, DirectoryRow::name, Comparator.comparing(DirectoryRow::name, String.CASE_INSENSITIVE_ORDER));

        StringBuilder rowsHtml = new StringBuilder();
        for (DirectoryRow row : page.rows())
            rowsHtml.append("""
                    <tr>
                        <td>%s%s</td>
                        <td>%d item%s</td>
                        <td><a class="btn ghost" href="/business/view?owner=%s">View</a></td>
                    </tr>
                    """.formatted(Html.escape(row.name()), reputationBadgesHtml(row, topRatedOwner, mostPopularOwner, fastestGrowingOwner),
                    row.itemsListed(), row.itemsListed() == 1 ? "" : "s", row.ownerId()));

        String body = """
                <div class="topbar"><h1>Businesses</h1><div class="topbar-links"><a href="/leaderboard">Leaderboard</a><a href="/user/business">My Business</a><a href="/user">Back to panel</a></div></div>
                <p class="sub">Player-run storefronts selling at their own prices.</p>
                <div class="card">
                    <h2>&#127970; Directory <span class="count-pill">%d</span></h2>
                    %s
                    <table><thead><tr><th>Business</th><th>Listed items</th><th></th></tr></thead><tbody>%s</tbody></table>
                    %s
                    %s
                </div>
                """.formatted(page.total(), WebSupport.searchBarHtml("/businesses", q, "biz-dir", "Search businesses..."),
                rowsHtml, page.rows().isEmpty() ? "<div class=\"empty-row\">No businesses match your search.</div>" : "",
                WebSupport.pagerHtml("/businesses", q, page, "biz-dir"));
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Businesses", body));
    }

    private static final int LEADERBOARD_SHOWN = 50;

    // A fuller ranking than the directory's small reputation badges - sortable by revenue, rating, or growth
    // (orders this week vs the week before), all computed straight from order/review history.
    public void renderLeaderboard(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String sort = query.getOrDefault("sort", "revenue");
        String gov = query.getOrDefault("gov", "");

        List<DirectoryRow> all = MainThreadExecutor.run(server, () -> BusinessData.get(server).getAll().stream()
                .filter(b -> b.listed && !b.listingPricesCents.isEmpty())
                .map(this::toDirectoryRow)
                .toList());

        List<String> governmentOptions = all.stream().flatMap(r -> r.governmentNames().stream())
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (!gov.isEmpty())
            all = all.stream().filter(r -> r.governmentNames().contains(gov)).toList();

        Comparator<DirectoryRow> comparator = switch (sort)
        {
            case "rating" -> Comparator.comparingDouble(DirectoryRow::avgStars).reversed();
            case "growth" -> Comparator.comparingInt((DirectoryRow r) -> r.ordersLast7Days() - r.ordersPrevious7Days()).reversed();
            default -> Comparator.comparingLong(DirectoryRow::totalRevenueCents).reversed();
        };
        List<DirectoryRow> ranked = all.stream().sorted(comparator).limit(LEADERBOARD_SHOWN).toList();

        StringBuilder rows = new StringBuilder();
        int rank = 1;
        for (DirectoryRow row : ranked)
        {
            rows.append("""
                    <tr>
                        <td>#%d</td>
                        <td><a href="/business/view?owner=%s">%s</a></td>
                        <td>%s</td>
                        <td>%.1f&#9733; (%d)</td>
                        <td>%+d this week</td>
                    </tr>
                    """.formatted(rank, row.ownerId(), Html.escape(row.name()), Money.format((int) Math.min(Integer.MAX_VALUE, row.totalRevenueCents())),
                    row.avgStars(), row.reviewCount(), row.ordersLast7Days() - row.ordersPrevious7Days()));
            rank++;
        }
        String table = ranked.isEmpty()
                ? "<p class=\"sub\">No businesses to rank yet.</p>"
                : "<table><thead><tr><th>#</th><th>Business</th><th>Revenue</th><th>Rating</th><th>Growth</th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        String govQueryParam = gov.isEmpty() ? "" : "&gov=" + java.net.URLEncoder.encode(gov, java.nio.charset.StandardCharsets.UTF_8);
        String sortLinks = """
                <div class="topbar-links">
                    <a href="/leaderboard?sort=revenue%s"%s>By revenue</a>
                    <a href="/leaderboard?sort=rating%s"%s>By rating</a>
                    <a href="/leaderboard?sort=growth%s"%s>By growth</a>
                </div>
                """.formatted(govQueryParam, sort.equals("revenue") ? " style=\"font-weight:700\"" : "",
                govQueryParam, sort.equals("rating") ? " style=\"font-weight:700\"" : "",
                govQueryParam, sort.equals("growth") ? " style=\"font-weight:700\"" : "");

        StringBuilder govOptionsHtml = new StringBuilder("<option value=\"\">All governments</option>");
        for (String option : governmentOptions)
            govOptionsHtml.append("<option value=\"%s\"%s>%s</option>".formatted(
                    Html.escape(option), option.equals(gov) ? " selected" : "", Html.escape(option)));
        String govFilter = """
                <form method="get" action="/leaderboard" class="row-form">
                    <input type="hidden" name="sort" value="%s">
                    <select name="gov" onchange="this.form.submit()">%s</select>
                </form>
                """.formatted(sort, govOptionsHtml);

        String body = """
                <div class="topbar"><h1>Leaderboard</h1><div class="topbar-links"><a href="/businesses">Directory</a><a href="/user">Back to panel</a></div></div>
                <div class="card">
                    <h2>&#127942; Top businesses</h2>
                    %s
                    %s
                    %s
                </div>
                """.formatted(sortLinks, govFilter, table);
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Leaderboard", body));
    }

    public void renderStorefront(HttpExchange exchange) throws IOException
    {
        // Accepts both roles (unlike every other /business/* page) so an admin can reach the Delete button on
        // a review without needing a separate ordinary player login - buying/reviewing stay Role.USER-only.
        Session session = SessionManager.get(WebSupport.getSessionToken(exchange));
        if (session == null || (session.role != Role.USER && session.role != Role.ADMIN))
        {
            WebSupport.redirect(exchange, "/");
            return;
        }

        Map<String, String> query = WebSupport.parseQuery(exchange);
        UUID ownerId;
        try
        {
            ownerId = UUID.fromString(query.getOrDefault("owner", ""));
        }
        catch (Exception e)
        {
            WebSupport.sendHtml(exchange, 404, Html.page("Not Found", "<div class=\"msg error\">Not found. <a href=\"/businesses\">Back</a></div>"));
            return;
        }

        String msgHtml = WebSupport.messageBanner(query);

        // Built entirely on the main thread, same reasoning as renderDashboard: this iterates the business's
        // live listingPricesCents map, which must not be read concurrently with another request mutating it.
        StorefrontView view = MainThreadExecutor.run(server, () -> buildStorefrontView(session.playerId, session.role, ownerId, msgHtml, query));
        if (view == null)
        {
            String body = "<div class=\"card\"><p>This business isn't available.</p><a href=\"/businesses\">Back to directory</a></div>";
            WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Business", body));
            return;
        }
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - " + view.name(), view.body()));
    }

    private record StorefrontView(String name, String body) {}

    private StorefrontView buildStorefrontView(UUID viewerId, Role viewerRole, UUID ownerId, String msgHtml, Map<String, String> query)
    {
        Business business = BusinessData.get(server).getByOwner(ownerId);
        if (business == null || (!business.listed && !business.ownerId.equals(viewerId)))
            return null;

        boolean own = business.ownerId.equals(viewerId);
        boolean suspended = business.status == Business.Status.SUSPENDED;
        Map<ResourceLocation, Integer> barrelStock = barrelStock(business);

        StringBuilder rows = new StringBuilder();
        business.listingPricesCents.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ResourceLocation item = entry.getKey();
                    int priceCents = entry.getValue();
                    int stock = barrelStock.getOrDefault(item, 0);
                    rows.append("""
                            <tr>
                                <td class="item-id">%s%s</td>
                                <td>%s each</td>
                                <td>%d in stock</td>
                                <td>
                                    <form method="post" action="/business/purchase" class="row-form">
                                        <input type="hidden" name="owner" value="%s">
                                        <input type="hidden" name="item" value="%s">
                                        <input type="number" name="amount" min="1" max="%d" value="1"%s>
                                        <button type="submit" class="btn"%s>Buy</button>
                                    </form>
                                </td>
                            </tr>
                            """.formatted(Html.escape(item.toString()), Html.copyButton(item.toString()), Money.format(priceCents),
                            stock, business.ownerId, Html.escape(item.toString()), Math.max(1, stock),
                            (own || suspended || stock <= 0) ? " disabled" : "", (own || suspended || stock <= 0) ? " disabled" : ""));
                });

        String note = own ? "<p class=\"sub\">This is your own storefront - you can't buy from yourself.</p>"
                : suspended ? "<p class=\"sub\">This business is currently suspended and can't sell right now.</p>" : "";
        String table = business.listingPricesCents.isEmpty()
                ? "<p class=\"sub\">Nothing listed for sale right now.</p>"
                : "<table><thead><tr><th>Item</th><th>Price</th><th>Stock</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        boolean open = BusinessData.get(server).isOpenNow(business);
        String openBadge = open ? "<span class=\"badge up\">OPEN now</span>" : "<span class=\"badge down\">CLOSED now</span>";
        String reviewsCard = reviewsCardHtml(business, viewerId, viewerRole == Role.ADMIN, query);

        String body = """
                <div class="topbar"><h1>%s</h1><div class="topbar-links"><a href="/businesses">Businesses</a><a href="/user">Back to panel</a></div></div>
                <div style="margin:-4px 0 10px">%s</div>
                %s
                %s
                <div class="card">
                    <h2>&#128722; For sale</h2>
                    %s
                </div>
                %s
                """.formatted(Html.escape(business.name), openBadge, msgHtml, note, table, reviewsCard);
        return new StorefrontView(business.name, body);
    }

    // ---------- reviews ----------

    private static final int REVIEWS_PAGE_SIZE = 10;

    private String reviewsCardHtml(Business business, UUID viewerId, boolean isAdmin, Map<String, String> query)
    {
        double average = business.reviews.isEmpty() ? 0 : business.reviews.stream().mapToDouble(r -> r.stars).average().orElse(0);
        String q = query.getOrDefault("reviewQ", "");
        WebSupport.Page<Review> page = WebSupport.paginate(business.reviews, q, WebSupport.parsePageParam(query.get("reviewPage")),
                REVIEWS_PAGE_SIZE, r -> r.authorName + " " + r.text, (a, b) -> Long.compare(b.timestampMillis, a.timestampMillis));

        boolean isOwner = business.ownerId.equals(viewerId);
        StringBuilder rows = new StringBuilder();
        for (Review review : page.rows())
            rows.append(reviewRowHtml(business, review, isAdmin, isOwner));
        String table = business.reviews.isEmpty()
                ? "<p class=\"sub\">No reviews yet.</p>"
                : "<table><thead><tr><th>Stars</th><th>Who</th><th>Review</th>%s</tr></thead><tbody>%s</tbody></table>"
                        .formatted(isAdmin ? "<th></th>" : "", rows);

        String summary = business.reviews.isEmpty() ? "No reviews yet." : "%.1f / 5.0 average over %d review%s"
                .formatted(average, business.reviews.size(), business.reviews.size() == 1 ? "" : "s");

        String reviewForm = pendingReviewFormHtml(business, viewerId);

        return """
                <div class="card">
                    <h2>&#11088; Reviews <span class="count-pill">%d</span></h2>
                    <p class="sub">%s</p>
                    %s
                    %s
                    %s
                </div>
                """.formatted(business.reviews.size(), summary, reviewForm, table,
                WebSupport.pagerHtml("/business/view?owner=" + business.ownerId, q, page, "reviews"));
    }

    private String reviewRowHtml(Business business, Review review, boolean isAdmin, boolean isOwner)
    {
        String tagsHtml = review.tags.isEmpty() ? "" : "<div class=\"hint\">" + Html.escape(String.join(", ", review.tags)) + "</div>";
        String textHtml = review.text == null || review.text.isBlank() ? "" : "<div>" + Html.escape(review.text) + "</div>";
        String sourceBadge = review.source == ReviewSource.NPC ? "<span class=\"badge flat\">NPC</span>" : "<span class=\"badge up\">Player</span>";

        String replyHtml;
        if (review.ownerReply != null && !review.ownerReply.isBlank())
            replyHtml = "<div class=\"hint\">&#8618; Owner: " + Html.escape(review.ownerReply) + "</div>";
        else if (isOwner)
            replyHtml = """
                    <form method="post" action="/user/business/review/reply" class="row-form">
                        <input type="hidden" name="reviewId" value="%s">
                        <input type="text" name="reply" placeholder="Reply to this review..." maxlength="200">
                        <button type="submit" class="btn">Reply</button>
                    </form>
                    """.formatted(review.id);
        else
            replyHtml = "";

        String deleteCell = !isAdmin ? "" : """
                <td>
                    <form method="post" action="/admin/business/review/remove" onsubmit="return confirm('Delete this review?');">
                        <input type="hidden" name="owner" value="%s">
                        <input type="hidden" name="reviewId" value="%s">
                        <button type="submit" class="btn danger">Delete</button>
                    </form>
                </td>
                """.formatted(business.ownerId, review.id);
        return """
                <tr>
                    <td>%.1f&#9733;</td>
                    <td>%s %s</td>
                    <td>%s%s%s</td>
                    %s
                </tr>
                """.formatted(review.stars, Html.escape(review.authorName), sourceBadge, textHtml, tagsHtml, replyHtml, deleteCell);
    }

    // If the viewer has a completed order on this business they haven't yet reviewed, offer the one-review-
    // per-order form: a 0.0-5.0 star rating plus good/bad tag checkboxes (no free text - matches the NPC
    // review vocabulary in review.ReviewTags so both sources render the same way).
    private String pendingReviewFormHtml(Business business, UUID viewerId)
    {
        List<PendingOrderReview> mine = business.pendingPlayerReviews.stream().filter(p -> p.buyerId.equals(viewerId)).toList();
        if (mine.isEmpty())
            return "";

        PendingOrderReview pending = mine.get(0);
        StringBuilder goodBoxes = new StringBuilder();
        for (String tag : ReviewTags.GOOD)
            goodBoxes.append(checkboxHtml("good", tag));
        StringBuilder badBoxes = new StringBuilder();
        for (String tag : ReviewTags.BAD)
            badBoxes.append(checkboxHtml("bad", tag));

        return """
                <div class="card" style="margin:0 0 14px;background:#171a22">
                    <h2 style="font-size:1em">Review your order (%s)</h2>
                    <form method="post" action="/business/review/submit">
                        <input type="hidden" name="owner" value="%s">
                        <input type="hidden" name="orderId" value="%s">
                        <label>Stars (0.0 - 5.0)</label>
                        <input type="number" step="0.1" min="0" max="5" name="stars" value="5.0" required>
                        <label>What was good?</label>
                        %s
                        <label>What was bad?</label>
                        %s
                        <input type="submit" value="Submit review">
                    </form>
                </div>
                """.formatted(Html.escape(pending.itemSummary), business.ownerId, pending.orderId, goodBoxes, badBoxes);
    }

    private String checkboxHtml(String group, String tag)
    {
        return """
                <label style="display:flex;align-items:center;gap:8px;margin:4px 0;font-weight:400">
                    <input type="checkbox" name="%s_%s" value="true" style="width:auto">%s
                </label>
                """.formatted(group, ReviewTags.slug(tag), Html.escape(tag));
    }

    public void handleSubmitReview(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        UUID ownerId;
        try
        {
            ownerId = parseUuid(form.get("owner"));
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/businesses", "Invalid business.", false);
            return;
        }

        try
        {
            UUID orderId = parseUuid(form.getOrDefault("orderId", ""));
            double stars = Math.max(0.0, Math.min(5.0, WebSupport.parseNonNegativeDouble(form.getOrDefault("stars", "0"))));
            List<String> goodTags = ReviewTags.GOOD.stream().filter(tag -> "true".equals(form.get("good_" + ReviewTags.slug(tag)))).toList();
            List<String> badTags = ReviewTags.BAD.stream().filter(tag -> "true".equals(form.get("bad_" + ReviewTags.slug(tag)))).toList();

            String message = MainThreadExecutor.run(server, () -> doSubmitReview(session.playerId, session.playerName, ownerId, orderId, stars, goodTags, badTags));
            WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + ownerId, message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + ownerId, e.getMessage(), false);
        }
    }

    private String doSubmitReview(UUID playerId, String playerName, UUID ownerId, UUID orderId, double stars, List<String> goodTags, List<String> badTags)
    {
        BusinessData data = BusinessData.get(server);
        Business business = data.getByOwner(ownerId);
        if (business == null)
            throw new WebActionException("That business isn't available.");

        if (!data.consumePendingOrderReview(business, playerId, orderId))
            throw new WebActionException("That order was already reviewed, or isn't yours.");

        Review review = new Review(UUID.randomUUID(), ReviewSource.PLAYER, playerName, playerId, stars, null, System.currentTimeMillis());
        for (String tag : goodTags)
            if (ReviewTags.GOOD.contains(tag))
                review.tags.add(tag);
        for (String tag : badTags)
            if (ReviewTags.BAD.contains(tag))
                review.tags.add(tag);

        data.addReview(business, review);
        return "Thanks for your review!";
    }

    public void handleAdminRemoveReview(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        UUID ownerId;
        UUID reviewId;
        try
        {
            ownerId = parseUuid(form.get("owner"));
            reviewId = parseUuid(form.get("reviewId"));
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/businesses", "Invalid business or review.", false);
            return;
        }

        boolean removed = MainThreadExecutor.run(server, () -> {
            BusinessData data = BusinessData.get(server);
            Business business = data.getByOwner(ownerId);
            return business != null && data.removeReview(business, reviewId);
        });
        WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + ownerId, removed ? "Review deleted." : "That review wasn't found.", removed);
    }

    public void handlePurchase(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        UUID ownerId;
        try
        {
            ownerId = parseUuid(form.get("owner"));
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/businesses", "Invalid business.", false);
            return;
        }

        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            int amount = WebSupport.parsePositiveInt(form.get("amount"));
            String message = MainThreadExecutor.run(server, () -> doPurchase(session.playerId, ownerId, id, amount));
            WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + ownerId, message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/business/view?owner=" + ownerId, e.getMessage(), false);
        }
    }

    private String doPurchase(UUID buyerId, UUID ownerId, ResourceLocation id, int amount)
    {
        if (ownerId.equals(buyerId))
            throw new WebActionException("You can't buy from your own business.");

        BusinessData data = BusinessData.get(server);
        Business business = data.getByOwner(ownerId);
        if (business == null || !business.listed)
            throw new WebActionException("That business isn't available.");
        if (business.status == Business.Status.SUSPENDED)
            throw new WebActionException("That business is currently suspended.");

        Integer priceCents = business.listingPricesCents.get(id);
        if (priceCents == null)
            throw new WebActionException("That item isn't listed.");

        int available = barrelStock(business).getOrDefault(id, 0);
        if (available < amount)
            throw new WebActionException("Only " + available + " in stock.");

        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null)
            throw new WebActionException("Unknown item.");

        int totalCents = priceCents * amount;
        int balance = MoneyData.get(server).getMoney(buyerId);
        if (balance < totalCents)
            throw new WebActionException("You need " + Money.format(totalCents) + " but only have " + Money.format(balance) + ".");

        if (!removeFromBarrels(business, id, amount))
            throw new WebActionException("Stock just changed - try again.");

        MoneyData.get(server).addMoney(server, buyerId, -totalCents);
        // The buyer still pays the full listed price - any government sales tax comes out of the business's
        // own cut, not the buyer's.
        data.adjustBalance(business, SalesTax.applyAndGetNet(server, business, totalCents));

        ServerPlayer online = server.getPlayerList().getPlayer(buyerId);
        if (online != null)
            InventoryUtil.giveOrDrop(online, new ItemStack(item, amount));
        else
            MailboxData.get(server).addPending(buyerId, id, amount);

        String itemSummary = amount + "x " + id;
        data.addPendingOrderReview(business, new PendingOrderReview(UUID.randomUUID(), buyerId, itemSummary, System.currentTimeMillis()));

        String buyerName = online != null ? online.getGameProfile().getName()
                : server.getProfileCache() != null ? server.getProfileCache().get(buyerId).map(p -> p.getName()).orElse("Player") : "Player";
        data.addOrder(business, new Order(UUID.randomUUID(), ReviewSource.PLAYER, buyerName, itemSummary, totalCents, System.currentTimeMillis()));

        return "Bought " + amount + "x " + id + " from " + business.name + " for " + Money.format(totalCents) + ".";
    }

    // ---------- admin ----------

    // Built entirely on the main thread, same reasoning as renderDashboard: overviewCardHtml() reads live
    // Business objects (balance/status/etc.) that could otherwise be read mid-mutation by another request or
    // the billing tick.
    public String adminCardsHtml()
    {
        return MainThreadExecutor.run(server, () -> {
            BusinessData data = BusinessData.get(server);
            return settingsCardHtml(data) + recipesCardHtml(data) + overviewCardHtml(data);
        });
    }

    private String settingsCardHtml(BusinessData data)
    {
        return """
                <div class="card">
                    <h2>&#127970; Business settings</h2>
                    <form method="post" action="/admin/business/settings">
                        <label>Registration fee ($)</label>
                        <input type="number" step="0.01" min="0" name="registrationFee" value="%.2f">
                        <label>Weekly fee ($)</label>
                        <input type="number" step="0.01" min="0" name="weeklyFee" value="%.2f">
                        <label>Sell Barrel fee ($ per barrel, per real day)</label>
                        <input type="number" step="0.01" min="0" name="sellBarrelFee" value="%.2f">
                        <label>If a business can't pay what it owes</label>
                        <select name="policy">
                            <option value="SUSPEND"%s>Suspend (frozen until manually paid off)</option>
                            <option value="DISSOLVE"%s>Dissolve immediately (balance refunded to owner)</option>
                            <option value="GRACE_THEN_SUSPEND"%s>Grace period, then suspend</option>
                        </select>
                        <label>Grace period (days - only used by "grace period then suspend")</label>
                        <input type="number" min="0" name="graceDays" value="%d">
                        <label style="display:flex;align-items:center;gap:8px;margin-top:16px">
                            <input type="checkbox" name="showMarketPrices" value="true" style="width:auto"%s>
                            Show players the wholesale market prices businesses pay (default off)
                        </label>
                        <label style="display:flex;align-items:center;gap:8px;margin-top:10px">
                            <input type="checkbox" name="physicalNpcEnabled" value="true" style="width:auto"%s>
                            Spawn physical customer NPCs that queue up and can be served in-game (default off)
                        </label>
                        <label>Customer spawn check interval (minutes, per business)</label>
                        <input type="number" min="1" name="npcIntervalMinutes" value="%d">
                        <label>Base max a customer spends per visit ($, scaled by the business's recent reviews)</label>
                        <input type="number" step="0.01" min="0" name="npcMaxSpend" value="%.2f">
                        <label>Max customer spawn distance from a queue point (blocks)</label>
                        <input type="number" min="1" name="npcSpawnRadius" value="%d">
                        <label>Business Sign maintenance fee ($/month, folded into the weekly bill)</label>
                        <input type="number" step="0.01" min="0" name="signFee" value="%.2f">
                        <label>Advert cost ($ per campaign)</label>
                        <input type="number" step="0.01" min="0" name="advertCost" value="%.2f">
                        <label>Advert duration (hours of discoverability per campaign)</label>
                        <input type="number" min="1" name="advertDuration" value="%d">
                        <label style="display:flex;align-items:center;gap:8px;margin-top:10px">
                            <input type="checkbox" name="wanderersEnabled" value="true" style="width:auto"%s>
                            Spawn ambient wandering NPCs with no destination (default off)
                        </label>
                        <p class="sub">They wander until they recall a business they already know, or spot a Business Sign - either way they'll then head over and shop.</p>
                        <label>Wanderer spawn interval (minutes)</label>
                        <input type="number" min="1" name="wandererInterval" value="%d">
                        <label>Max wanderers at once (server-wide)</label>
                        <input type="number" min="0" name="maxWanderers" value="%d">
                        <label>Employee wage ($/week, only charged while a business has one hired)</label>
                        <input type="number" step="0.01" min="0" name="employeeWage" value="%.2f">
                        <label>Awning cost ($, one-time)</label>
                        <input type="number" step="0.01" min="0" name="awningCost" value="%.2f">
                        <label>Price Board cost ($, one-time)</label>
                        <input type="number" step="0.01" min="0" name="priceBoardCost" value="%.2f">
                        <label>Loyalty Card cost ($, one-time)</label>
                        <input type="number" step="0.01" min="0" name="loyaltyCardCost" value="%.2f">
                        <label>Guard Dog cost ($, one-time)</label>
                        <input type="number" step="0.01" min="0" name="guardDogCost" value="%.2f">
                        <label style="display:flex;align-items:center;gap:8px;margin-top:10px">
                            <input type="checkbox" name="theftEnabled" value="true" style="width:auto"%s>
                            Unguarded, unclaimed storefronts can rarely get robbed instead of bought from (default off)
                        </label>
                        <label>Theft chance per check (%%, before Guard Dog reduction)</label>
                        <input type="number" step="0.1" min="0" name="theftChance" value="%.1f">
                        <input type="submit" value="Save business settings">
                    </form>
                </div>
                """.formatted(data.registrationFeeCents() / 100.0, data.weeklyFeeCents() / 100.0, data.sellBarrelDailyFeeCents() / 100.0,
                selectedIf(data.missedPaymentPolicy() == MissedPaymentPolicy.SUSPEND),
                selectedIf(data.missedPaymentPolicy() == MissedPaymentPolicy.DISSOLVE),
                selectedIf(data.missedPaymentPolicy() == MissedPaymentPolicy.GRACE_THEN_SUSPEND),
                data.graceDays(), data.showMarketPricesToPlayers() ? " checked" : "",
                data.physicalNpcEnabled() ? " checked" : "", data.npcSpawnIntervalMinutes(), data.npcBaseMaxSpendCents() / 100.0,
                data.npcSpawnRadiusBlocks(), data.signFeeCentsPerMonth() / 100.0, data.advertCostCents() / 100.0, data.advertDurationHours(),
                data.wanderersEnabled() ? " checked" : "", data.wandererSpawnIntervalMinutes(), data.maxWanderers(),
                data.employeeWageCentsPerWeek() / 100.0, data.awningCostCents() / 100.0, data.priceBoardCostCents() / 100.0,
                data.loyaltyCardCostCents() / 100.0, data.guardDogCostCents() / 100.0,
                data.theftEnabled() ? " checked" : "", data.theftChancePercent());
    }

    private String selectedIf(boolean condition)
    {
        return condition ? " selected" : "";
    }

    private String recipesCardHtml(BusinessData data)
    {
        StringBuilder rows = new StringBuilder();
        for (Recipe recipe : data.getRecipes())
            rows.append("""
                    <tr>
                        <td class="item-id">%s</td>
                        <td>
                            <form method="post" action="/admin/business/recipe/remove" onsubmit="return confirm('Remove this recipe?');">
                                <input type="hidden" name="id" value="%s">
                                <button type="submit" class="btn danger">Remove</button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(Html.escape(recipeSummary(recipe)), recipe.id));
        String table = data.getRecipes().isEmpty() ? "<p class=\"sub\">No manufacturing recipes yet.</p>"
                : "<table><tbody>%s</tbody></table>".formatted(rows);

        return """
                <div class="card">
                    <h2>&#9881;&#65039; Manufacturing recipes</h2>
                    <p class="sub">What businesses can convert with the "Manufacture" action. Up to 3 input item types per recipe.</p>
                    %s
                    <form method="post" action="/admin/business/recipe/add">
                        <label>Output item</label>
                        <input type="text" name="output" placeholder="minecraft:iron_block" required>
                        <label>Output quantity</label>
                        <input type="number" name="outputCount" min="1" value="1" required>
                        <label>Input 1 (required)</label>
                        <input type="text" name="input1" placeholder="minecraft:iron_ingot">
                        <input type="number" name="input1Count" min="1" placeholder="qty">
                        <label>Input 2 (optional)</label>
                        <input type="text" name="input2">
                        <input type="number" name="input2Count" min="1" placeholder="qty">
                        <label>Input 3 (optional)</label>
                        <input type="text" name="input3">
                        <input type="number" name="input3Count" min="1" placeholder="qty">
                        <input type="submit" value="Add recipe">
                    </form>
                </div>
                """.formatted(table);
    }

    private String overviewCardHtml(BusinessData data)
    {
        List<Business> all = data.getAll();
        all.sort(Comparator.comparing(b -> b.name, String.CASE_INSENSITIVE_ORDER));

        StringBuilder rows = new StringBuilder();
        for (Business business : all)
            rows.append("""
                    <tr>
                        <td>%s</td>
                        <td>%s</td>
                        <td>%s</td>
                        <td>%s</td>
                        <td>%d</td>
                        <td><a class="btn ghost" href="/business/view?owner=%s">View / moderate reviews</a></td>
                    </tr>
                    """.formatted(Html.escape(business.name), statusBadgeHtml(business),
                    Money.format(business.balanceCents), business.listed ? "Listed" : "Private",
                    business.reviews.size(), business.ownerId));

        String table = all.isEmpty() ? "<p class=\"sub\">No businesses registered yet.</p>"
                : "<table><thead><tr><th>Name</th><th>Status</th><th>Balance</th><th>Storefront</th><th>Reviews</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        return """
                <div class="card">
                    <h2>&#128203; Businesses overview <span class="count-pill">%d</span></h2>
                    %s
                </div>
                """.formatted(all.size(), table);
    }

    public void handleAdminSettings(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            int registrationFee = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("registrationFee")));
            int weeklyFee = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("weeklyFee")));
            int sellBarrelFee = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("sellBarrelFee")));
            int graceDays = Math.max(0, (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("graceDays", "0")));
            boolean showMarketPrices = "true".equals(form.get("showMarketPrices"));
            boolean physicalNpcEnabled = "true".equals(form.get("physicalNpcEnabled"));
            int npcIntervalMinutes = Math.max(1, (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("npcIntervalMinutes", "30")));
            int npcMaxSpend = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("npcMaxSpend", "0")));
            int npcSpawnRadius = Math.max(1, (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("npcSpawnRadius", "300")));
            int signFee = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("signFee", "1")));
            int advertCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("advertCost", "10")));
            int advertDuration = Math.max(1, (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("advertDuration", "24")));
            boolean wanderersEnabled = "true".equals(form.get("wanderersEnabled"));
            int wandererInterval = Math.max(1, (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("wandererInterval", "5")));
            int maxWanderers = Math.max(0, (int) WebSupport.parseNonNegativeDouble(form.getOrDefault("maxWanderers", "10")));
            int employeeWage = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("employeeWage", "20")));
            int awningCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("awningCost", "50")));
            int priceBoardCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("priceBoardCost", "50")));
            int loyaltyCardCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("loyaltyCardCost", "100")));
            int guardDogCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("guardDogCost", "150")));
            boolean theftEnabled = "true".equals(form.get("theftEnabled"));
            double theftChance = WebSupport.parseNonNegativeDouble(form.getOrDefault("theftChance", "2.0"));
            MissedPaymentPolicy policy;
            try
            {
                policy = MissedPaymentPolicy.valueOf(form.getOrDefault("policy", "SUSPEND"));
            }
            catch (IllegalArgumentException e)
            {
                throw new WebActionException("Invalid policy.");
            }

            MainThreadExecutor.run(server, () -> {
                BusinessData data = BusinessData.get(server);
                data.setRegistrationFeeCents(registrationFee);
                data.setWeeklyFeeCents(weeklyFee);
                data.setSellBarrelDailyFeeCents(sellBarrelFee);
                data.setMissedPaymentPolicy(policy);
                data.setGraceDays(graceDays);
                data.setShowMarketPricesToPlayers(showMarketPrices);
                data.setPhysicalNpcEnabled(physicalNpcEnabled);
                data.setNpcSpawnIntervalMinutes(npcIntervalMinutes);
                data.setNpcBaseMaxSpendCents(npcMaxSpend);
                data.setNpcSpawnRadiusBlocks(npcSpawnRadius);
                data.setSignFeeCentsPerMonth(signFee);
                data.setAdvertCostCents(advertCost);
                data.setAdvertDurationHours(advertDuration);
                data.setWanderersEnabled(wanderersEnabled);
                data.setWandererSpawnIntervalMinutes(wandererInterval);
                data.setMaxWanderers(maxWanderers);
                data.setEmployeeWageCentsPerWeek(employeeWage);
                data.setAwningCostCents(awningCost);
                data.setPriceBoardCostCents(priceBoardCost);
                data.setLoyaltyCardCostCents(loyaltyCardCost);
                data.setGuardDogCostCents(guardDogCost);
                data.setTheftEnabled(theftEnabled);
                data.setTheftChancePercent(theftChance);
                return null;
            });
            WebSupport.redirectWithMessage(exchange, "/admin", "Business settings updated.", true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    public void handleAdminAddRecipe(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation output = WebSupport.parseItemId(form.get("output"));
            if (!ForgeRegistries.ITEMS.containsKey(output))
                throw new WebActionException("Unknown output item: " + form.get("output"));
            int outputCount = WebSupport.parsePositiveInt(form.get("outputCount"));

            Recipe recipe = new Recipe(UUID.randomUUID(), output, outputCount);
            for (int i = 1; i <= 3; i++)
            {
                String itemStr = form.get("input" + i);
                if (itemStr == null || itemStr.isBlank())
                    continue;
                ResourceLocation inputId = WebSupport.parseItemId(itemStr);
                if (!ForgeRegistries.ITEMS.containsKey(inputId))
                    throw new WebActionException("Unknown input item: " + itemStr);
                int count = WebSupport.parsePositiveInt(form.get("input" + i + "Count"));
                recipe.inputs.put(inputId, count);
            }
            if (recipe.inputs.isEmpty())
                throw new WebActionException("A recipe needs at least one input item.");

            MainThreadExecutor.run(server, () -> { BusinessData.get(server).addRecipe(recipe); return null; });
            WebSupport.redirectWithMessage(exchange, "/admin", "Added recipe for " + output + ".", true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    public void handleAdminRemoveRecipe(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            UUID id = parseUuid(form.get("id"));
            boolean removed = MainThreadExecutor.run(server, () -> BusinessData.get(server).removeRecipe(id));
            WebSupport.redirectWithMessage(exchange, "/admin", removed ? "Recipe removed." : "That recipe wasn't found.", removed);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }
}
