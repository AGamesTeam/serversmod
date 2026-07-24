N# Laws, Jail & Diplomacy

Countries can legislate, jail lawbreakers, and wage economic diplomacy against each other; any land claim owner can report a specific rule violation to their local government for review. This page covers all of it.

## Country laws

Laws are a **country-only** feature (cities don't have laws — a city's territory instead relies on individual land-claim rules, plus optional automatic official access; see [Land, Cities & Countries](Land-Cities-Countries.md)). There are exactly three laws, toggled and configured independently per country by an official with `manage_laws`:

| Law | How it's enforced |
|---|---|
| `no_fire` | Blocks flint & steel / fire charge use anywhere the law applies. Natural fire spread is unaffected. |
| `no_destruction` | Blocks breaking/placing blocks — but **only on unclaimed land** inside the country. Anywhere inside a personal land claim, that claim's own `build` rule governs instead. |
| `clothing_required` | Checked once a minute for every online player standing in the country: if you're missing a chestplate or leggings (helmet/boots don't matter) where the law applies, you're punished immediately — and again next minute if you're still not dressed. |

Each law can be configured with any combination of a **fine**, a **jail sentence** (cell + minutes), and a **mining quota** (a cents-of-market-value amount that must be mined off before release) — all three can stack on one violation.

**Where laws apply:** by default, everywhere in the country's territory up to a configurable Y-level ceiling (default 320 — effectively everywhere). A country can also carve out rectangular X/Z **zones** where laws apply *regardless* of the Y ceiling — useful for exempting, or specifically covering, a tall structure like a watchtower.

**Permits:** officials with `manage_permits` can exempt individual players from individual laws — a logging permit that exempts someone from `no_destruction`, for instance.

**Violation → punishment:** the triggering action is cancelled, the offender gets a message, and if the law has a fine and/or jail/quota configured, both apply immediately (fines go entirely to the country's treasury — unlike report fines, there's no victim to split with).

## Jail

A jail cell is a named, physical location (center point + radius + max capacity) that a city or country designates with `/land city|country <name> jail add <cell> <radius> <maxCapacity>`, requiring `manage_jails`.

**Getting sentenced** happens two ways:
- **Automatically**, from a country-law violation (above) — no human review.
- **Manually**, when an official resolves a filed [report](#reports) against a player.

**What happens when you're jailed:** checked in real time, about once a second. The moment you're online with an active, unapplied sentence, your entire inventory is confiscated and held, and you're teleported to the jail cell. While jailed, if you wander past the cell's radius you're simply teleported back to its center — confinement is enforced by repeated re-teleportation rather than a wall. A sentence can combine timed confinement with a **mining quota**: breaking blocks while jailed counts their live market value toward the quota, and release requires both the timer to expire *and* the quota to be met, if both are set.

**Release:**
- **Automatic**, the instant your sentence is complete (time served and quota met) while you're online — you're teleported back to wherever you were arrested from, and every confiscated item is returned (dropped on the ground if your inventory can't fit it all).
- **Manual pardon**, via `/land release <player>` — server-operator only, releases you immediately regardless of whether your sentence is actually finished.

If you're offline when your sentence naturally expires and confinement was never applied, it's simply dropped — no confiscation or teleport ever happens retroactively.

## Diplomacy — sanctions and alliances

Managed **only through the web panel** (`manage_commerce` required) — there's no in-game command for either. Both are unilateral: declaring a sanction or alliance against another government requires no acceptance from them, and either government can independently declare or withdraw one.

- **Sanctions** take effect the moment *either* side has declared one — checked from both directions.
- **Alliances** only produce their benefits once **mutual** — a one-sided alliance declaration has no economic effect until the other government reciprocates.

**Effects on businesses taxed by a sanctioning/sanctioned government:**
- Sales tax gets a flat **+50 percentage point** surcharge on top of the normal rate, if the business is a resident of a government on either side of a sanction relationship with the government taxing it.
- Theft risk (if enabled) **triples** for businesses caught in this kind of "trade war."

**Effects of a confirmed mutual alliance:**
- If two governments taxing the same sale are mutually allied, only the **larger** of their two cuts is actually charged — no double taxation between allies.
- A customer NPC whose home government has been listed as an ally by the taxing government gets a flat **10% discount** on their purchase (this direction only needs the taxing government's declaration, not mutual confirmation).

See [Businesses](Businesses.md) for how this combines with the rest of the sales tax formula (foreign surcharge, review-based discount).

## Reports

Reports are how citizens flag a **specific, already-happened rule violation** on a claim they own — distinct from country laws, which are proactively enforced. A rule has to be explicitly set to `reportable` (see [Land, Cities & Countries](Land-Cities-Countries.md#trust-and-per-claim-rules)) before anyone can file against it; there's no automatic detection.

**Filing:** `/land report city|country <government> <player> <permission> <reason>`, while standing on a claim you own, targeting a government that actually has jurisdiction over that claim (a city whose territory overlaps it, or the one country that does).

**Reviewing:** officials with `manage_jails` can list pending reports (`/land reports city|country <government>`) and either:
- **Deny** (`/land report deny <id>`) — dismissed, no further effect.
- **Resolve** (`/land report resolve <id> <fine> <cell|none> <minutes> <quota>`) — same fine/jail/quota mechanics as a law violation, with one difference: a resolved report's fine is **split** between the government's treasury and the player who filed the report, according to that government's `victimshare` percentage (default 50%). If the offender is online, confinement/confiscation is applied immediately rather than waiting for the next check.

## Billing recap

Laws, jails, and diplomacy don't have their own billing — they ride on the same weekly rent cascade (land → city → country → admin) and per-government treasuries covered in [Land, Cities & Countries](Land-Cities-Countries.md#billing-and-missed-payments). Fines and report shares are simply credited to or deducted from those same treasury/personal balances.

See [Commands](Commands.md) for the full `/land` command syntax covering laws, jails, and reports.
