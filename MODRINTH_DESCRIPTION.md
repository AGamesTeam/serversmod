# ServerMod

**A full player-driven economy, business, and nation-building overhaul for your server — currently in early beta.**

> ⚠️ **This mod is in active early development (v0.1.0-beta).** It is feature-rich but **not battle-tested**. Expect bugs, unbalanced numbers, unfinished edge cases, and the occasional weird interaction between systems. Back up your world before installing, test on a non-critical server first, and please report anything broken. This is very much a "build in public" project — updates will be frequent, and things **will** change/break between versions.

ServerMod turns a vanilla server into a living economy: players run businesses, sell to a fluctuating market and to physical customer NPCs, claim land, and found cities and countries with their own laws, taxes, and jails — all manageable in-game or through a built-in web dashboard.

---

## 💰 Economy & Market

- Every player has a persistent money balance, visible in a HUD overlay and synced live.
- A real, fluctuating item market — prices move with every trade and drift back toward a base price over time, so the economy self-balances instead of getting mined into worthlessness.
- The **Trader Block**: right-click to instantly sell whatever you're holding at the current market price.
- The **Auto-Seller**: feed it with a hopper and it sells automatically, paying whoever placed it.
- Direct player-to-player trading of money or items via command.
- An offline mailbox delivers anything bought through the web panel next time you log in.

## 🏪 Businesses

- Register your own business, fund it, and stock it with the **Sell Barrel** (a real 27-slot container) to sell items on its web storefront.
- A **Business Sign** is placed automatically on registration, showing live hours and open/closed status.
- Manufacturing recipes, purchasable upgrades, and licenses to grow your business.
- Set hours, toggle closed, manage listings, and run advertising.
- Hire an **Employee NPC** who stands at the counter as a cosmetic sign of a staffed shop.
- Set up **customer queue points** so NPCs physically line up and wait to be served.
- A full public directory, leaderboard, and per-business storefront page on the web panel.

## 🧍 Customer NPCs & Reviews

- Physical customer NPCs spawn near listed businesses, walk over, queue, and wait to be served (or wander in, if you allow it).
- Every visit generates a **written, randomized review** (0–5 stars) — covering price fairness, wait time, and how rough the trip there was — so no two reviews read the same.
- Regular customers are tracked and rewarded with loyalty bonuses.
- Recent review scores actually matter: they scale a business's customer spawn rate and basket size up or down.
- Business owners can reply to reviews; admins can moderate them.

## 🏙️ Land, Cities & Countries

- Claim land with the **Land Claim Shovel** — right-click two corners and it's yours.
- Found full **cities** and **countries** with the City/Country Claim Shovels, complete with configurable claim tiers.
- Grant trust to other players on your claims, and set per-claim rules.
- Governments can set tax rates, sales tax, foreign tax, license fees, and victim-share payouts.
- Appoint government officials with delegated permissions, and designate public squares.
- A real-time border system prompts players entering/leaving territory with accept/decline options.
- Inter-government diplomacy: sanctions and alliances.
- A configurable law system, including zone-based enforcement and periodic clothing-law checks.
- A jail system with sentencing and real-time confinement/release.
- Citizen and visitor reporting, with resolve/deny workflows for city and country officials.
- Recurring territory billing, same as business billing, so upkeep is a real ongoing cost.

## 🌐 Built-In Web Panel

No plugins, no extra software — the mod runs its own lightweight web server with login sessions and Admin/User roles:

- **Admin dashboard:** live market editing, adding/removing tradeable items, business and land tier/rate/policy management.
- **User dashboard:** browse the market, sell items, send money, and fully manage your business or territory from a browser.
- **Public pages:** business directory, leaderboard, and storefronts anyone can view and buy from.

The panel is plain HTTP and defaults to `127.0.0.1` (localhost-only) for safety — see the config if you want to expose it further, ideally behind a reverse proxy.

## ⚙️ Configuration

Nearly everything is tunable: market impact/reversion rates and price bounds, customer NPC spawn/queue timing and capacity, review scoring windows and multipliers, business fees and grace periods, and the web server's port/bind address.

---

## Requirements

- Minecraft **1.20.1**
- Minecraft Forge **47+**

## Known State (Beta)

This is a **first release** of a large, interconnected set of systems (economy, businesses, NPCs, territory/government, and a web panel), all built and tuned at once. That means:

- Balance numbers (prices, fees, tax rates, spawn rates) are starting points, not final.
- Some feature combinations haven't been stress-tested together yet.
- The web panel is functional but unpolished, and only speaks plain HTTP.
- Expect config options and command syntax to shift as things get ironed out.

Bug reports, balance feedback, and suggestions are genuinely welcome — this mod is going to keep evolving fast.
