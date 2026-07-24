# Land, Cities & Countries

Three independent tiers of claimable territory — personal land claims, cities, and countries — each claimed with its own shovel item, each with its own tier system, and each billed rent up the chain: land claims pay cities, cities pay countries, countries pay the server admin.

## Claim shovels

One item class (`ClaimShovelItem`) handles all three claim types (`LAND`, `CITY`, `COUNTRY`) — which one a given shovel is depends on which was bought. Buy one with `/land buy land|city|country <tier>` (see [Commands](Commands.md)); admins define the available tiers (name, price, number of uses, max area per use) via `/land admin tier add`, and there are none by default — an admin must set at least one tier up before players can buy shovels of that type.

**Selecting land — right-click two corners:**
1. First right-click marks corner 1.
2. Second right-click (same dimension) builds a rectangle from the two corners and checks it against the shovel's max-area limit.
3. The selection is automatically **molded** around every other claim of the same type — anything another player already claimed is subtracted out, so your selection can end up as an irregular shape hugging existing borders. City claims only mold around other cities, country claims only around other countries, land claims only around other land claims — the three tiers never block each other directly.
4. If nothing claimable is left after molding, the selection is rejected outright.
5. Otherwise you get a chat confirmation (with a rent preview) and 2 minutes to `/land accept <id>` or `/land decline <id>` — or just click the buttons.

**One charge is spent per completed selection**, whether you accept or decline it — the shovel breaks after its last charge. Confirmed claims that touch an existing claim you already own (of the same type) merge into it automatically instead of creating a separate claim.

City and Country shovels additionally require naming first with `/land name <name>` before you start selecting corners — the name is checked for uniqueness (case-insensitive) when you accept.

There's no cap on how much total land one player can own — only a per-selection area cap from the tier, and however many charges the shovel has.

## Trust and per-claim rules

Every land claim starts fully locked down: building, opening containers, and PvP are blocked by default; interacting and mob damage are allowed. You (the owner) can change any of six rules with `/land claim rule <permission> <mode>` while standing on your claim — `build`, `container_open`, `container_take`, `interact`, `pvp`, `mob_damage`, each set to `allowed`, `blocked`, or `reportable` (reportable doesn't block the action, but lets other players later file a [report](Laws-Jail-Diplomacy.md#reports) against whoever did it).

Trust is **granular, per-player, per-permission** — `/land claim trust <player> <permission>` grants one specific permission to one player on the claim you're standing on; `/land claim untrust <player>` removes them entirely (there's no way to revoke just one permission from an already-trusted player via command). Only the claim owner can grant or revoke trust — trusted players can't delegate further.

Permission checks resolve in order: **you own it** → always allowed; **you're individually trusted** with that permission → allowed; **you're a city official** with `manage_land_rules` in a city that's turned on automatic official access for that permission → allowed; otherwise, the claim's own public rule decides.

## Founding a city or country

Same shovel/corner/molding flow as land claims, but founding a **City** or **Country** (via `Government`, the shared base class for both) instead. There's no separate "founding fee" beyond the shovel's purchase price — but the moment it's founded, it starts owing weekly rent to whatever government surrounds it (previewed before you confirm).

A brand-new city or country starts with all its rates at zero — an official with `manage_rates` has to set them explicitly:

- **City**: `landClaimRatePerBlockCents` — weekly per-block rent charged to any land claim overlapping its territory.
- **Country**: `cityRatePerBlockCents` — weekly per-block rent charged to any city overlapping its territory.

Countries pay the server admin directly at a single, server-wide flat rate (`/land admin rate country <amount>`).

## Government officials

The founder always has every permission implicitly. Beyond that, officials are granted specific, individually-toggleable permissions (`/land city|country <name> official grant <player> <permission>`) — there are no fixed "ranks," just a checklist so a founder can hand out exactly as much control as they want:

| Permission | Grants |
|---|---|
| `manage_laws` | Country-only: enable/configure laws, Y-level cap, zones |
| `manage_jails` | Jail cells, resolving/denying reports, victim-share rate |
| `manage_permits` | Grant/revoke individual law exemptions |
| `manage_rates` | Claim/city rent rates, sales tax %, reactivating a suspended government |
| `manage_officials` | Grant/revoke other officials |
| `manage_land_rules` | City-only: toggle automatic official access to land claims within the city |
| `manage_commerce` | Public squares, licenses, foreign-tax surcharge, sanctions/alliances, subsidies, festivals, endorsements, audits — the government-vs-business web-panel levers, deliberately kept separate from plain rate-setting |

Revoking an official (`official revoke <player>`) removes them as an official entirely — there's no in-game way to strip a single permission from an existing official, only all-or-nothing removal.

## Tax rates and fees

Each government independently sets, per `/land city|country <name> ...`:

- **`rate`** — weekly per-block rent owed by claims underneath it (see billing cascade above).
- **`salestax <percent>`** — cut taken from every sale made by a business with a Sell Barrel inside its territory.
- **`victimshare <percent>`** — what share of a resolved report's fine goes to the person who filed it, versus the treasury (default 50%).

Two more levers exist **only through the web panel** (no `/land` command), gated on `manage_commerce`:
- **Foreign tax surcharge** — extra percentage points added on top of sales tax for a business owner who isn't a resident (doesn't personally hold a claim overlapping the territory).
- **License fee** — a one-time fee a business must pay before it gets any customer NPC traffic at all while it has a barrel in this territory.

A business with barrels in multiple overlapping governments' territory pays each one separately, at its own effective rate — see [Businesses](Businesses.md) for how sales tax stacks with sanctions, alliances, and review-based discounts.

## Public squares

A civic amenity: `/land city|country <name> square add <radius>`, placed physically at the official's current position (must have `manage_commerce` and the government must afford the admin-set cost, capped by an admin-set max radius). Any business with a customer queue point inside a public square counts as automatically discoverable to customer NPCs, even with no sign placed and no advert running — a form of public infrastructure a government can extend to businesses. `square remove <index>` / `square list` manage them.

## Real-time border crossings

Countries (not cities or plain land claims) prompt players in real time as they cross the border: a chat message lists the country's currently-enabled laws with **Accept**/**Decline** buttons, checked roughly once a second for every online player. Accepting records your agreement (tied to a version number that bumps every time the country's laws change, so you're re-prompted if the rules changed since you last agreed); declining teleports you straight back to wherever you last stood outside any country's territory. See [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md) for what those laws actually do.

## Billing and missed payments

Land claims, cities, and countries are each billed weekly and independently, using the same three-tier policy as businesses: **Suspend** (frozen until reactivated), **Dissolve** (deleted, remaining treasury refunded to the founder/owner), or **Grace then suspend** (a configurable grace period first). Admins set the policy and grace period separately for each of the three tiers (`/land admin policy|gracedays <type> ...`). A suspended land claim, city, or country simply stops accruing further billing attempts until its owner/officials pay off the amount due with `/land reactivate` or `/land city|country <name> reactivate`.

See [Commands](Commands.md) for the full command syntax and [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md) for laws, jails, sanctions, alliances, and the reporting workflow.
