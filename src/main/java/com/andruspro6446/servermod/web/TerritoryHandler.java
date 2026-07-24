package com.andruspro6446.servermod.web;

import com.andruspro6446.servermod.ServerMod;
import com.andruspro6446.servermod.business.Business;
import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.territory.BillingStatus;
import com.andruspro6446.servermod.territory.City;
import com.andruspro6446.servermod.territory.ClaimPermission;
import com.andruspro6446.servermod.territory.ClaimShovelItem;
import com.andruspro6446.servermod.territory.ClaimType;
import com.andruspro6446.servermod.territory.Country;
import com.andruspro6446.servermod.territory.CountryLaw;
import com.andruspro6446.servermod.territory.Government;
import com.andruspro6446.servermod.territory.GovPermission;
import com.andruspro6446.servermod.territory.JailCell;
import com.andruspro6446.servermod.territory.JailListener;
import com.andruspro6446.servermod.territory.LandClaim;
import com.andruspro6446.servermod.territory.LawConfig;
import com.andruspro6446.servermod.territory.MissedPaymentPolicy;
import com.andruspro6446.servermod.territory.PermissionMode;
import com.andruspro6446.servermod.territory.Rect;
import com.andruspro6446.servermod.territory.Report;
import com.andruspro6446.servermod.territory.ReportStatus;
import com.andruspro6446.servermod.territory.Sentence;
import com.andruspro6446.servermod.territory.ShovelTier;
import com.andruspro6446.servermod.territory.TerritoryData;
import com.andruspro6446.servermod.util.InventoryUtil;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Routes and renders everything under /user/land (a player's own claims + the cities/countries they found or
// hold office in) and /governments (a public read-only directory). Split out of WebHandler for the same
// reason BusinessHandler is: a sizable feature in its own right. Anything touching TerritoryData is built
// entirely on the main thread via MainThreadExecutor, matching every other handler in this package - its maps
// are live, mutable, unsynchronized state.
public class TerritoryHandler
{
    private final MinecraftServer server;

    public TerritoryHandler(MinecraftServer server)
    {
        this.server = server;
    }

    // ---------- dashboard ----------

    public void renderDashboard(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String msgHtml = WebSupport.messageBanner(query);

        String body = MainThreadExecutor.run(server, () -> dashboardHtml(TerritoryData.get(server), session, msgHtml));
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - My Land", body));
    }

    private String dashboardHtml(TerritoryData data, Session session, String msgHtml)
    {
        List<LandClaim> claims = data.getLandClaimsOwnedBy(session.playerId);
        StringBuilder claimsHtml = new StringBuilder();
        for (LandClaim claim : claims)
            claimsHtml.append(landClaimCardHtml(data, claim));

        return """
                <div class="topbar"><h1>My Land</h1><div class="topbar-links"><a href="/governments">Governments</a><a href="/user/business">My Business</a><a href="/user">Back to panel</a></div></div>
                %s
                %s
                <div class="card">
                    <h2>&#9935; Buy a claim shovel</h2>
                    <p class="sub">A land claim can be made anywhere; founding a city or country needs its own named shovel first (<code>/land name &lt;name&gt;</code> while holding one).</p>
                    %s
                </div>
                %s
                %s
                %s
                """.formatted(msgHtml,
                claimsHtml.isEmpty() ? "<div class=\"card\"><p class=\"sub\">You don't own any land claims yet. Buy a claim shovel below to claim some.</p></div>" : claimsHtml.toString(),
                shovelBuyHtml(data), reportFormHtml(data, session.playerId), reportsInboxHtml(data, session.playerId),
                governmentsHtml(data, session.playerId));
    }

    // ---------- land claim cards ----------

    private String landClaimCardHtml(TerritoryData data, LandClaim claim)
    {
        String dim = claim.region.dimension.location().toString();
        long blocks = claim.region.blockCount();
        int dueCents = data.landRentBreakdown(claim.region).values().stream().mapToInt(Integer::intValue).sum();
        long daysLeft = Math.max(0, (claim.billing.nextBillingAtMillis - System.currentTimeMillis()) / (24L * 60 * 60 * 1000));
        boolean suspended = claim.billing.status != BillingStatus.ACTIVE;

        String billingNote = claim.billing.status == BillingStatus.SUSPENDED
                ? "Suspended - reactivate below to resume."
                : "Next bill: " + Money.format(dueCents) + "/week in " + daysLeft + " day" + (daysLeft == 1 ? "" : "s") + ".";

        String reactivateHtml = claim.billing.status == BillingStatus.SUSPENDED
                ? """
                        <form method="post" action="/user/land/claim/reactivate" style="margin-top:10px">
                            <input type="hidden" name="claimId" value="%s">
                            <input type="submit" value="Pay %s and reactivate">
                        </form>
                        """.formatted(claim.id, Money.format(dueCents))
                : "";

        return """
                <div class="card">
                    <h2>&#127794; Land claim <span class="badge %s">%s</span></h2>
                    <p class="sub">%d blocks in %s. %s</p>
                    %s
                    <label style="margin-top:16px">Permission rules</label>
                    %s
                    <p class="hint">Container-Take's Blocked mode is tracked but not yet hard-enforced - use Reportable there for now.</p>
                    <label>Trusted players</label>
                    %s
                    <form method="post" action="/user/land/claim/trust" class="row-form">
                        <input type="hidden" name="claimId" value="%s">
                        <input type="text" name="player" placeholder="Player name" required style="flex:1">
                        <select name="permission">%s</select>
                        <button type="submit" class="btn">Trust</button>
                    </form>
                </div>
                """.formatted(suspended ? "down" : "up", claim.billing.status, blocks, dim, billingNote, reactivateHtml,
                claimRuleRowsHtml(claim), trustRowsHtml(claim), claim.id, permissionOptionsHtml());
    }

    private String claimRuleRowsHtml(LandClaim claim)
    {
        StringBuilder sb = new StringBuilder();
        for (ClaimPermission permission : ClaimPermission.values())
        {
            PermissionMode current = claim.rules.getOrDefault(permission, PermissionMode.ALLOWED);
            StringBuilder options = new StringBuilder();
            for (PermissionMode mode : PermissionMode.values())
                options.append("<option value=\"%s\"%s>%s</option>".formatted(mode.name(), mode == current ? " selected" : "", mode.name()));
            sb.append("""
                    <div class="row-form">
                        <span style="flex:1;color:#c9cdd8;font-size:.88em">%s</span>
                        <form method="post" action="/user/land/claim/rule" class="row-form" style="margin:0">
                            <input type="hidden" name="claimId" value="%s">
                            <input type="hidden" name="permission" value="%s">
                            <select name="mode">%s</select>
                            <button type="submit" class="btn">Set</button>
                        </form>
                    </div>
                    """.formatted(permission.name(), claim.id, permission.name(), options));
        }
        return sb.toString();
    }

    private String trustRowsHtml(LandClaim claim)
    {
        if (claim.trusted.isEmpty())
            return "<p class=\"sub\">No one is individually trusted on this claim.</p>";

        StringBuilder rows = new StringBuilder();
        for (Map.Entry<UUID, Set<ClaimPermission>> entry : claim.trusted.entrySet())
        {
            String name = playerName(entry.getKey());
            String perms = entry.getValue().stream().map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("");
            rows.append("""
                    <tr>
                        <td>%s</td>
                        <td>%s</td>
                        <td>
                            <form method="post" action="/user/land/claim/untrust">
                                <input type="hidden" name="claimId" value="%s">
                                <input type="hidden" name="player" value="%s">
                                <button type="submit" class="btn danger">Remove</button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(Html.escape(name), Html.escape(perms), claim.id, Html.escape(name)));
        }
        return "<table><thead><tr><th>Player</th><th>Permissions</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);
    }

    private String permissionOptionsHtml()
    {
        StringBuilder sb = new StringBuilder();
        for (ClaimPermission p : ClaimPermission.values())
            sb.append("<option value=\"%s\">%s</option>".formatted(p.name(), p.name()));
        return sb.toString();
    }

    // ---------- claim shovels ----------

    private String shovelBuyHtml(TerritoryData data)
    {
        StringBuilder sb = new StringBuilder();
        for (ClaimType type : ClaimType.values())
        {
            List<ShovelTier> tiers = data.getTiers(type);
            if (tiers.isEmpty())
                continue;

            StringBuilder rows = new StringBuilder();
            for (ShovelTier tier : tiers)
            {
                rows.append("""
                        <tr>
                            <td>%s</td>
                            <td>%s</td>
                            <td>%d use%s</td>
                            <td>%d blocks max</td>
                            <td>
                                <form method="post" action="/user/land/shovel/buy" class="row-form">
                                    <input type="hidden" name="type" value="%s">
                                    <input type="hidden" name="tier" value="%s">
                                    <button type="submit" class="btn">Buy</button>
                                </form>
                            </td>
                        </tr>
                        """.formatted(Html.escape(tier.name), Money.format(tier.priceCents), tier.maxCharges,
                        tier.maxCharges == 1 ? "" : "s", tier.maxAreaBlocks, type.name(), Html.escape(tier.name)));
            }
            sb.append("<p class=\"sub\" style=\"margin-top:14px;text-transform:uppercase;font-size:.78em;letter-spacing:.04em\">%s</p>"
                    .formatted(type.name()));
            sb.append("<table><thead><tr><th>Tier</th><th>Price</th><th>Uses</th><th>Max area</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows));
        }
        return sb.length() == 0 ? "<p class=\"sub\">No claim shovel tiers are configured yet.</p>" : sb.toString();
    }

    private Item shovelItemFor(ClaimType type)
    {
        return switch (type)
        {
            case LAND -> ServerMod.LAND_CLAIM_SHOVEL.get();
            case CITY -> ServerMod.CITY_CLAIM_SHOVEL.get();
            case COUNTRY -> ServerMod.COUNTRY_CLAIM_SHOVEL.get();
        };
    }

    // ---------- admin: tiers & land/government settings ----------

    public String adminCardsHtml()
    {
        return MainThreadExecutor.run(server, () -> {
            TerritoryData data = TerritoryData.get(server);
            return tiersCardHtml(data) + adminSettingsCardHtml(data);
        });
    }

    private String tiersCardHtml(TerritoryData data)
    {
        StringBuilder sb = new StringBuilder();
        for (ClaimType type : ClaimType.values())
            sb.append(tierTypeSectionHtml(data, type));

        return """
                <div class="card">
                    <h2>&#9935; Claim shovel tiers</h2>
                    <p class="sub">Players can't buy a claim shovel of a given type until at least one tier exists for it.</p>
                    %s
                </div>
                """.formatted(sb);
    }

    private String tierTypeSectionHtml(TerritoryData data, ClaimType type)
    {
        List<ShovelTier> tiers = data.getTiers(type);
        StringBuilder rows = new StringBuilder();
        for (ShovelTier tier : tiers)
        {
            rows.append("""
                    <tr>
                        <td>%s</td><td>%s</td><td>%d</td><td>%d</td>
                        <td>
                            <form method="post" action="/admin/land/tier/remove" onsubmit="return confirm('Remove this tier?');">
                                <input type="hidden" name="type" value="%s">
                                <input type="hidden" name="name" value="%s">
                                <button type="submit" class="btn danger">Remove</button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(Html.escape(tier.name), Money.format(tier.priceCents), tier.maxCharges, tier.maxAreaBlocks,
                    type.name(), Html.escape(tier.name)));
        }
        String table = tiers.isEmpty() ? "<p class=\"sub\">No tiers yet.</p>"
                : "<table><thead><tr><th>Name</th><th>Price</th><th>Uses</th><th>Max area</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        return """
                <p class="sub" style="margin-top:14px;text-transform:uppercase;font-size:.78em;letter-spacing:.04em">%s</p>
                %s
                <form method="post" action="/admin/land/tier/add" class="row-form">
                    <input type="hidden" name="type" value="%s">
                    <input type="text" name="name" placeholder="Tier name" required style="flex:1">
                    <input type="number" step="0.01" min="0" name="price" placeholder="Price $" required style="flex:1">
                    <input type="number" min="1" name="maxCharges" placeholder="Uses" required style="flex:1">
                    <input type="number" min="1" name="maxArea" placeholder="Max blocks" required style="flex:1">
                    <button type="submit" class="btn">Add</button>
                </form>
                """.formatted(type.name(), table, type.name());
    }

    private String adminSettingsCardHtml(TerritoryData data)
    {
        StringBuilder rows = new StringBuilder();
        for (ClaimType type : ClaimType.values())
        {
            rows.append("""
                    <div class="row-form">
                        <span style="flex:0 0 90px;color:#c9cdd8;font-size:.88em">%s</span>
                        <form method="post" action="/admin/land/policy" class="row-form" style="margin:0;flex:1">
                            <input type="hidden" name="type" value="%s">
                            <select name="policy">%s</select>
                            <input type="number" min="0" name="graceDays" value="%d" style="width:80px" title="Grace days">
                            <button type="submit" class="btn">Save</button>
                        </form>
                    </div>
                    """.formatted(type.name(), type.name(), policyOptionsHtml(data.policyFor(type)), data.graceDaysFor(type)));
        }

        return """
                <div class="card">
                    <h2>&#9878; Land &amp; government settings</h2>
                    <label>Country &rarr; admin rate ($/block/week)</label>
                    <form method="post" action="/admin/land/rate" class="row-form">
                        <input type="number" step="0.01" min="0" name="amount" value="%.2f">
                        <button type="submit" class="btn">Save</button>
                    </form>
                    <label style="margin-top:16px">Missed-payment policy &amp; grace days</label>
                    <p class="hint">Grace days only apply to the grace_then_suspend policy.</p>
                    %s
                    <label style="margin-top:16px">Government commerce action costs (flat, same for every city/country)</label>
                    <form method="post" action="/admin/land/commercecosts">
                        <label>Festival cost ($)</label>
                        <input type="number" step="0.01" min="0" name="festivalCost" value="%.2f">
                        <label>Endorsement cost ($)</label>
                        <input type="number" step="0.01" min="0" name="endorsementCost" value="%.2f">
                        <label>Audit cost ($)</label>
                        <input type="number" step="0.01" min="0" name="auditCost" value="%.2f">
                        <label>Audit fine (%% of last-7-days revenue, if caught)</label>
                        <input type="number" step="0.1" min="0" name="auditFinePercent" value="%.1f">
                        <label>Public square cost ($)</label>
                        <input type="number" step="0.01" min="0" name="publicSquareCost" value="%.2f">
                        <label>Public square max radius (blocks)</label>
                        <input type="number" min="1" name="publicSquareMaxRadius" value="%d">
                        <input type="submit" value="Save commerce costs">
                    </form>
                </div>
                """.formatted(data.adminCountryRatePerBlockCents() / 100.0, rows,
                data.festivalCostCents() / 100.0, data.endorsementCostCents() / 100.0, data.auditCostCents() / 100.0,
                data.auditFinePercent(), data.publicSquareCostCents() / 100.0, data.publicSquareMaxRadius());
    }

    private String policyOptionsHtml(MissedPaymentPolicy current)
    {
        StringBuilder sb = new StringBuilder();
        for (MissedPaymentPolicy p : MissedPaymentPolicy.values())
            sb.append("<option value=\"%s\"%s>%s</option>".formatted(p.name(), p == current ? " selected" : "", p.name()));
        return sb.toString();
    }

    // ---------- governments ----------

    private String governmentsHtml(TerritoryData data, UUID playerId)
    {
        StringBuilder sb = new StringBuilder();
        for (City city : data.getAllCities())
            if (city.founderId.equals(playerId) || city.officials.containsKey(playerId))
                sb.append(governmentCardHtml(data, city, ClaimType.CITY, playerId));
        for (Country country : data.getAllCountries())
            if (country.founderId.equals(playerId) || country.officials.containsKey(playerId))
                sb.append(governmentCardHtml(data, country, ClaimType.COUNTRY, playerId));
        return sb.length() == 0
                ? "<div class=\"card\"><p class=\"sub\">You aren't a founder or official of any city or country. Name a city/country shovel and select corners to found one.</p></div>"
                : sb.toString();
    }

    private String governmentCardHtml(TerritoryData data, Government gov, ClaimType type, UUID playerId)
    {
        boolean isFounder = gov.founderId.equals(playerId);
        long blocks = gov.region.blockCount();
        String dim = gov.region.dimension.location().toString();

        StringBuilder body = new StringBuilder();
        body.append("<p class=\"sub\">%d blocks in %s. Treasury: %s. Status: %s%s</p>".formatted(
                blocks, dim, Money.format(gov.balanceCents), gov.billing.status, isFounder ? " &middot; you are the founder" : ""));

        if (gov.billing.status != BillingStatus.ACTIVE && gov.hasPermission(playerId, GovPermission.MANAGE_RATES))
        {
            int due = type == ClaimType.CITY
                    ? data.cityRentToCountryCents(gov.region, data.cityCountry(gov.region))
                    : data.countryRentToAdminCents((Country) gov);
            body.append("""
                    <form method="post" action="/user/land/gov/reactivate">
                        <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                        <input type="submit" value="Pay %s and reactivate">
                    </form>
                    """.formatted(type.name(), gov.id, Money.format(due)));
        }

        if (gov.hasPermission(playerId, GovPermission.MANAGE_RATES))
        {
            int currentRate = type == ClaimType.CITY ? ((City) gov).landClaimRatePerBlockCents : ((Country) gov).cityRatePerBlockCents;
            body.append("""
                    <label>Rate charged to %s ($/block/week)</label>
                    <form method="post" action="/user/land/gov/rate" class="row-form">
                        <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                        <input type="number" step="0.01" min="0" name="amount" value="%.2f">
                        <button type="submit" class="btn">Save</button>
                    </form>
                    <label>Victim share of report fines (%%)</label>
                    <form method="post" action="/user/land/gov/victimshare" class="row-form">
                        <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                        <input type="number" min="0" max="100" name="percent" value="%d">
                        <button type="submit" class="btn">Save</button>
                    </form>
                    <label>Sales tax on businesses with a Sell Barrel here (%% of every transaction)</label>
                    <form method="post" action="/user/land/gov/salestax" class="row-form">
                        <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                        <input type="number" min="0" max="100" name="percent" value="%d">
                        <button type="submit" class="btn">Save</button>
                    </form>
                    """.formatted(type == ClaimType.CITY ? "land claims here" : "cities here", type.name(), gov.id, currentRate / 100.0,
                    type.name(), gov.id, gov.victimSharePercent, type.name(), gov.id, gov.salesTaxRatePercent));
        }

        if (gov.hasPermission(playerId, GovPermission.MANAGE_COMMERCE))
            body.append(commerceCardHtml(data, gov, type));

        if (gov.hasPermission(playerId, GovPermission.MANAGE_OFFICIALS))
            body.append(officialsHtml(gov, type));

        if (gov.hasPermission(playerId, GovPermission.MANAGE_JAILS))
            body.append(jailCellsHtml(gov, type));

        if (type == ClaimType.CITY && gov.hasPermission(playerId, GovPermission.MANAGE_LAND_RULES))
            body.append(officialAccessHtml((City) gov));

        if (type == ClaimType.COUNTRY)
        {
            Country country = (Country) gov;
            if (gov.hasPermission(playerId, GovPermission.MANAGE_LAWS))
                body.append(lawsHtml(country));
            if (gov.hasPermission(playerId, GovPermission.MANAGE_PERMITS))
                body.append(permitsHtml(country));
        }

        return """
                <div class="card">
                    <h2>&#127970; %s <span class="hint" style="margin:0">(%s)</span></h2>
                    %s
                </div>
                """.formatted(Html.escape(gov.name), type.name(), body);
    }

    // Every government-vs-business commerce lever: foreign-tax surcharge, licensing, sanctions/alliances
    // (unilateral diplomacy - see Government), subsidies, festivals, endorsements, and audits. Gated entirely
    // behind MANAGE_COMMERCE, kept separate from the base-rate/sales-tax section (MANAGE_RATES) above.
    private String commerceCardHtml(TerritoryData data, Government gov, ClaimType type)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <label>Foreign business tax surcharge (%%, on top of sales tax for businesses not owned by a resident here)</label>
                <form method="post" action="/user/land/gov/foreigntax" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="number" min="0" max="100" name="percent" value="%d">
                    <button type="submit" class="btn">Save</button>
                </form>
                <label>License fee to operate here ($, one-time - 0 means no license required)</label>
                <form method="post" action="/user/land/gov/license" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="number" step="0.01" min="0" name="amount" value="%.2f">
                    <button type="submit" class="btn">Save</button>
                </form>
                """.formatted(type.name(), gov.id, gov.foreignTaxSurchargePercent,
                type.name(), gov.id, gov.licenseFeeCents / 100.0));

        sb.append(diplomacyHtml(data, gov, type, "Sanctioned", "sanction", gov.sanctionedGovernmentIds));
        sb.append(diplomacyHtml(data, gov, type, "Allied", "ally", gov.alliedGovernmentIds));

        sb.append("""
                <label>Subsidize a business (pays straight out of the treasury)</label>
                <form method="post" action="/user/land/gov/subsidize" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="owner" placeholder="Business owner's player name" required style="flex:1">
                    <input type="number" step="0.01" min="0" name="amount" placeholder="Amount ($)">
                    <button type="submit" class="btn">Send</button>
                </form>
                <label>Run a festival - boosts customer traffic for every business taxed here (%s)</label>
                <form method="post" action="/user/land/gov/festival" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <button type="submit" class="btn">Run festival</button>
                </form>
                <label>Publicly endorse a business (%s, permanent badge + a free advert)</label>
                <form method="post" action="/user/land/gov/endorse" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="owner" placeholder="Business owner's player name" required style="flex:1">
                    <button type="submit" class="btn">Endorse</button>
                </form>
                <label>Audit a business for dodging tax at the border (%s, risk even if it finds nothing)</label>
                <form method="post" action="/user/land/gov/audit" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="owner" placeholder="Business owner's player name" required style="flex:1">
                    <button type="submit" class="btn">Audit</button>
                </form>
                <label>State-affiliate a business (still player-owned; takes a profit-share cut on every sale straight to this treasury - 0%% clears it)</label>
                <form method="post" action="/user/land/gov/affiliate" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="owner" placeholder="Business owner's player name" required style="flex:1">
                    <input type="number" min="0" max="100" name="percent" placeholder="Profit share %%" style="width:110px">
                    <button type="submit" class="btn">Set</button>
                </form>
                """.formatted(type.name(), gov.id, Money.format(data.festivalCostCents()), type.name(), gov.id,
                Money.format(data.endorsementCostCents()), type.name(), gov.id,
                Money.format(data.auditCostCents()), type.name(), gov.id,
                type.name(), gov.id));

        return sb.toString();
    }

    // Shared renderer for the sanction/ally lists: a table of currently-listed governments each with a Remove
    // button, plus an add-by-name form. Both are unilateral (see Government) so no accept/reject flow exists.
    private String diplomacyHtml(TerritoryData data, Government gov, ClaimType type, String label, String action, Set<UUID> ids)
    {
        StringBuilder rows = new StringBuilder();
        for (UUID id : ids)
        {
            Government target = data.getGovernmentById(id);
            String name = target != null ? target.name : id.toString().substring(0, 8);
            rows.append("""
                    <tr>
                        <td>%s</td>
                        <td>
                            <form method="post" action="/user/land/gov/%s">
                                <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                                <input type="hidden" name="target" value="%s"><input type="hidden" name="remove" value="true">
                                <button type="submit" class="btn danger">Remove</button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(Html.escape(name), action, type.name(), gov.id, Html.escape(name)));
        }
        String table = ids.isEmpty() ? "<p class=\"sub\">None.</p>"
                : "<table><thead><tr><th>%s</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(label, rows);

        return """
                <label>%s governments</label>
                %s
                <form method="post" action="/user/land/gov/%s" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="target" placeholder="City or country name" required style="flex:1">
                    <button type="submit" class="btn">Add</button>
                </form>
                """.formatted(label, table, action, type.name(), gov.id);
    }

    private String officialsHtml(Government gov, ClaimType type)
    {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<UUID, Set<GovPermission>> entry : gov.officials.entrySet())
        {
            String name = playerName(entry.getKey());
            for (GovPermission perm : entry.getValue())
            {
                rows.append("""
                        <tr>
                            <td>%s</td><td>%s</td>
                            <td>
                                <form method="post" action="/user/land/gov/official/revoke">
                                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                                    <input type="hidden" name="player" value="%s"><input type="hidden" name="permission" value="%s">
                                    <button type="submit" class="btn danger">Revoke</button>
                                </form>
                            </td>
                        </tr>
                        """.formatted(Html.escape(name), perm.name(), type.name(), gov.id, Html.escape(name), perm.name()));
            }
        }
        String table = gov.officials.isEmpty() ? "<p class=\"sub\">No officials yet.</p>"
                : "<table><thead><tr><th>Player</th><th>Permission</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        return """
                <label>Officials</label>
                %s
                <form method="post" action="/user/land/gov/official/grant" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="player" placeholder="Player name" required style="flex:1">
                    <select name="permission">%s</select>
                    <button type="submit" class="btn">Grant</button>
                </form>
                """.formatted(table, type.name(), gov.id, govPermissionOptionsHtml());
    }

    private String govPermissionOptionsHtml()
    {
        StringBuilder sb = new StringBuilder();
        for (GovPermission p : GovPermission.values())
            sb.append("<option value=\"%s\">%s</option>".formatted(p.name(), p.name()));
        return sb.toString();
    }

    private String jailCellsHtml(Government gov, ClaimType type)
    {
        StringBuilder rows = new StringBuilder();
        for (JailCell cell : gov.jailCells)
        {
            rows.append("""
                    <tr>
                        <td>%s</td><td>%s</td><td>%d</td><td>%d</td>
                        <td>
                            <form method="post" action="/user/land/gov/jail/remove">
                                <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                                <input type="hidden" name="cell" value="%s">
                                <button type="submit" class="btn danger">Remove</button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(Html.escape(cell.name), cell.dimension.location().toString(), cell.radius, cell.maxCapacity,
                    type.name(), gov.id, Html.escape(cell.name)));
        }
        String table = gov.jailCells.isEmpty() ? "<p class=\"sub\">No jail cells yet.</p>"
                : "<table><thead><tr><th>Name</th><th>Dimension</th><th>Radius</th><th>Capacity</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        return """
                <label>Jail cells</label>
                %s
                <form method="post" action="/user/land/gov/jail/add" class="row-form">
                    <input type="hidden" name="govType" value="%s"><input type="hidden" name="govId" value="%s">
                    <input type="text" name="cell" placeholder="Cell name" required style="flex:1">
                    <input type="number" min="1" name="radius" placeholder="Radius" required style="flex:1">
                    <input type="number" min="1" name="maxCapacity" placeholder="Capacity" required style="flex:1">
                    <button type="submit" class="btn">Add at my location</button>
                </form>
                <p class="hint">Adds the cell centered on where you're currently standing in-game - you must be online.</p>
                """.formatted(table, type.name(), gov.id);
    }

    private String officialAccessHtml(City city)
    {
        StringBuilder rows = new StringBuilder();
        for (ClaimPermission p : ClaimPermission.values())
        {
            boolean has = city.officialAccess.contains(p);
            rows.append("""
                    <div class="row-form">
                        <span style="flex:1;color:#c9cdd8;font-size:.88em">%s</span>
                        <form method="post" action="/user/land/gov/officialaccess" style="margin:0">
                            <input type="hidden" name="govId" value="%s">
                            <input type="hidden" name="permission" value="%s">
                            <input type="hidden" name="access" value="%s">
                            <button type="submit" class="btn %s">%s</button>
                        </form>
                    </div>
                    """.formatted(p.name(), city.id, p.name(), !has, has ? "danger" : "",
                    has ? "Revoke automatic access" : "Grant automatic access"));
        }
        return """
                <label>Officials' automatic access on land claims in this city</label>
                <p class="hint">Disclosed to anyone claiming land here as part of the terms they agree to.</p>
                %s
                """.formatted(rows);
    }

    private String lawsHtml(Country country)
    {
        StringBuilder sb = new StringBuilder("<label>Laws</label>");
        for (CountryLaw law : CountryLaw.values())
        {
            LawConfig config = country.laws.get(law);
            sb.append("""
                    <div class="card" style="margin:10px 0;padding:14px">
                        <div class="row-form">
                            <span style="flex:1;font-weight:600">%s</span>
                            <form method="post" action="/user/land/gov/law/enable" style="margin:0">
                                <input type="hidden" name="govId" value="%s"><input type="hidden" name="law" value="%s">
                                <input type="hidden" name="enabled" value="%s">
                                <button type="submit" class="btn %s">%s</button>
                            </form>
                        </div>
                        <form method="post" action="/user/land/gov/law/configure" class="row-form">
                            <input type="hidden" name="govId" value="%s"><input type="hidden" name="law" value="%s">
                            <input type="number" step="0.01" min="0" name="fine" value="%.2f" placeholder="Fine $" style="flex:1">
                            <input type="text" name="cell" value="%s" placeholder="Jail cell (or none)" style="flex:1">
                            <input type="number" min="0" name="minutes" value="%d" placeholder="Minutes" style="flex:1">
                            <input type="number" step="0.01" min="0" name="quota" value="%.2f" placeholder="Mining quota $" style="flex:1">
                            <button type="submit" class="btn">Save</button>
                        </form>
                    </div>
                    """.formatted(law.name(), country.id, law.name(), !config.enabled, config.enabled ? "danger" : "",
                    config.enabled ? "Enabled (click to disable)" : "Disabled (click to enable)",
                    country.id, law.name(), config.fineCents / 100.0,
                    config.jailCellId != null ? Html.escape(cellNameOf(country, config.jailCellId)) : "none",
                    config.jailMinutes, config.quotaCents / 100.0));
        }

        sb.append("""
                <label>Max Y laws apply to</label>
                <form method="post" action="/user/land/gov/law/maxy" class="row-form">
                    <input type="hidden" name="govId" value="%s">
                    <input type="number" name="y" value="%d">
                    <button type="submit" class="btn">Save</button>
                </form>
                """.formatted(country.id, country.lawMaxY));

        sb.append(lawZonesHtml(country));
        return sb.toString();
    }

    private String cellNameOf(Government gov, UUID cellId)
    {
        JailCell cell = gov.findJailCell(cellId);
        return cell != null ? cell.name : "unknown";
    }

    private String lawZonesHtml(Country country)
    {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < country.lawYOverrideZones.size(); i++)
        {
            Rect zone = country.lawYOverrideZones.get(i);
            rows.append("""
                    <tr>
                        <td>%d</td><td>(%d,%d) to (%d,%d)</td>
                        <td>
                            <form method="post" action="/user/land/gov/law/zone/remove">
                                <input type="hidden" name="govId" value="%s"><input type="hidden" name="index" value="%d">
                                <button type="submit" class="btn danger">Remove</button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(i, zone.minX(), zone.minZ(), zone.maxX(), zone.maxZ(), country.id, i));
        }
        String table = country.lawYOverrideZones.isEmpty() ? "<p class=\"sub\">No override zones.</p>"
                : "<table><thead><tr><th>#</th><th>X/Z bounds</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        return """
                <label>Y-cap override zones (laws always apply here regardless of the Y cap above)</label>
                %s
                <form method="post" action="/user/land/gov/law/zone/add" class="row-form">
                    <input type="hidden" name="govId" value="%s">
                    <input type="number" name="x1" placeholder="X1" style="flex:1">
                    <input type="number" name="z1" placeholder="Z1" style="flex:1">
                    <input type="number" name="x2" placeholder="X2" style="flex:1">
                    <input type="number" name="z2" placeholder="Z2" style="flex:1">
                    <button type="submit" class="btn">Add</button>
                </form>
                """.formatted(table, country.id);
    }

    private String permitsHtml(Country country)
    {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<UUID, Set<CountryLaw>> entry : country.permits.entrySet())
        {
            String name = playerName(entry.getKey());
            for (CountryLaw law : entry.getValue())
            {
                rows.append("""
                        <tr>
                            <td>%s</td><td>%s</td>
                            <td>
                                <form method="post" action="/user/land/gov/permit/revoke">
                                    <input type="hidden" name="govId" value="%s">
                                    <input type="hidden" name="player" value="%s"><input type="hidden" name="law" value="%s">
                                    <button type="submit" class="btn danger">Revoke</button>
                                </form>
                            </td>
                        </tr>
                        """.formatted(Html.escape(name), law.name(), country.id, Html.escape(name), law.name()));
            }
        }
        String table = country.permits.isEmpty() ? "<p class=\"sub\">No permits granted.</p>"
                : "<table><thead><tr><th>Player</th><th>Exempt law</th><th></th></tr></thead><tbody>%s</tbody></table>".formatted(rows);

        return """
                <label>Permits (exemptions from specific laws)</label>
                %s
                <form method="post" action="/user/land/gov/permit/grant" class="row-form">
                    <input type="hidden" name="govId" value="%s">
                    <input type="text" name="player" placeholder="Player name" required style="flex:1">
                    <select name="law">%s</select>
                    <button type="submit" class="btn">Grant</button>
                </form>
                """.formatted(table, country.id, countryLawOptionsHtml());
    }

    private String countryLawOptionsHtml()
    {
        StringBuilder sb = new StringBuilder();
        for (CountryLaw law : CountryLaw.values())
            sb.append("<option value=\"%s\">%s</option>".formatted(law.name(), law.name()));
        return sb.toString();
    }

    // ---------- reports ----------

    private String reportsInboxHtml(TerritoryData data, UUID playerId)
    {
        List<Report> pending = new ArrayList<>();
        for (City city : data.getAllCities())
            if (city.hasPermission(playerId, GovPermission.MANAGE_JAILS))
                pending.addAll(data.getPendingReportsFor(city));
        for (Country country : data.getAllCountries())
            if (country.hasPermission(playerId, GovPermission.MANAGE_JAILS))
                pending.addAll(data.getPendingReportsFor(country));

        if (pending.isEmpty())
            return "";

        StringBuilder rows = new StringBuilder();
        for (Report report : pending)
        {
            LandClaim claim = data.landClaimOf(report);
            String claimDesc = claim != null ? claim.region.dimension.location() + ", " + claim.region.blockCount() + " blocks" : "unknown claim";
            rows.append("""
                    <div class="card" style="margin:10px 0;padding:14px">
                        <p class="sub"><b>%s</b> reported <b>%s</b> for %s on a claim (%s).</p>
                        <p class="sub">Reason: %s</p>
                        <form method="post" action="/user/land/report/resolve" class="row-form">
                            <input type="hidden" name="reportId" value="%s">
                            <input type="number" step="0.01" min="0" name="fine" placeholder="Fine $" style="flex:1">
                            <input type="text" name="cell" placeholder="Jail cell (or none)" style="flex:1">
                            <input type="number" min="0" name="minutes" placeholder="Minutes" style="flex:1">
                            <input type="number" step="0.01" min="0" name="quota" placeholder="Mining quota $" style="flex:1">
                            <button type="submit" class="btn">Resolve</button>
                        </form>
                        <form method="post" action="/user/land/report/deny" style="margin-top:8px">
                            <input type="hidden" name="reportId" value="%s">
                            <button type="submit" class="btn ghost">Deny</button>
                        </form>
                    </div>
                    """.formatted(Html.escape(playerName(report.reporterId)), Html.escape(playerName(report.offenderId)),
                    report.permission.name(), Html.escape(claimDesc), Html.escape(report.reason), report.id, report.id));
        }

        return """
                <div class="card">
                    <h2>&#128220; Pending reports <span class="count-pill">%d</span></h2>
                    %s
                </div>
                """.formatted(pending.size(), rows);
    }

    private String reportFormHtml(TerritoryData data, UUID playerId)
    {
        List<LandClaim> claims = data.getLandClaimsOwnedBy(playerId);
        if (claims.isEmpty())
            return "";

        StringBuilder options = new StringBuilder();
        for (LandClaim claim : claims)
            options.append("<option value=\"%s\">%s (%d blocks)</option>".formatted(
                    claim.id, claim.region.dimension.location(), claim.region.blockCount()));

        return """
                <div class="card">
                    <h2>&#128681; File a report</h2>
                    <p class="sub">If someone violated a Reportable rule on your land, file it with a city or country whose territory overlaps the claim.</p>
                    <form method="post" action="/user/land/report/submit">
                        <label>Claim</label>
                        <select name="claimId">%s</select>
                        <label>Government type</label>
                        <select name="govType"><option value="CITY">City</option><option value="COUNTRY">Country</option></select>
                        <label>Government name</label>
                        <input type="text" name="govName" required>
                        <label>Offending player</label>
                        <input type="text" name="offender" required>
                        <label>Permission violated</label>
                        <select name="permission">%s</select>
                        <label>Reason</label>
                        <input type="text" name="reason" required>
                        <input type="submit" value="File report">
                    </form>
                </div>
                """.formatted(options, permissionOptionsHtml());
    }

    // ---------- shared helpers ----------

    private String playerName(UUID id)
    {
        if (server.getProfileCache() == null)
            return id.toString().substring(0, 8);
        return server.getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    private GameProfile resolveTarget(String username)
    {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty())
            throw new WebActionException("Enter a player name.");
        Optional<GameProfile> opt = server.getProfileCache() != null ? server.getProfileCache().get(trimmed) : Optional.empty();
        if (opt.isEmpty())
            throw new WebActionException("Unknown player: " + trimmed);
        return opt.get();
    }

    // Resolves a business by its owner's player name - the commerce actions (subsidize/endorse/audit) target a
    // business this way rather than by id, matching how officials already grant permissions by player name.
    private Business requireBusiness(String ownerName)
    {
        GameProfile profile = resolveTarget(ownerName);
        Business business = BusinessData.get(server).getByOwner(profile.getId());
        if (business == null)
            throw new WebActionException(profile.getName() + " doesn't have a business.");
        return business;
    }

    private UUID parseUuidOrThrow(String raw, String label)
    {
        try
        {
            return UUID.fromString(raw);
        }
        catch (Exception e)
        {
            throw new WebActionException("Invalid " + label + ".");
        }
    }

    private int parseIntOrThrow(String raw, String label)
    {
        try
        {
            return Integer.parseInt(raw.trim());
        }
        catch (Exception e)
        {
            throw new WebActionException("Invalid " + label + ".");
        }
    }

    private int parseNonNegativeInt(String raw)
    {
        int value = parseIntOrThrow(raw, "number");
        if (value < 0)
            throw new WebActionException("Value must not be negative.");
        return value;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label)
    {
        try
        {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (Exception e)
        {
            throw new WebActionException("Invalid " + label + ".");
        }
    }

    private LandClaim findOwnClaim(TerritoryData data, UUID playerId, String claimIdStr)
    {
        UUID id = parseUuidOrThrow(claimIdStr, "claim id");
        LandClaim claim = data.getLandClaim(id);
        if (claim == null || !claim.ownerId.equals(playerId))
            throw new WebActionException("You don't own that claim.");
        return claim;
    }

    private Government findGov(TerritoryData data, String type, String idStr)
    {
        UUID id = parseUuidOrThrow(idStr, "government id");
        Government gov = "CITY".equalsIgnoreCase(type) ? data.getCity(id) : "COUNTRY".equalsIgnoreCase(type) ? data.getCountry(id) : null;
        if (gov == null)
            throw new WebActionException("Unknown government.");
        return gov;
    }

    private Government requireGov(TerritoryData data, String type, String idStr, UUID playerId, GovPermission permission)
    {
        Government gov = findGov(data, type, idStr);
        if (!gov.hasPermission(playerId, permission))
            throw new WebActionException("You don't have permission to do that in " + gov.name + ".");
        return gov;
    }

    // Looks a government up by name regardless of whether it's a city or a country - used to target the
    // *other* side of a sanction/alliance, since that government could be either kind.
    private Government findGovernmentByName(TerritoryData data, String name)
    {
        Government gov = data.findCityByName(name);
        if (gov == null)
            gov = data.findCountryByName(name);
        if (gov == null)
            throw new WebActionException("Unknown city or country \"" + name + "\".");
        return gov;
    }

    private City requireCity(TerritoryData data, String idStr, UUID playerId, GovPermission permission)
    {
        UUID id = parseUuidOrThrow(idStr, "government id");
        City city = data.getCity(id);
        if (city == null)
            throw new WebActionException("Unknown city.");
        if (!city.hasPermission(playerId, permission))
            throw new WebActionException("You don't have permission to do that in " + city.name + ".");
        return city;
    }

    private Country requireCountry(TerritoryData data, String idStr, UUID playerId, GovPermission permission)
    {
        UUID id = parseUuidOrThrow(idStr, "government id");
        Country country = data.getCountry(id);
        if (country == null)
            throw new WebActionException("Unknown country.");
        if (!country.hasPermission(playerId, permission))
            throw new WebActionException("You don't have permission to do that in " + country.name + ".");
        return country;
    }

    private Report findPendingReport(TerritoryData data, String reportIdStr)
    {
        UUID id = parseUuidOrThrow(reportIdStr, "report id");
        Report report = data.getReport(id);
        if (report == null || report.status != ReportStatus.PENDING)
            throw new WebActionException("Unknown or already-resolved report.");
        return report;
    }

    // ---------- claim actions ----------

    public void handleBuyShovel(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                ClaimType type = parseEnum(ClaimType.class, form.get("type"), "shovel type");
                String tierName = form.getOrDefault("tier", "").trim();
                TerritoryData data = TerritoryData.get(server);
                ShovelTier tier = data.findTierByName(type, tierName);
                if (tier == null)
                    throw new WebActionException("Unknown tier.");

                ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
                if (player == null)
                    throw new WebActionException("You must be online in-game to buy a claim shovel.");

                MoneyData moneyData = MoneyData.get(server);
                int balance = moneyData.getMoney(session.playerId);
                if (balance < tier.priceCents)
                    throw new WebActionException("You need " + Money.format(tier.priceCents) + " but only have " + Money.format(balance) + ".");

                moneyData.addMoney(server, session.playerId, -tier.priceCents);
                ItemStack stack = new ItemStack(shovelItemFor(type));
                ClaimShovelItem.initialize(stack, tier);
                InventoryUtil.giveOrDrop(player, stack);
                return "Bought a " + tier.name + " " + type.name().toLowerCase(Locale.ROOT) + " claim shovel.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetRule(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                LandClaim claim = findOwnClaim(data, session.playerId, form.get("claimId"));
                ClaimPermission permission = parseEnum(ClaimPermission.class, form.get("permission"), "permission");
                PermissionMode mode = parseEnum(PermissionMode.class, form.get("mode"), "mode");
                data.setRule(claim, permission, mode);
                return permission + " set to " + mode + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleTrust(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                LandClaim claim = findOwnClaim(data, session.playerId, form.get("claimId"));
                GameProfile target = resolveTarget(form.get("player"));
                ClaimPermission permission = parseEnum(ClaimPermission.class, form.get("permission"), "permission");
                data.trustPlayer(claim, target.getId(), permission);
                return "Trusted " + target.getName() + " with " + permission + " on this claim.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleUntrust(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                LandClaim claim = findOwnClaim(data, session.playerId, form.get("claimId"));
                GameProfile target = resolveTarget(form.get("player"));
                boolean removed = data.untrustPlayer(claim, target.getId());
                return removed ? "Removed " + target.getName() + "'s trust." : target.getName() + " wasn't trusted.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleReactivateClaim(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                LandClaim claim = findOwnClaim(data, session.playerId, form.get("claimId"));
                if (claim.billing.status == BillingStatus.ACTIVE)
                    throw new WebActionException("This claim isn't suspended.");
                if (!data.reactivateLandClaim(server, claim))
                    throw new WebActionException("You can't afford the rent due to reactivate this claim.");
                return "Claim reactivated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    // ---------- admin actions: tiers & land/government settings ----------

    public void handleAdminAddTier(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                ClaimType type = parseEnum(ClaimType.class, form.get("type"), "claim type");
                String name = form.getOrDefault("name", "").trim();
                if (name.isEmpty())
                    throw new WebActionException("Enter a tier name.");
                if (data.findTierByName(type, name) != null)
                    throw new WebActionException("A " + type.name().toLowerCase(Locale.ROOT) + " tier named \"" + name + "\" already exists.");
                int priceCents = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("price")));
                int maxCharges = WebSupport.parsePositiveInt(form.get("maxCharges"));
                int maxArea = WebSupport.parsePositiveInt(form.get("maxArea"));
                data.addTier(type, name, priceCents, maxCharges, maxArea);
                return "Added " + type.name().toLowerCase(Locale.ROOT) + " tier \"" + name + "\".";
            });
            WebSupport.redirectWithMessage(exchange, "/admin", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    public void handleAdminRemoveTier(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                ClaimType type = parseEnum(ClaimType.class, form.get("type"), "claim type");
                String name = form.getOrDefault("name", "").trim();
                ShovelTier tier = data.findTierByName(type, name);
                if (tier == null)
                    throw new WebActionException("Unknown tier \"" + name + "\".");
                data.removeTier(type, tier.id);
                return "Removed tier \"" + name + "\".";
            });
            WebSupport.redirectWithMessage(exchange, "/admin", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    public void handleAdminSetCountryRate(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                int cents = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("amount")));
                TerritoryData.get(server).setAdminCountryRatePerBlockCents(cents);
                return "Country -> admin rate set to " + Money.format(cents) + "/block/week.";
            });
            WebSupport.redirectWithMessage(exchange, "/admin", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    public void handleAdminSetCommerceCosts(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            int festivalCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("festivalCost")));
            int endorsementCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("endorsementCost")));
            int auditCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("auditCost")));
            double auditFinePercent = WebSupport.parseNonNegativeDouble(form.get("auditFinePercent"));
            int publicSquareCost = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("publicSquareCost")));
            int publicSquareMaxRadius = parseIntOrThrow(form.getOrDefault("publicSquareMaxRadius", "48"), "public square max radius");

            MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                data.setFestivalCostCents(festivalCost);
                data.setEndorsementCostCents(endorsementCost);
                data.setAuditCostCents(auditCost);
                data.setAuditFinePercent(auditFinePercent);
                data.setPublicSquareCostCents(publicSquareCost);
                data.setPublicSquareMaxRadius(publicSquareMaxRadius);
                return null;
            });
            WebSupport.redirectWithMessage(exchange, "/admin", "Commerce action costs updated.", true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    public void handleAdminSetPolicy(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                ClaimType type = parseEnum(ClaimType.class, form.get("type"), "claim type");
                MissedPaymentPolicy policy = parseEnum(MissedPaymentPolicy.class, form.get("policy"), "policy");
                int graceDays = parseNonNegativeInt(form.getOrDefault("graceDays", "0"));
                data.setPolicy(type, policy);
                data.setGraceDays(type, graceDays);
                return type.name().toLowerCase(Locale.ROOT) + " policy set to " + policy + " (" + graceDays
                        + " grace day" + (graceDays == 1 ? "" : "s") + ").";
            });
            WebSupport.redirectWithMessage(exchange, "/admin", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    // ---------- government actions ----------

    public void handleSetRate(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_RATES);
                int cents = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("amount")));
                if (gov instanceof City city)
                    data.setCityRate(city, cents);
                else
                    data.setCountryRate((Country) gov, cents);
                return gov.name + "'s rate set to " + Money.format(cents) + "/block/week.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetVictimShare(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_RATES);
                int percent = parseNonNegativeInt(form.get("percent"));
                data.setVictimShare(gov, percent);
                return "Victim share updated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetSalesTax(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_RATES);
                int percent = parseNonNegativeInt(form.get("percent"));
                data.setSalesTaxRate(gov, percent);
                return "Sales tax updated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetForeignTax(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                int percent = parseNonNegativeInt(form.get("percent"));
                data.setForeignTaxSurcharge(gov, percent);
                return "Foreign tax surcharge updated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetLicenseFee(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                int cents = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("amount")));
                data.setLicenseFee(gov, cents);
                return "License fee updated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSanction(HttpExchange exchange) throws IOException
    {
        handleDiplomacy(exchange, true);
    }

    public void handleAlly(HttpExchange exchange) throws IOException
    {
        handleDiplomacy(exchange, false);
    }

    private void handleDiplomacy(HttpExchange exchange, boolean sanction) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                Government target = findGovernmentByName(data, form.get("target"));
                if (target == gov)
                    throw new WebActionException("Can't target your own government.");
                boolean remove = "true".equals(form.get("remove"));
                if (sanction)
                    data.setSanctioned(gov, target, !remove);
                else
                    data.setAllied(gov, target, !remove);
                return (remove ? "Removed " : "Added ") + target.name + (sanction ? " as sanctioned." : " as allied.");
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSubsidize(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                Business business = requireBusiness(form.get("owner"));
                int cents = Money.toCents(WebSupport.parseNonNegativeDouble(form.get("amount")));
                if (!data.grantSubsidy(server, gov, business, cents))
                    throw new WebActionException("The treasury doesn't have that much.");
                return "Sent " + Money.format(cents) + " to " + business.name + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleFestival(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                if (!data.runFestival(server, gov, BusinessData.get(server).advertDurationHours()))
                    throw new WebActionException("The treasury doesn't have enough to run a festival.");
                return "Festival running - every business taxed here just got a visibility boost.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleEndorse(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                Business business = requireBusiness(form.get("owner"));
                if (!data.endorseBusiness(server, gov, business))
                    throw new WebActionException("The treasury doesn't have enough to endorse.");
                return gov.name + " endorsed " + business.name + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleAudit(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                Business business = requireBusiness(form.get("owner"));
                return data.auditBusiness(server, gov, business);
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetStateAffiliation(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_COMMERCE);
                Business business = requireBusiness(form.get("owner"));
                int percent = parseNonNegativeInt(form.getOrDefault("percent", "0"));
                if (percent > 100)
                    throw new WebActionException("Percent can't exceed 100.");
                data.setStateAffiliation(server, business, gov, percent);
                return percent > 0 ? business.name + " is now affiliated with " + gov.name + " (" + percent + "% profit share)."
                        : "Cleared " + business.name + "'s state affiliation.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleReactivateGov(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_RATES);
                if (gov.billing.status == BillingStatus.ACTIVE)
                    throw new WebActionException(gov.name + " isn't suspended.");
                boolean success = gov instanceof City city ? data.reactivateCity(city) : data.reactivateCountry((Country) gov);
                if (!success)
                    throw new WebActionException(gov.name + " can't afford the rent due to reactivate.");
                return gov.name + " reactivated.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleGrantOfficial(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_OFFICIALS);
                GameProfile target = resolveTarget(form.get("player"));
                GovPermission permission = parseEnum(GovPermission.class, form.get("permission"), "permission");
                data.grantOfficial(gov, target.getId(), permission);
                return "Granted " + target.getName() + " " + permission + " in " + gov.name + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleRevokeOfficial(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_OFFICIALS);
                GameProfile target = resolveTarget(form.get("player"));
                GovPermission permission = parseEnum(GovPermission.class, form.get("permission"), "permission");
                boolean removed = data.revokeOfficial(gov, target.getId(), permission);
                return removed ? "Revoked " + permission + " from " + target.getName() + "." : target.getName() + " didn't have that permission.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleRemoveJailCell(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_JAILS);
                String cellName = form.getOrDefault("cell", "").trim();
                JailCell cell = gov.findJailCellByName(cellName);
                if (cell == null)
                    throw new WebActionException("Unknown jail cell \"" + cellName + "\".");
                data.removeJailCell(gov, cell.id);
                return "Removed jail cell \"" + cellName + "\".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleAddJailCell(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Government gov = requireGov(data, form.get("govType"), form.get("govId"), session.playerId, GovPermission.MANAGE_JAILS);
                String cellName = form.getOrDefault("cell", "").trim();
                if (cellName.isEmpty())
                    throw new WebActionException("Enter a cell name.");
                if (gov.findJailCellByName(cellName) != null)
                    throw new WebActionException("A cell named \"" + cellName + "\" already exists.");
                int radius = WebSupport.parsePositiveInt(form.get("radius"));
                int maxCapacity = WebSupport.parsePositiveInt(form.get("maxCapacity"));

                ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
                if (player == null)
                    throw new WebActionException("You must be online in-game, standing where you want the cell, to add one.");

                data.addJailCell(gov, cellName, player.level().dimension(), player.blockPosition(), radius, maxCapacity);
                return "Added jail cell \"" + cellName + "\" at your current location.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetOfficialAccess(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                City city = requireCity(data, form.get("govId"), session.playerId, GovPermission.MANAGE_LAND_RULES);
                ClaimPermission permission = parseEnum(ClaimPermission.class, form.get("permission"), "permission");
                boolean access = Boolean.parseBoolean(form.get("access"));
                data.setCityOfficialAccess(city, permission, access);
                return city.name + " officials " + (access ? "now have " : "no longer have ") + permission + " on land claims here.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetLawEnabled(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_LAWS);
                CountryLaw law = parseEnum(CountryLaw.class, form.get("law"), "law");
                boolean enabled = Boolean.parseBoolean(form.get("enabled"));
                data.setLawEnabled(country, law, enabled);
                return law + (enabled ? " enabled." : " disabled.");
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleConfigureLaw(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_LAWS);
                CountryLaw law = parseEnum(CountryLaw.class, form.get("law"), "law");

                int fineCents = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("fine", "0")));
                String cellName = form.getOrDefault("cell", "none").trim();
                int minutes = parseNonNegativeInt(form.getOrDefault("minutes", "0"));
                int quotaCents = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("quota", "0")));

                UUID cellId = null;
                if (!cellName.isEmpty() && !cellName.equalsIgnoreCase("none"))
                {
                    JailCell cell = country.findJailCellByName(cellName);
                    if (cell == null)
                        throw new WebActionException("Unknown jail cell \"" + cellName + "\".");
                    cellId = cell.id;
                }

                data.configureLaw(country, law, fineCents, cellId, minutes, quotaCents);
                return "Configured " + law + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleSetLawMaxY(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_LAWS);
                int y = parseIntOrThrow(form.get("y"), "Y level");
                data.setLawMaxY(country, y);
                return "Laws now apply up to Y=" + y + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleAddLawZone(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_LAWS);
                Rect zone = Rect.ofCorners(parseIntOrThrow(form.get("x1"), "X1"), parseIntOrThrow(form.get("z1"), "Z1"),
                        parseIntOrThrow(form.get("x2"), "X2"), parseIntOrThrow(form.get("z2"), "Z2"));
                data.addLawYOverrideZone(country, zone);
                return "Added an override zone.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleRemoveLawZone(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_LAWS);
                int index = parseNonNegativeInt(form.get("index"));
                if (!data.removeLawYOverrideZone(country, index))
                    throw new WebActionException("No override zone at that index.");
                return "Removed override zone.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleGrantPermit(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_PERMITS);
                GameProfile target = resolveTarget(form.get("player"));
                CountryLaw law = parseEnum(CountryLaw.class, form.get("law"), "law");
                data.grantPermit(country, target.getId(), law);
                return "Granted " + target.getName() + " a permit for " + law + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleRevokePermit(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Country country = requireCountry(data, form.get("govId"), session.playerId, GovPermission.MANAGE_PERMITS);
                GameProfile target = resolveTarget(form.get("player"));
                CountryLaw law = parseEnum(CountryLaw.class, form.get("law"), "law");
                boolean removed = data.revokePermit(country, target.getId(), law);
                return removed ? "Revoked " + target.getName() + "'s permit for " + law + "." : target.getName() + " didn't have that permit.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    // ---------- report actions ----------

    public void handleSubmitReport(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                LandClaim claim = findOwnClaim(data, session.playerId, form.get("claimId"));

                ClaimType govType = parseEnum(ClaimType.class, form.get("govType"), "government type");
                if (govType == ClaimType.LAND)
                    throw new WebActionException("Pick city or country.");

                String govName = form.getOrDefault("govName", "").trim();
                Government government = govType == ClaimType.CITY ? data.findCityByName(govName) : data.findCountryByName(govName);
                if (government == null)
                    throw new WebActionException("Unknown " + govType.name().toLowerCase(Locale.ROOT) + " \"" + govName + "\".");

                boolean hasJurisdiction = govType == ClaimType.CITY
                        ? data.citiesOverlapping(claim.region).contains(government)
                        : government.equals(data.countryOverlapping(claim.region));
                if (!hasJurisdiction)
                    throw new WebActionException(government.name + " has no jurisdiction over this claim.");

                GameProfile target = resolveTarget(form.get("offender"));
                ClaimPermission permission = parseEnum(ClaimPermission.class, form.get("permission"), "permission");
                if (claim.rules.get(permission) != PermissionMode.REPORTABLE)
                    throw new WebActionException(permission + " isn't set to reportable on this claim.");

                String reason = form.getOrDefault("reason", "").trim();
                if (reason.isEmpty())
                    throw new WebActionException("Enter a reason.");

                data.submitReport(claim, session.playerId, target.getId(), govType, government, permission, reason);
                return "Report filed with " + government.name + ".";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleResolveReport(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Report report = findPendingReport(data, form.get("reportId"));
                Government government = report.governmentType == ClaimType.CITY ? data.getCity(report.governmentId) : data.getCountry(report.governmentId);
                if (government == null || !government.hasPermission(session.playerId, GovPermission.MANAGE_JAILS))
                    throw new WebActionException("You don't have permission to review this report.");

                int fineCents = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("fine", "0")));
                String cellName = form.getOrDefault("cell", "none").trim();
                int minutes = parseNonNegativeInt(form.getOrDefault("minutes", "0"));
                long jailMillis = minutes * 60L * 1000;
                int quotaCents = Money.toCents(WebSupport.parseNonNegativeDouble(form.getOrDefault("quota", "0")));

                JailCell cell = null;
                if (!cellName.isEmpty() && !cellName.equalsIgnoreCase("none"))
                {
                    cell = government.findJailCellByName(cellName);
                    if (cell == null)
                        throw new WebActionException("Unknown jail cell \"" + cellName + "\".");
                    if (data.jailCellOccupancy(cell) >= cell.maxCapacity)
                        throw new WebActionException("Cell \"" + cellName + "\" is full.");
                }

                Sentence sentence = data.resolveReport(server, government, report, fineCents, cell, jailMillis, quotaCents);
                if (sentence != null)
                {
                    ServerPlayer offender = server.getPlayerList().getPlayer(report.offenderId);
                    if (offender != null)
                        JailListener.applySentenceNow(server, offender, sentence, cell);
                }
                return "Report resolved.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    public void handleDenyReport(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;
        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String message = MainThreadExecutor.run(server, () -> {
                TerritoryData data = TerritoryData.get(server);
                Report report = findPendingReport(data, form.get("reportId"));
                Government government = report.governmentType == ClaimType.CITY ? data.getCity(report.governmentId) : data.getCountry(report.governmentId);
                if (government == null || !government.hasPermission(session.playerId, GovPermission.MANAGE_JAILS))
                    throw new WebActionException("You don't have permission to review this report.");
                data.denyReport(report);
                return "Report denied.";
            });
            WebSupport.redirectWithMessage(exchange, "/user/land", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user/land", e.getMessage(), false);
        }
    }

    // ---------- public directory ----------

    public void renderDirectory(HttpExchange exchange) throws IOException
    {
        String body = MainThreadExecutor.run(server, () -> {
            TerritoryData data = TerritoryData.get(server);
            StringBuilder countryRows = new StringBuilder();
            for (Country country : data.getAllCountries())
                countryRows.append(directoryRowHtml(country));
            StringBuilder cityRows = new StringBuilder();
            for (City city : data.getAllCities())
                cityRows.append(directoryRowHtml(city));

            return """
                    <div class="topbar"><h1>Governments</h1><div class="topbar-links"><a href="/user/land">My Land</a><a href="/user">Back to panel</a></div></div>
                    <div class="card">
                        <h2>&#127984; Countries <span class="count-pill">%d</span></h2>
                        %s
                    </div>
                    <div class="card">
                        <h2>&#127961; Cities <span class="count-pill">%d</span></h2>
                        %s
                    </div>
                    """.formatted(data.getAllCountries().size(),
                    countryRows.length() == 0 ? "<p class=\"sub\">No countries founded yet.</p>"
                            : "<table><thead><tr><th>Name</th><th>Founder</th><th>Blocks</th><th>Status</th></tr></thead><tbody>%s</tbody></table>".formatted(countryRows),
                    data.getAllCities().size(),
                    cityRows.length() == 0 ? "<p class=\"sub\">No cities founded yet.</p>"
                            : "<table><thead><tr><th>Name</th><th>Founder</th><th>Blocks</th><th>Status</th></tr></thead><tbody>%s</tbody></table>".formatted(cityRows));
        });
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Governments", body));
    }

    private String directoryRowHtml(Government gov)
    {
        return "<tr><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>".formatted(
                Html.escape(gov.name), Html.escape(playerName(gov.founderId)), gov.region.blockCount(), gov.billing.status);
    }
}
