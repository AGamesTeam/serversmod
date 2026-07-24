# For Server Owners: What This Does For Your Server

ServerMod isn't a single feature — it's an economic and social operating system for the whole server. This page covers why it's worth running, what it actually does for server health, and what to watch as an admin. See [Configuration](Configuration.md) for every knob mentioned here.

## It gives players long-term goals beyond gear progression

Vanilla survival runs out of things to chase once a player is geared up. A living economy doesn't — there's always a bigger business, a better rating, more territory, a government to run or overthrow diplomatically. This is the single biggest lever for **long-term retention**: players who've registered a business, claimed land, or taken office in a government have a reason to keep logging in that has nothing to do with grinding for loot.

## Money sinks keep the economy from collapsing into inflation

Every layer of the mod has a real, recurring cost: business registration and weekly fees, Sell Barrel and Business Sign upkeep, employee wages, land/city/country rent, government commerce actions (festivals, endorsements, audits, public squares), and one-time upgrade/license costs. None of this money vanishes into nowhere by accident — it flows to other players and governments (treasuries, victim shares, sales tax) or, at the top of the chain, out of the game entirely to the server admin's country rent. Left unmanaged, a player economy trends toward everyone sitting on infinite currency with nothing meaningful left to spend it on; this mod is built specifically to prevent that.

## The market self-balances without you touching it

Prices move with every trade and drift back toward a base price over time (see [Economy & Market](Economy-Market.md)) — so a resource can't be permanently mined into worthlessness, and a shortage can't spiral forever either. You set base prices once (or seed them from config on a new world) and the market handles the rest. The tuning knobs (`marketImpactRate`, `marketReversionRate`, bounds) let you make the economy as volatile or as stable as fits your server's pace, without ever needing to manually intervene in day-to-day prices.

## Governance is decentralized to your players — by design

Cities and countries aren't cosmetic labels; they're real governments with treasuries, tax rates, laws, jails, and delegated official permissions. That means **you don't have to be the sole arbiter of every land dispute or rule violation** — trusted players can run their own jurisdictions, set their own tax policy, write their own laws (within the three supported categories), and resolve their own citizen reports. This scales moderation-adjacent work outward instead of it all landing on server staff, and gives your most invested players real stakes in keeping their corner of the server functional.

## Built-in tools reduce your admin overhead, not increase it

- **The web panel** means you (and any op) can manage the market, businesses, and territory system from a browser — no memorizing command syntax, no plugin stack, no extra software to install. See [Web Panel](Web-Panel.md).
- **Review moderation** gives you a lightweight, built-in way to remove abusive or spam reviews without needing a separate reporting system.
- **The jail system** gives player governments a real, in-world consequence for rule-breaking that doesn't require you personally banning or muting anyone — most day-to-day discipline (theft, destruction, clothing violations, reported offenses) can be handled entirely by player-run governments.
- **Runtime-configurable business/territory settings** (fees, spawn rates, policies) live in world data editable from the admin panel, not the Forge config file — meaning you can retune the economy live, without a restart, as you watch how your specific player base actually plays.

## Anti-griefing comes for free with land claims

The claim/trust/permission system (build, container access, PvP, mob damage — see [Land, Cities & Countries](Land-Cities-Countries.md)) gives every player a real way to protect their base without you needing a separate land-claim plugin. It's already wired into the economy too — claimed land protects a business's storefront from the optional theft mechanic, and claim ownership determines tax residency and government jurisdiction.

## It creates emergent, visible activity

Physical customer NPCs walking to shops, queueing, and leaving written reviews (see [Customer NPCs & Reviews](Customer-NPCs-Reviews.md)) make the server *look* alive even when player counts are modest — moving villagers, active storefronts, and a public leaderboard and business directory give newcomers something to see and aspire to within minutes of joining, rather than an empty world with just a spawn sign.

## What to actually watch as an admin

- **This is early beta.** Balance numbers (prices, fees, tax rates, spawn rates) are starting points, not tuned defaults — expect to adjust them for your server's economy size and player count rather than trusting the out-of-the-box values.
- **The web panel is plain HTTP, not HTTPS.** Leave it bound to `127.0.0.1` unless you're putting a TLS-terminating reverse proxy in front of it — see the security notes in [Web Panel](Web-Panel.md). There's no CSRF protection, so don't expose it carelessly.
- **Missed-payment policy matters more than it looks.** `DISSOLVE` is unforgiving for new/casual players who miss a week; `SUSPEND` or `GRACE_THEN_SUSPEND` are gentler defaults if you want the economy to be forgiving rather than punishing toward less-active players.
- **Feature combinations haven't all been stress-tested together** at scale — keep an eye on interactions (e.g., theft + sanctions + trade-war zones, or heavy customer-NPC spawn rates on a busy server) as your player base grows.
- **Back up your world before installing or updating**, and test on a non-critical server first — this is explicitly "build in public," and things can change between versions.

## Bottom line

This mod trades a modest amount of setup and ongoing tuning for a genuinely deep, self-sustaining economic and social layer that gives players reasons to keep playing, gives your most engaged players real governance responsibility, and gives you tools to manage all of it without extra plugins. See [Configuration](Configuration.md) for the full list of levers, and [Commands](Commands.md) for the admin-only command reference.
