# ServerMod Wiki

ServerMod turns a vanilla Minecraft server into a living, player-driven economy: players run businesses, sell to a fluctuating market and physical customer NPCs, claim land, and found cities and countries with their own laws, taxes, and jails — all manageable in-game or through a built-in web dashboard. Requires Minecraft **1.20.1** and Forge **47+**.

This wiki documents every system in detail, based directly on the mod's source code.

## Pages

### Start here
- **[For Players](For-Players.md)** — what's in it for you: fast ways to make money, running a business well, claiming and defending land, and getting involved in government.
- **[For Server Owners](For-Server-Owners.md)** — what this does for your server: retention, economic balance, decentralized moderation, anti-griefing, and what to watch as an admin.

### Core economy
- **[Economy & Market](Economy-Market.md)** — player balances, the fluctuating item market, the Trader Block, the Auto-Seller, direct trading, and the offline mailbox.

### Businesses
- **[Businesses](Businesses.md)** — registration, the Sell Barrel, Business Sign, manufacturing, upgrades, licenses, employees, queue points, hours, advertising, billing, and theft.
- **[Customer NPCs & Reviews](Customer-NPCs-Reviews.md)** — how customer NPCs spawn, walk, queue, and get served; how star ratings and written reviews are generated; loyalty/regulars; and the review-to-traffic feedback loop.

### Territory & government
- **[Land, Cities & Countries](Land-Cities-Countries.md)** — claim shovels, trust and per-claim rules, founding cities/countries, government officials, tax rates, public squares, real-time border crossings, and billing.
- **[Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md)** — country laws and zones, the jail/sentencing system, sanctions and alliances between governments, and the citizen reporting workflow.

### Platform
- **[Web Panel](Web-Panel.md)** — the mod's built-in HTTP dashboard: login, admin tools, the user dashboard, and public directory pages.
- **[Commands](Commands.md)** — the full in-game command reference (`/money`, `/trade`, `/business`, `/land`).
- **[Client & HUD](Client-HUD.md)** — the money HUD overlay, chat-based interactive prompts, and NPC rendering.
- **[Configuration](Configuration.md)** — every Forge config option, and where to find the settings that live in runtime data instead.

## How the systems connect

- A **business** only gets customer traffic if it's licensed with every government taxing it, discoverable (sign, advert, or public square), stocked, and open — see [Businesses](Businesses.md) and [Land, Cities & Countries](Land-Cities-Countries.md).
- Every sale a business makes is taxed by whichever governments' territory its Sell Barrels sit in, modified by sanctions, alliances, and the business's own review score — see [Businesses](Businesses.md#billing-and-missed-payments) and [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md#diplomacy--sanctions-and-alliances).
- **Reviews** (from both NPCs and real player purchases) directly control how often and how much future customers spend — see [Customer NPCs & Reviews](Customer-NPCs-Reviews.md#reviews--how-the-star-score-works).
- Almost everything billed (businesses, land claims, cities, countries) follows the same weekly cycle and three-policy missed-payment model (Suspend / Dissolve / Grace-then-suspend).
- Most in-game "popup" interactions (claim confirmations, customer NPC offers, border crossings) are chat messages with clickable buttons that run ordinary commands under the hood — see [Client & HUD](Client-HUD.md).

## Beta status

This is a first release of a large, interconnected set of systems, all built and tuned at once. Balance numbers are starting points, not final; some feature combinations haven't been stress-tested together; and the web panel is functional but speaks plain HTTP only (see the security notes in [Web Panel](Web-Panel.md)). Expect config and command syntax to keep shifting as things get ironed out.
