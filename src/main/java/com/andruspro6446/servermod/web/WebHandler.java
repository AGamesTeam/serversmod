package com.andruspro6446.servermod.web;

import com.andruspro6446.servermod.business.BusinessData;
import com.andruspro6446.servermod.market.MailboxData;
import com.andruspro6446.servermod.market.MarketData;
import com.andruspro6446.servermod.market.MarketEntry;
import com.andruspro6446.servermod.money.Money;
import com.andruspro6446.servermod.money.MoneyData;
import com.andruspro6446.servermod.util.InventoryUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Routes every request for the web panel: the login flow, the admin price-management panel, and the user
// sell/send panel. Buying items is deliberately not offered here - the shared market is a business's wholesale
// source, not something regular players can buy from directly; players buy from business storefronts instead
// (see BusinessHandler). Business-related pages (/user/business, /businesses, /business/*, and the admin
// business cards) are delegated to BusinessHandler. Any logic that touches Minecraft world/player state is
// hopped onto the main server thread via MainThreadExecutor, since this handler runs on its own HTTP worker
// threads.
public class WebHandler implements HttpHandler
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;
    private final BusinessHandler business;
    private final TerritoryHandler territory;

    public WebHandler(MinecraftServer server)
    {
        this.server = server;
        this.business = new BusinessHandler(server);
        this.territory = new TerritoryHandler(server);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            route(exchange);
        }
        catch (Exception e)
        {
            LOGGER.error("Error handling web panel request", e);
            WebSupport.sendHtml(exchange, 500, Html.page("Error", "<div class=\"msg error\">Something went wrong.</div>"));
        }
        finally
        {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException
    {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/") && method.equals("GET")) { renderLoginChooser(exchange, null); return; }
        if (path.equals("/login/request") && method.equals("POST")) { handleLoginRequest(exchange); return; }
        if (path.equals("/login/verify") && method.equals("POST")) { handleLoginVerify(exchange); return; }
        if (path.equals("/logout")) { handleLogout(exchange); return; }
        if (path.equals("/admin") && method.equals("GET")) { renderAdmin(exchange); return; }
        if (path.equals("/admin/market.json") && method.equals("GET")) { renderMarketJson(exchange, Role.ADMIN); return; }
        if (path.equals("/admin/price") && method.equals("POST")) { handleAdminSetPrice(exchange); return; }
        if (path.equals("/admin/add-item") && method.equals("POST")) { handleAdminAddItem(exchange); return; }
        if (path.equals("/admin/remove-item") && method.equals("POST")) { handleAdminRemoveItem(exchange); return; }
        if (path.equals("/admin/business/settings") && method.equals("POST")) { business.handleAdminSettings(exchange); return; }
        if (path.equals("/admin/business/recipe/add") && method.equals("POST")) { business.handleAdminAddRecipe(exchange); return; }
        if (path.equals("/admin/business/recipe/remove") && method.equals("POST")) { business.handleAdminRemoveRecipe(exchange); return; }
        if (path.equals("/admin/business/review/remove") && method.equals("POST")) { business.handleAdminRemoveReview(exchange); return; }
        if (path.equals("/admin/land/tier/add") && method.equals("POST")) { territory.handleAdminAddTier(exchange); return; }
        if (path.equals("/admin/land/tier/remove") && method.equals("POST")) { territory.handleAdminRemoveTier(exchange); return; }
        if (path.equals("/admin/land/rate") && method.equals("POST")) { territory.handleAdminSetCountryRate(exchange); return; }
        if (path.equals("/admin/land/policy") && method.equals("POST")) { territory.handleAdminSetPolicy(exchange); return; }
        if (path.equals("/admin/land/commercecosts") && method.equals("POST")) { territory.handleAdminSetCommerceCosts(exchange); return; }
        if (path.equals("/user") && method.equals("GET")) { renderUser(exchange); return; }
        if (path.equals("/user/market.json") && method.equals("GET")) { renderMarketJson(exchange, Role.USER); return; }
        if (path.equals("/user/sell") && method.equals("POST")) { handleUserSell(exchange); return; }
        if (path.equals("/user/send") && method.equals("POST")) { handleUserSend(exchange); return; }
        if (path.equals("/user/business") && method.equals("GET")) { business.renderDashboard(exchange); return; }
        if (path.equals("/user/business/register") && method.equals("POST")) { business.handleRegister(exchange); return; }
        if (path.equals("/user/business/fund") && method.equals("POST")) { business.handleFund(exchange); return; }
        if (path.equals("/user/business/withdraw") && method.equals("POST")) { business.handleWithdraw(exchange); return; }
        if (path.equals("/user/business/withdraw-item") && method.equals("POST")) { business.handleWithdrawItem(exchange); return; }
        if (path.equals("/user/business/buy") && method.equals("POST")) { business.handleBuy(exchange); return; }
        if (path.equals("/user/business/sell") && method.equals("POST")) { business.handleSell(exchange); return; }
        if (path.equals("/user/business/manufacture") && method.equals("POST")) { business.handleManufacture(exchange); return; }
        if (path.equals("/user/business/reactivate") && method.equals("POST")) { business.handleReactivate(exchange); return; }
        if (path.equals("/user/business/listed") && method.equals("POST")) { business.handleSetListed(exchange); return; }
        if (path.equals("/user/business/listing") && method.equals("POST")) { business.handleSetListing(exchange); return; }
        if (path.equals("/user/business/hours") && method.equals("POST")) { business.handleSetHours(exchange); return; }
        if (path.equals("/user/business/toggle-closed") && method.equals("POST")) { business.handleToggleClosed(exchange); return; }
        if (path.equals("/user/business/npc-customers") && method.equals("POST")) { business.handleSetNpcCustomersEnabled(exchange); return; }
        if (path.equals("/user/business/employee") && method.equals("POST")) { business.handleSetEmployeeHired(exchange); return; }
        if (path.equals("/user/business/advertise") && method.equals("POST")) { business.handleRunAdvert(exchange); return; }
        if (path.equals("/user/business/upgrade") && method.equals("POST")) { business.handlePurchaseUpgrade(exchange); return; }
        if (path.equals("/user/business/license") && method.equals("POST")) { business.handleBuyLicense(exchange); return; }
        if (path.equals("/user/business/review/reply") && method.equals("POST")) { business.handleReplyToReview(exchange); return; }
        if (path.equals("/user/business/claim-sign") && method.equals("POST")) { business.handleClaimSign(exchange); return; }
        if (path.equals("/businesses") && method.equals("GET")) { business.renderDirectory(exchange); return; }
        if (path.equals("/leaderboard") && method.equals("GET")) { business.renderLeaderboard(exchange); return; }
        if (path.equals("/business/view") && method.equals("GET")) { business.renderStorefront(exchange); return; }
        if (path.equals("/business/purchase") && method.equals("POST")) { business.handlePurchase(exchange); return; }
        if (path.equals("/business/review/submit") && method.equals("POST")) { business.handleSubmitReview(exchange); return; }
        if (path.equals("/user/land") && method.equals("GET")) { territory.renderDashboard(exchange); return; }
        if (path.equals("/user/land/shovel/buy") && method.equals("POST")) { territory.handleBuyShovel(exchange); return; }
        if (path.equals("/user/land/claim/rule") && method.equals("POST")) { territory.handleSetRule(exchange); return; }
        if (path.equals("/user/land/claim/trust") && method.equals("POST")) { territory.handleTrust(exchange); return; }
        if (path.equals("/user/land/claim/untrust") && method.equals("POST")) { territory.handleUntrust(exchange); return; }
        if (path.equals("/user/land/claim/reactivate") && method.equals("POST")) { territory.handleReactivateClaim(exchange); return; }
        if (path.equals("/user/land/gov/rate") && method.equals("POST")) { territory.handleSetRate(exchange); return; }
        if (path.equals("/user/land/gov/victimshare") && method.equals("POST")) { territory.handleSetVictimShare(exchange); return; }
        if (path.equals("/user/land/gov/salestax") && method.equals("POST")) { territory.handleSetSalesTax(exchange); return; }
        if (path.equals("/user/land/gov/foreigntax") && method.equals("POST")) { territory.handleSetForeignTax(exchange); return; }
        if (path.equals("/user/land/gov/license") && method.equals("POST")) { territory.handleSetLicenseFee(exchange); return; }
        if (path.equals("/user/land/gov/sanction") && method.equals("POST")) { territory.handleSanction(exchange); return; }
        if (path.equals("/user/land/gov/ally") && method.equals("POST")) { territory.handleAlly(exchange); return; }
        if (path.equals("/user/land/gov/subsidize") && method.equals("POST")) { territory.handleSubsidize(exchange); return; }
        if (path.equals("/user/land/gov/festival") && method.equals("POST")) { territory.handleFestival(exchange); return; }
        if (path.equals("/user/land/gov/endorse") && method.equals("POST")) { territory.handleEndorse(exchange); return; }
        if (path.equals("/user/land/gov/audit") && method.equals("POST")) { territory.handleAudit(exchange); return; }
        if (path.equals("/user/land/gov/affiliate") && method.equals("POST")) { territory.handleSetStateAffiliation(exchange); return; }
        if (path.equals("/user/land/gov/reactivate") && method.equals("POST")) { territory.handleReactivateGov(exchange); return; }
        if (path.equals("/user/land/gov/official/grant") && method.equals("POST")) { territory.handleGrantOfficial(exchange); return; }
        if (path.equals("/user/land/gov/official/revoke") && method.equals("POST")) { territory.handleRevokeOfficial(exchange); return; }
        if (path.equals("/user/land/gov/jail/add") && method.equals("POST")) { territory.handleAddJailCell(exchange); return; }
        if (path.equals("/user/land/gov/jail/remove") && method.equals("POST")) { territory.handleRemoveJailCell(exchange); return; }
        if (path.equals("/user/land/gov/officialaccess") && method.equals("POST")) { territory.handleSetOfficialAccess(exchange); return; }
        if (path.equals("/user/land/gov/law/enable") && method.equals("POST")) { territory.handleSetLawEnabled(exchange); return; }
        if (path.equals("/user/land/gov/law/configure") && method.equals("POST")) { territory.handleConfigureLaw(exchange); return; }
        if (path.equals("/user/land/gov/law/maxy") && method.equals("POST")) { territory.handleSetLawMaxY(exchange); return; }
        if (path.equals("/user/land/gov/law/zone/add") && method.equals("POST")) { territory.handleAddLawZone(exchange); return; }
        if (path.equals("/user/land/gov/law/zone/remove") && method.equals("POST")) { territory.handleRemoveLawZone(exchange); return; }
        if (path.equals("/user/land/gov/permit/grant") && method.equals("POST")) { territory.handleGrantPermit(exchange); return; }
        if (path.equals("/user/land/gov/permit/revoke") && method.equals("POST")) { territory.handleRevokePermit(exchange); return; }
        if (path.equals("/user/land/report/submit") && method.equals("POST")) { territory.handleSubmitReport(exchange); return; }
        if (path.equals("/user/land/report/resolve") && method.equals("POST")) { territory.handleResolveReport(exchange); return; }
        if (path.equals("/user/land/report/deny") && method.equals("POST")) { territory.handleDenyReport(exchange); return; }
        if (path.equals("/governments") && method.equals("GET")) { territory.renderDirectory(exchange); return; }

        WebSupport.sendHtml(exchange, 404, Html.page("Not Found", "<div class=\"msg error\">Not found. <a href=\"/\">Home</a></div>"));
    }

    // ---------- login ----------

    private void renderLoginChooser(HttpExchange exchange, String error) throws IOException
    {
        String errorHtml = error != null ? "<div class=\"msg error\">" + Html.escape(error) + "</div>" : "";
        String body = """
                <div class="lock">&#128274;</div>
                <h1 style="text-align:center">ServerMod Panel</h1>
                <p class="sub" style="text-align:center">Sign in to manage prices or trade items.</p>
                %s
                <div class="card">
                    <h2>Admin login</h2>
                    <p class="sub">Sends a one-time code to every online op.</p>
                    <form method="post" action="/login/request">
                        <input type="hidden" name="role" value="admin">
                        <input type="submit" value="Send admin code">
                    </form>
                </div>
                <div class="card">
                    <h2>User login</h2>
                    <p class="sub">Sends a one-time code to your in-game chat. You must be online to receive it.</p>
                    <form method="post" action="/login/request">
                        <input type="hidden" name="role" value="user">
                        <label>Minecraft username</label>
                        <input type="text" name="username" required>
                        <input type="submit" value="Send my code">
                    </form>
                </div>
                """.formatted(errorHtml);
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Login", body));
    }

    private void handleLoginRequest(HttpExchange exchange) throws IOException
    {
        Map<String, String> form = WebSupport.parseForm(exchange);
        String role = form.getOrDefault("role", "");

        String requestId;
        if (role.equals("admin"))
        {
            requestId = MainThreadExecutor.run(server, () -> LoginManager.requestAdminLogin(server));
        }
        else
        {
            String username = form.getOrDefault("username", "").trim();
            if (username.isEmpty())
            {
                renderLoginChooser(exchange, "Enter a username.");
                return;
            }
            requestId = MainThreadExecutor.run(server, () -> LoginManager.requestUserLogin(server, username));
        }

        if (requestId == null)
        {
            renderLoginChooser(exchange, role.equals("admin")
                    ? "No ops are currently online to receive the code."
                    : "That player isn't online right now - you must be online in-game to receive a login code.");
            return;
        }

        String body = """
                <div class="lock">&#128274;</div>
                <h1 style="text-align:center">Enter your code</h1>
                <p class="sub" style="text-align:center">Check in-game chat for a 20-character code - click it there to copy it, then paste it below.</p>
                <div class="card">
                    <form method="post" action="/login/verify">
                        <input type="hidden" name="requestId" value="%s">
                        <label>Login code</label>
                        <input type="text" name="code" autocomplete="one-time-code" autofocus required maxlength="20">
                        <input type="submit" value="Log in">
                    </form>
                </div>
                <p class="sub" style="text-align:center"><a href="/">Back</a></p>
                """.formatted(Html.escape(requestId));
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Enter Code", body));
    }

    private void handleLoginVerify(HttpExchange exchange) throws IOException
    {
        Map<String, String> form = WebSupport.parseForm(exchange);
        String requestId = form.get("requestId");
        String code = form.getOrDefault("code", "").trim();

        PendingLogin login = LoginManager.verify(requestId, code);
        if (login == null)
        {
            String body = """
                    <div class="lock">&#128274;</div>
                    <h1 style="text-align:center">Invalid or expired code</h1>
                    <div class="card" style="text-align:center">
                        <p>That code is wrong, expired, or already used.</p>
                        <a href="/">Start over</a>
                    </div>
                    """;
            WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Invalid Code", body));
            return;
        }

        String token = SessionManager.createSession(login.role, login.playerId, login.playerName);
        exchange.getResponseHeaders().add("Set-Cookie",
                WebSupport.SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400");
        WebSupport.redirect(exchange, login.role == Role.ADMIN ? "/admin" : "/user");
    }

    private void handleLogout(HttpExchange exchange) throws IOException
    {
        SessionManager.invalidate(WebSupport.getSessionToken(exchange));
        exchange.getResponseHeaders().add("Set-Cookie", WebSupport.SESSION_COOKIE + "=; Path=/; Max-Age=0");
        WebSupport.redirect(exchange, "/");
    }

    // ---------- admin ----------

    private static final int MARKET_PAGE_SIZE = 50;

    private record MarketRow(String id, double base, double current) {}

    private WebSupport.Page<MarketRow> paginateMarket(List<MarketRow> all, String query, int page, int pageSize)
    {
        return WebSupport.paginate(all, query, page, pageSize, MarketRow::id, Comparator.comparing(MarketRow::id));
    }

    private String adminRowHtml(MarketRow row)
    {
        return """
                <tr>
                    <td class="item-id">%s%s</td>
                    <td>$%.2f %s</td>
                    <td>
                        <form method="post" action="/admin/price" class="row-form">
                            <input type="hidden" name="item" value="%s">
                            <input type="number" step="0.01" min="0" name="basePrice" value="%.2f">
                            <button type="submit" class="btn">Save</button>
                        </form>
                    </td>
                    <td>
                        <form method="post" action="/admin/remove-item" onsubmit="return confirm('Remove %s from the market?');">
                            <input type="hidden" name="item" value="%s">
                            <button type="submit" class="btn danger">Remove</button>
                        </form>
                    </td>
                </tr>
                """.formatted(Html.escape(row.id()), Html.copyButton(row.id()), row.current(),
                priceBadge(row.base(), row.current()), Html.escape(row.id()), row.base(),
                Html.escape(row.id()), Html.escape(row.id()));
    }

    private void renderAdmin(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        List<MarketRow> allRows = MainThreadExecutor.run(server, () -> snapshotMarket());

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String msgHtml = WebSupport.messageBanner(query);
        String q = query.getOrDefault("q", "");
        WebSupport.Page<MarketRow> page = paginateMarket(allRows, q, WebSupport.parsePageParam(query.get("page")), MARKET_PAGE_SIZE);

        StringBuilder rowsHtml = new StringBuilder();
        for (MarketRow row : page.rows())
            rowsHtml.append(adminRowHtml(row));
        String emptyStyle = page.rows().isEmpty() ? "" : " style=\"display:none\"";
        String tableStyle = page.rows().isEmpty() ? " style=\"display:none\"" : "";

        String body = """
                <div class="topbar"><h1>Admin Panel</h1><a href="/logout">Log out</a></div>
                <p class="sub">Manage base prices for tradeable items. The current price drifts with buy/sell demand.</p>
                %s
                <div class="card" id="market-card">
                    <h2>&#128202; Market items <span class="count-pill js-count">%d</span></h2>
                    %s
                    <table%s><thead><tr><th>Item</th><th>Current price</th><th>Base price</th><th></th></tr></thead><tbody class="js-rows">%s</tbody></table>
                    <div class="empty-row js-empty"%s>No items match your search.</div>
                    %s
                </div>
                <div class="card">
                    <h2>&#10133; Add item</h2>
                    <form method="post" action="/admin/add-item">
                        <label>Item id (e.g. minecraft:netherite_ingot)</label>
                        <input type="text" name="item" required>
                        <label>Base price</label>
                        <input type="number" step="0.01" min="0" name="basePrice" required>
                        <input type="submit" value="Add to market">
                    </form>
                </div>
                <script>ServerModUI.initMarketTable({root:'#market-card', endpoint:'/admin/market.json', mode:'admin', initialPage:%d});</script>
                %s
                """.formatted(msgHtml, page.total(), WebSupport.searchBarHtml("/admin", q, "market", "Search items... (e.g. netherite, minecraft:diamond)"),
                tableStyle, rowsHtml, emptyStyle, WebSupport.pagerHtml("/admin", q, page, "market"), page.page(),
                business.adminCardsHtml() + territory.adminCardsHtml());
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - Admin", body));
    }

    // ---------- market JSON (search/pagination API backing the market table and item pickers) ----------

    private void renderMarketJson(HttpExchange exchange, Role requiredRole) throws IOException
    {
        Session session = SessionManager.get(WebSupport.getSessionToken(exchange));
        if (session == null || session.role != requiredRole)
        {
            WebSupport.sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String q = query.getOrDefault("q", "");
        int page = WebSupport.parsePageParam(query.get("page"));
        int pageSize = query.containsKey("pageSize") ? Math.max(1, Math.min(200, WebSupport.parsePageParam(query.get("pageSize")))) : MARKET_PAGE_SIZE;

        List<MarketRow> allRows = MainThreadExecutor.run(server, () -> snapshotMarket());
        WebSupport.Page<MarketRow> result = paginateMarket(allRows, q, page, pageSize);

        StringBuilder items = new StringBuilder();
        for (MarketRow row : result.rows())
        {
            if (items.length() > 0)
                items.append(',');
            double pct = row.base() > 0 ? (row.current() - row.base()) / row.base() * 100.0 : 0;
            String badgeClass = row.base() <= 0 ? "flat" : pct > 0.5 ? "up" : pct < -0.5 ? "down" : "flat";
            String badgeText = row.base() <= 0 ? "—" : "%s%+.1f%%".formatted(pct > 0.5 ? "▲ " : pct < -0.5 ? "▼ " : "", pct);

            items.append('{')
                    .append("\"id\":").append(WebSupport.jsonString(row.id())).append(',')
                    .append("\"current\":").append(row.current()).append(',');
            if (requiredRole == Role.ADMIN)
                items.append("\"base\":").append(row.base()).append(',');
            items.append("\"badgeClass\":").append(WebSupport.jsonString(badgeClass)).append(',')
                    .append("\"badgeText\":").append(WebSupport.jsonString(badgeText))
                    .append('}');
        }

        String json = "{\"items\":[" + items + "],\"total\":" + result.total() + ",\"page\":" + result.page()
                + ",\"pageSize\":" + result.pageSize() + ",\"totalPages\":" + result.totalPages() + "}";
        WebSupport.sendJson(exchange, 200, json);
    }

    // Renders the % difference between an item's current and base price as a colored pill.
    private String priceBadge(double base, double current)
    {
        if (base <= 0)
            return "<span class=\"badge flat\">&mdash;</span>";

        double pct = (current - base) / base * 100.0;
        String cls = pct > 0.5 ? "up" : pct < -0.5 ? "down" : "flat";
        String arrow = pct > 0.5 ? "&#9650; " : pct < -0.5 ? "&#9660; " : "";
        return "<span class=\"badge %s\">%s%+.1f%%</span>".formatted(cls, arrow, pct);
    }

    private List<MarketRow> snapshotMarket()
    {
        List<MarketRow> list = new ArrayList<>();
        MarketData.get(server).getAllEntries().forEach((id, entry) ->
                list.add(new MarketRow(id.toString(), entry.basePrice, entry.currentPrice)));
        return list;
    }

    private void handleAdminSetPrice(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            double price = WebSupport.parseNonNegativeDouble(form.get("basePrice"));
            MainThreadExecutor.run(server, () -> { MarketData.get(server).setBasePrice(id, price); return null; });
            WebSupport.redirectWithMessage(exchange, "/admin", "Updated base price for " + id, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    private void handleAdminAddItem(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            if (!ForgeRegistries.ITEMS.containsKey(id))
                throw new WebActionException("Unknown item: " + form.get("item"));
            double price = WebSupport.parseNonNegativeDouble(form.get("basePrice"));
            MainThreadExecutor.run(server, () -> { MarketData.get(server).setBasePrice(id, price); return null; });
            WebSupport.redirectWithMessage(exchange, "/admin", "Added " + id + " to the market", true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    private void handleAdminRemoveItem(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.ADMIN);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            boolean removed = MainThreadExecutor.run(server, () -> MarketData.get(server).removeEntry(id));
            WebSupport.redirectWithMessage(exchange, "/admin", removed ? "Removed " + id + " from the market." : "That item wasn't tracked.", removed);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/admin", e.getMessage(), false);
        }
    }

    // ---------- user ----------

    private record UserPageData(int balance, List<MarketRow> rows, boolean hasMailboxPending, boolean showMarketPrices) {}

    private String userRowHtml(MarketRow row)
    {
        return "<tr><td class=\"item-id\">%s%s</td><td>$%.2f %s</td></tr>".formatted(
                Html.escape(row.id()), Html.copyButton(row.id()), row.current(), priceBadge(row.base(), row.current()));
    }

    private void renderUser(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        UserPageData data = MainThreadExecutor.run(server, () -> new UserPageData(
                MoneyData.get(server).getMoney(session.playerId),
                snapshotMarket(),
                MailboxData.get(server).hasPending(session.playerId),
                BusinessData.get(server).showMarketPricesToPlayers()
        ));

        Map<String, String> query = WebSupport.parseQuery(exchange);
        String msgHtml = WebSupport.messageBanner(query);
        String q = query.getOrDefault("q", "");
        WebSupport.Page<MarketRow> page = paginateMarket(data.rows(), q, WebSupport.parsePageParam(query.get("page")), MARKET_PAGE_SIZE);

        StringBuilder rowsHtml = new StringBuilder();
        for (MarketRow row : page.rows())
            rowsHtml.append(userRowHtml(row));
        String emptyStyle = page.rows().isEmpty() ? "" : " style=\"display:none\"";
        String tableStyle = page.rows().isEmpty() ? " style=\"display:none\"" : "";

        String mailboxNote = data.hasMailboxPending()
                ? "<p class=\"sub\">&#128230; You have items waiting - they'll be delivered automatically next time you're online.</p>"
                : "";

        String marketCard = data.showMarketPrices()
                ? """
                        <div class="card" id="market-card">
                            <h2>&#128200; Market prices <span class="count-pill js-count">%d</span></h2>
                            <p class="sub">Reference only - this is what businesses pay to restock. To buy items yourself, browse <a href="/businesses">Businesses</a>.</p>
                            %s
                            <table%s><thead><tr><th>Item</th><th>Current price</th></tr></thead><tbody class="js-rows">%s</tbody></table>
                            <div class="empty-row js-empty"%s>No items match your search.</div>
                            %s
                        </div>
                        <script>ServerModUI.initMarketTable({root:'#market-card', endpoint:'/user/market.json', mode:'user', initialPage:%d});</script>
                        """.formatted(page.total(), WebSupport.searchBarHtml("/user", q, "market", "Search items... (e.g. netherite, minecraft:diamond)"),
                        tableStyle, rowsHtml, emptyStyle, WebSupport.pagerHtml("/user", q, page, "market"), page.page())
                : "";

        String body = """
                <div class="topbar"><h1>Hi, %s</h1><div class="topbar-links"><a href="/user/land">My Land</a><a href="/governments">Governments</a><a href="/user/business">My Business</a><a href="/businesses">Businesses</a><a href="/leaderboard">Leaderboard</a><a href="/logout">Log out</a></div></div>
                %s
                <div class="card">
                    <p class="sub">Your balance</p>
                    <div class="balance">%s</div>
                    %s
                </div>
                %s
                <div class="card">
                    <h2>&#128181; Sell</h2>
                    <p class="sub">Sells from your real in-game inventory, so you must be online right now.</p>
                    <form method="post" action="/user/sell">
                        <label>Item</label>
                        <div class="combo"><input type="text" name="item" id="sell-item" placeholder="Search items..." required></div>
                        <label>Amount</label>
                        <input type="number" name="amount" min="1" value="1" required>
                        <input type="submit" value="Sell">
                    </form>
                </div>
                <div class="card">
                    <h2>&#129309; Send to another player</h2>
                    <p class="sub">Money always works. Items require you to be online right now.</p>
                    <form method="post" action="/user/send">
                        <label>Recipient username</label>
                        <input type="text" name="target" required>
                        <label>Type</label>
                        <select name="type" data-toggle="#send-item-field">
                            <option value="money">Money</option>
                            <option value="item">Item</option>
                        </select>
                        <div id="send-item-field">
                            <label>Item id (only used when sending an item)</label>
                            <div class="combo"><input type="text" name="item" id="send-item" placeholder="minecraft:diamond"></div>
                        </div>
                        <label>Amount</label>
                        <input type="number" name="amount" id="send-amount" step="0.01" min="0.01" value="1" required>
                        <input type="submit" value="Send">
                    </form>
                </div>
                <script>
                    ServerModUI.initItemPicker(document.getElementById('sell-item'), '/user/market.json');
                    ServerModUI.initItemPicker(document.getElementById('send-item'), '/user/market.json');
                </script>
                """.formatted(Html.escape(session.playerName), msgHtml, Money.format(data.balance()), mailboxNote, marketCard);
        WebSupport.sendHtml(exchange, 200, Html.page("ServerMod Panel - User", body));
    }

    private void handleUserSell(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            ResourceLocation id = WebSupport.parseItemId(form.get("item"));
            int amount = WebSupport.parsePositiveInt(form.get("amount"));
            String message = MainThreadExecutor.run(server, () -> doSell(session.playerId, id, amount));
            WebSupport.redirectWithMessage(exchange, "/user", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user", e.getMessage(), false);
        }
    }

    private String doSell(UUID playerId, ResourceLocation id, int amount)
    {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null)
            throw new WebActionException("You must be online in-game to sell items.");

        MarketEntry entry = MarketData.get(server).getEntry(id);
        if (entry == null)
            throw new WebActionException("That item isn't tradeable.");

        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || !InventoryUtil.removeItem(player.getInventory(), item, amount))
            throw new WebActionException("You don't have " + amount + "x that item.");

        int unitPriceCents = (int) Math.floor(entry.currentPrice * 100);
        int totalCents = Math.max(0, unitPriceCents * amount);
        int newBalance = MoneyData.get(server).addMoney(server, playerId, totalCents);
        MarketData.get(server).applySell(id, amount);

        return "Sold " + amount + "x " + id + " for " + Money.format(totalCents) + " (balance: " + Money.format(newBalance) + ")";
    }

    private void handleUserSend(HttpExchange exchange) throws IOException
    {
        Session session = WebSupport.requireSession(exchange, Role.USER);
        if (session == null)
            return;

        Map<String, String> form = WebSupport.parseForm(exchange);
        try
        {
            String type = form.getOrDefault("type", "money");
            String targetName = form.getOrDefault("target", "").trim();
            if (targetName.isEmpty())
                throw new WebActionException("Enter a recipient username.");

            String message;
            if (type.equals("item"))
            {
                int amount = WebSupport.parsePositiveInt(form.get("amount"));
                message = MainThreadExecutor.run(server, () -> doSendItem(session.playerId, targetName, form.get("item"), amount));
            }
            else
            {
                int amountCents = Money.toCents(WebSupport.parsePositiveDouble(form.get("amount")));
                message = MainThreadExecutor.run(server, () -> doSendMoney(session.playerId, targetName, amountCents));
            }
            WebSupport.redirectWithMessage(exchange, "/user", message, true);
        }
        catch (WebActionException e)
        {
            WebSupport.redirectWithMessage(exchange, "/user", e.getMessage(), false);
        }
    }

    private GameProfile resolveSendTarget(UUID senderId, String targetName)
    {
        Optional<GameProfile> targetProfileOpt = server.getProfileCache().get(targetName);
        if (targetProfileOpt.isEmpty())
            throw new WebActionException("Unknown player: " + targetName);

        GameProfile targetProfile = targetProfileOpt.get();
        if (targetProfile.getId().equals(senderId))
            throw new WebActionException("You can't send to yourself.");
        return targetProfile;
    }

    private String doSendItem(UUID senderId, String targetName, String itemStr, int amount)
    {
        GameProfile targetProfile = resolveSendTarget(senderId, targetName);
        UUID targetId = targetProfile.getId();

        ServerPlayer sender = server.getPlayerList().getPlayer(senderId);
        if (sender == null)
            throw new WebActionException("You must be online in-game to send items.");

        ResourceLocation id = WebSupport.parseItemId(itemStr);
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || !InventoryUtil.removeItem(sender.getInventory(), item, amount))
            throw new WebActionException("You don't have " + amount + "x that item.");

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(targetId);
        if (targetPlayer != null)
            InventoryUtil.giveOrDrop(targetPlayer, new ItemStack(item, amount));
        else
            MailboxData.get(server).addPending(targetId, id, amount);

        return "Sent " + amount + "x " + id + " to " + targetProfile.getName();
    }

    private String doSendMoney(UUID senderId, String targetName, int amountCents)
    {
        GameProfile targetProfile = resolveSendTarget(senderId, targetName);
        UUID targetId = targetProfile.getId();

        MoneyData moneyData = MoneyData.get(server);
        if (moneyData.getMoney(senderId) < amountCents)
            throw new WebActionException("You don't have " + Money.format(amountCents) + ".");

        moneyData.addMoney(server, senderId, -amountCents);
        moneyData.addMoney(server, targetId, amountCents);
        return "Sent " + Money.format(amountCents) + " to " + targetProfile.getName();
    }
}
