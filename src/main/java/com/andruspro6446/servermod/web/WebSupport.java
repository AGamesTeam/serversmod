package com.andruspro6446.servermod.web;

import com.sun.net.httpserver.HttpExchange;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

// Shared HTTP/HTML plumbing used by every route handler (WebHandler, BusinessHandler): request parsing,
// session lookup, response writing, and the search/pagination helpers behind any list page that could grow
// large (the market table, the business directory, ...).
public final class WebSupport
{
    public static final String SESSION_COOKIE = "servermod_session";

    private WebSupport()
    {
    }

    public record Page<T>(List<T> rows, int page, int pageSize, int total, int totalPages) {}

    // Filters (case-insensitive substring on the search key), sorts, and paginates a list. Used for any page
    // that renders a list which could grow large - only `pageSize` rows are ever rendered or sent over the
    // wire at once, no matter how many total items exist.
    public static <T> Page<T> paginate(List<T> all, String query, int page, int pageSize, Function<T, String> searchKey, Comparator<T> order)
    {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Predicate<T> matches = q.isEmpty() ? t -> true : t -> searchKey.apply(t).toLowerCase(Locale.ROOT).contains(q);
        List<T> filtered = all.stream().filter(matches).sorted(order).toList();

        int total = filtered.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        int safePage = Math.min(Math.max(1, page), totalPages);
        int from = Math.min(total, (safePage - 1) * pageSize);
        int to = Math.min(total, from + pageSize);
        return new Page<>(filtered.subList(from, to), safePage, pageSize, total, totalPages);
    }

    public static int parsePageParam(String raw)
    {
        try
        {
            return Math.max(1, Integer.parseInt(raw));
        }
        catch (Exception e)
        {
            return 1;
        }
    }

    // A GET search box (no JS required) that ServerModUI.initMarketTable() progressively enhances into a
    // live, no-reload search once the page loads.
    public static String searchBarHtml(String action, String q, String rootId, String placeholder)
    {
        return """
                <div class="search-bar">
                    <form method="get" action="%s" class="js-search-form" id="%s-form">
                        <input type="search" name="q" value="%s" placeholder="%s" class="js-search-input" id="%s-search">
                    </form>
                </div>
                """.formatted(action, rootId, Html.escape(q), Html.escape(placeholder), rootId);
    }

    // Prev/Next links that work with plain page reloads; ServerModUI.initMarketTable() replaces this with
    // AJAX-driven buttons once JS runs.
    public static String pagerHtml(String action, String q, Page<?> page, String rootId)
    {
        if (page.totalPages() <= 1)
            return "<div class=\"pager js-pager\" id=\"" + rootId + "-pager\"></div>";

        String qParam = URLEncoder.encode(q == null ? "" : q, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("<div class=\"pager js-pager\" id=\"" + rootId + "-pager\">");
        if (page.page() > 1)
            sb.append("<a class=\"btn ghost\" href=\"%s?q=%s&page=%d\">&lsaquo; Prev</a>".formatted(action, qParam, page.page() - 1));
        sb.append("<span class=\"page-info\">Page %d of %d</span>".formatted(page.page(), page.totalPages()));
        if (page.page() < page.totalPages())
            sb.append("<a class=\"btn ghost\" href=\"%s?q=%s&page=%d\">Next &rsaquo;</a>".formatted(action, qParam, page.page() + 1));
        sb.append("</div>");
        return sb.toString();
    }

    public static Map<String, String> parseForm(HttpExchange exchange) throws IOException
    {
        return parseUrlEncoded(readBody(exchange));
    }

    public static Map<String, String> parseQuery(HttpExchange exchange)
    {
        return parseUrlEncoded(exchange.getRequestURI().getRawQuery());
    }

    private static Map<String, String> parseUrlEncoded(String raw)
    {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty())
            return result;
        for (String pair : raw.split("&"))
        {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String readBody(HttpExchange exchange) throws IOException
    {
        try (InputStream in = exchange.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static String getSessionToken(HttpExchange exchange)
    {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null)
            return null;
        for (String part : cookieHeader.split(";"))
        {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq < 0)
                continue;
            if (trimmed.substring(0, eq).equals(SESSION_COOKIE))
                return trimmed.substring(eq + 1);
        }
        return null;
    }

    // Looks up the session and, if it doesn't match the required role, redirects to "/" and returns null.
    // Callers must check for null and return immediately when they get it.
    public static Session requireSession(HttpExchange exchange, Role requiredRole) throws IOException
    {
        Session session = SessionManager.get(getSessionToken(exchange));
        if (session == null || session.role != requiredRole)
        {
            redirect(exchange, "/");
            return null;
        }
        return session;
    }

    public static void sendHtml(HttpExchange exchange, int status, String html) throws IOException
    {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }

    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException
    {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }

    public static void redirect(HttpExchange exchange, String location) throws IOException
    {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    public static void redirectWithMessage(HttpExchange exchange, String path, String message, boolean ok) throws IOException
    {
        String query = "msg=" + URLEncoder.encode(message, StandardCharsets.UTF_8) + "&ok=" + (ok ? "1" : "0");
        redirect(exchange, path + "?" + query);
    }

    public static String messageBanner(Map<String, String> query)
    {
        String msg = query.get("msg");
        if (msg == null || msg.isEmpty())
            return "";
        boolean ok = "1".equals(query.get("ok"));
        return "<div class=\"msg " + (ok ? "ok" : "error") + "\">" + Html.escape(msg) + "</div>";
    }

    public static ResourceLocation parseItemId(String raw)
    {
        if (raw == null || raw.isBlank())
            throw new WebActionException("Missing item id.");
        ResourceLocation id = ResourceLocation.tryParse(raw.trim());
        if (id == null)
            throw new WebActionException("Invalid item id: " + raw);
        return id;
    }

    public static double parseNonNegativeDouble(String raw)
    {
        try
        {
            double value = Double.parseDouble(raw);
            if (value < 0)
                throw new WebActionException("Value must not be negative.");
            return value;
        }
        catch (NumberFormatException e)
        {
            throw new WebActionException("Invalid number: " + raw);
        }
    }

    public static double parsePositiveDouble(String raw)
    {
        try
        {
            double value = Double.parseDouble(raw);
            if (value <= 0)
                throw new WebActionException("Amount must be positive.");
            return value;
        }
        catch (NumberFormatException e)
        {
            throw new WebActionException("Invalid number: " + raw);
        }
    }

    public static int parsePositiveInt(String raw)
    {
        try
        {
            int value = Integer.parseInt(raw);
            if (value <= 0)
                throw new WebActionException("Amount must be positive.");
            return value;
        }
        catch (NumberFormatException e)
        {
            throw new WebActionException("Invalid number: " + raw);
        }
    }

    // Escapes a string as a JSON string literal, including the surrounding quotes.
    public static String jsonString(String s)
    {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20)
                        sb.append("\\").append('u').append("%04x".formatted((int) c));
                    else
                        sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
