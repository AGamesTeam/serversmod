# Changelog

All notable changes to **ServerMod** will be documented in this file.

## [0.1.0-beta] - 2026-07-24

Initial release. ServerMod adds a player-driven economy, businesses, and land/government simulation to a Minecraft Forge 1.20.1 server, backed by a web-based admin/user panel.

### Economy & Market
- Added a per-player money balance system (`money` command: `get`, `add`, `remove`, `set`), synced live to the client and shown in a HUD overlay.
- Added `/trade money` and `/trade item` for direct player-to-player trading.
- Added a live, fluctuating item market: prices shift with every buy/sell (`marketImpactRate`) and drift back toward a base price over time (`marketReversionRate` / interval), clamped between configurable min/max multipliers.
- Added the **Trader Block**: right-click to instantly sell the held item at the current market price.
- Added the **Auto-Seller**: hopper items into it to sell automatically at market price, paying whoever placed it.
- Added an offline mailbox that delivers items purchased through the web panel the next time the buyer logs in.
- Added config-seeded starting market prices for a fresh world (managed live from the admin panel afterwards).

### Businesses
- Added player-owned businesses: registration, funding/withdrawing balance, and withdrawing stocked items.
- Added the **Sell Barrel**, a 27-slot container businesses stock (by hand or hopper) to sell items on their web storefront.
- Added the **Business Sign**, auto-placed on registration, showing a business's hours and live open/closed status.
- Added business storefronts on the web panel: public directory, leaderboard, per-business page, and reviews.
- Added configurable business fees (registration, weekly, sell-barrel) and a missed-payment policy with a grace-day period (`business fee`, `business policy`, `business gracedays`).
- Added manufacturing recipes, purchasable business upgrades, and licenses.
- Added business hours, closed-toggle, listing management, and advertising.
- Added employee hiring with a cosmetic Employee NPC standing at the counter.
- Added customer NPC queue points (`business queue add|remove`) so physical customers line up to be served.
- Added a chat-based purchase flow for customer NPCs (accept/decline/haggle).

### Customer NPCs & Reviews
- Added physical Customer NPCs that spawn near listed businesses, walk to a queue point, wait to be served, and leave afterward.
- Added wandering NPCs and configurable spawn/queue intervals, capacity, and service timeout.
- Added a review system: each visit outcome (completed, declined, timed out, turned away for being closed) generates a 0-5 star review with randomized, varied written feedback covering price fairness, wait time, and trip safety.
- Added regular-customer tracking and loyalty bonuses that factor into review generation.
- Added recent-review scoring that scales a business's customer spawn rate and basket size up or down.
- Added the ability for business owners to reply to reviews, and for admins to remove them.

### Land, Cities & Countries
- Added land claiming with the **Land Claim Shovel** (right-click two corners to claim), plus **City** and **Country** claim shovels to found governments.
- Added claim tiers (admin-configurable), purchasable via `/land buy land|city|country <tier>`.
- Added claim trust/untrust for granting other players build/use permission, and per-claim rules.
- Added claim and government reactivation after a lapsed claim.
- Added city/country governments with tax rates, sales tax, foreign tax, license fees, and victim-share settings.
- Added official access roles: granting/revoking government officials with delegated permissions.
- Added public squares that governments can designate within their territory.
- Added a border system with real-time enter/leave prompts (accept/decline) between territories.
- Added inter-government diplomacy: sanctions and alliances.
- Added a law system: enable/configure custom laws, zone-based enforcement, and a periodic clothing-law check.
- Added a jailing system: government-assigned jail cells, sentencing, and real-time confinement/release ticking.
- Added a citizen/visitor reporting system with resolve/deny workflows for city and country governments.
- Added billing for territory upkeep, run on the same real-time schedule as business billing.
- Added an admin command suite for tier management, country-wide rates, policy, and commerce costs.

### Web Admin/User Panel
- Added a built-in HTTP server (`webServerEnabled`, `webServerPort`, `webServerBindAddress`) with a login/session system and Admin vs. User roles.
- Added an admin dashboard: live market editing, adding/removing tradeable items, business settings/recipes/reviews, and land tier/rate/policy/commerce-cost management.
- Added a user dashboard: market view, selling items, sending money, and a full business management panel (fund, withdraw, buy, sell, manufacture, listings, hours, upgrades, licenses, advertising).
- Added a land dashboard: shovel purchases, claim rules, trust management, and government administration (rates, taxes, sanctions, alliances, licenses).
- Added a public business directory, leaderboard, and per-business storefront with purchase and review submission.

### Configuration
- Added `Config` options for the web server, market tuning (impact rate, reversion rate/interval, min/max multipliers), customer NPC queue behavior (timeout, capacity), and review scoring (recency window, min/max rate multipliers).
