# Web Panel

ServerMod runs its own lightweight HTTP server, no plugins or extra software required — a login-session-based dashboard for admins and players alike, built entirely on the JDK's built-in `HttpServer` with hand-written HTML (no framework, no build step, no static assets — everything is generated per request).

## Starting up and access

- Starts automatically with the server (unless disabled) and stops when the server does.
- Binds to `127.0.0.1` (localhost-only) on port `8080` by default — see [Configuration](Configuration.md) to change the port or widen the bind address.
- **Plain HTTP only, no TLS.** The mod explicitly warns: only widen access beyond localhost on a trusted network, or behind a TLS-terminating reverse proxy.
- Handles up to 4 concurrent requests via a small fixed thread pool, independent of the main Minecraft game thread — but every action that actually touches game state is funneled back onto the main server thread before it runs, so there's no risk of it racing the game tick.

## Logging in — no passwords

There's no username/password to remember. Logging in works entirely through in-game chat:

1. Open the panel and choose **Admin login** or **User login** (with your username).
2. **Admin**: a one-time code is sent to every currently-online server operator in chat. **User**: a code is sent privately to that player, only if they're currently online.
3. The code is a clickable chat component — click it to copy it to your clipboard — then paste it into the panel to finish logging in.
4. Codes are cryptographically random, expire after 5 minutes, allow at most 5 guesses, and are single-use.

A successful login issues a session cookie valid for **24 hours**. Sessions live only in memory — a server restart logs everyone out, which the mod treats as fine since a fresh code is always just one chat message away. A session is permanently tied to whichever role (`Admin` or `User`) you logged in as; there's no live re-check against your current in-game op status.

## Admin dashboard

Everything under `/admin`, admin session required.

- **Market management** — search/paginate every tradeable item, edit base prices, add new tradeable items, remove items from the market entirely. See [Economy & Market](Economy-Market.md).
- **Business settings** — one form controlling essentially every business-side cost and toggle: registration/weekly/barrel fees, missed-payment policy and grace period, whether players can see wholesale prices, the physical customer-NPC system (master toggle, spawn interval, base spend, spawn radius), Business Sign fee, advert cost/duration, ambient wanderer settings, employee wage, all four upgrade costs, and theft toggle/chance. See [Businesses](Businesses.md) and [Customer NPCs & Reviews](Customer-NPCs-Reviews.md).
- **Manufacturing recipes** — define server-wide recipes (up to 3 inputs → 1 output), available to every business equally.
- **Review moderation** — delete any review outright (no editing) from any business, either from the admin panel or directly from a storefront page while logged in as admin.
- **Businesses overview** — a read-only table of every business: status, balance, storefront visibility, review count, with a link into each one to moderate.
- **Land/government admin** — claim-shovel tiers per type (name, price, uses, max area), the flat country→admin rent rate, missed-payment policy and grace period per claim type, and the global flat costs for government commerce actions (festivals, endorsements, audits, public squares). See [Land, Cities & Countries](Land-Cities-Countries.md).

## User dashboard

Everything under `/user`, user session required. Shows your balance, a note if you have items waiting in your [mailbox](Economy-Market.md#offline-mailbox), and (if the admin has enabled it) a read-only market price table.

- **Sell items** from your real in-game inventory at the current market price (you must be online).
- **Send** money (works even if the recipient is offline) or an item (queued to their mailbox if they're offline) to another player by username.
- **My Business** — the full business console: register, fund/withdraw between personal and business balance, buy from the market or manufacture into virtual stock, withdraw virtual stock to your real inventory, price and list items on your Sell Barrel storefront, set hours/toggle closed, opt in/out of customer NPCs, hire/fire an employee, run adverts, buy upgrades and government licenses, reply to reviews, and view order history. See [Businesses](Businesses.md).
- **My Land** — every claim/city/country you own or hold office in: per-claim permission rules and trusted-player management, buy claim shovels, a "file a report" form, a pending-reports inbox if you're an official, and (if you hold the relevant permission) government management: rates, sales tax, victim share, foreign tax surcharge, license fee, sanctions/alliances, subsidies, festivals, endorsements, audits, state affiliation, officials, jail cells, laws/permits/zones, and public squares. See [Land, Cities & Countries](Land-Cities-Countries.md) and [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md).

## Public and directory pages

- **Business directory** and **leaderboard** — searchable/sortable lists of listed businesses, with reputation badges (Top Rated, Most Popular, Fastest Growing) and government endorsement badges. Requires a **User** login — not truly anonymous.
- **Storefront pages** — browse a specific business's listed items with live stock, buy directly (paid from your personal balance, delivered to your inventory or mailbox if offline), and read/leave reviews. Requires a User (or Admin) session.
- **Government directory** — the one genuinely anonymous page: lists every city and country (name, founder, size, billing status) with no login required at all.

There's no anonymous shopping flow — you need at minimum a one-time User login before browsing, buying, or reviewing anything. Buying items on the shared wholesale market is deliberately *not* offered to ordinary players here — that market is a business's wholesale supply, not a public storefront; regular players buy from business storefronts instead.

## Security notes

- Session cookies are `HttpOnly` (JS can't read them) with `SameSite=Lax`, though without a `Secure` flag (moot on plain HTTP anyway).
- Login codes are checked with constant-time comparison to resist timing attacks.
- User-supplied text (names, review text, item IDs) is consistently HTML-escaped before rendering.
- **There is no CSRF protection** on any form — a real gap worth knowing about if you ever widen the bind address beyond localhost.
- No rate limiting beyond the per-code 5-attempt cap — nothing stops repeatedly requesting fresh codes.

Given all this, the safe default (`127.0.0.1`) is strongly recommended unless you're putting a TLS-terminating reverse proxy in front of it. See [Configuration](Configuration.md).
