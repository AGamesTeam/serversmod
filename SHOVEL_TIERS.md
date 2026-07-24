# Claim Shovel Tiers

## The one thing to understand first

A shovel's **tier only controls its economics** — price, how many claims it can make
before breaking, and the largest area it can select in one go. It does **not** change
what the resulting claim can do. Every land claim, regardless of tier, gets the exact
same set of configurable permission rules (`build`, `container_open`, `container_take`,
`interact`, `pvp`, `mob_damage`); every city/country founder gets full control
(`manage_laws`, `manage_jails`, `manage_permits`, `manage_rates`, `manage_officials`,
`manage_land_rules`) regardless of which tier founded it. "Access" differences come
from the **claim type** (land vs. city vs. country), not the tier.

Tiers are entirely admin-defined — there are no defaults shipped with the mod. Add or
remove them from `/admin` on the web panel ("Claim shovel tiers" card) or in-game with
`/land admin tier add <land|city|country> <name> <price> <maxCharges> <maxArea>`.

## What each claim type actually grants

| Type | Grants | Weekly rent owed to |
|---|---|---|
| **Land** | A plot you own outright: set `build`/`pvp`/`interact`/etc. rules, trust specific players with specific permissions. | The city it's inside (if any) — free if it's not inside a city. |
| **City** | A government over an area: set the land-rent rate charged to claims inside it, appoint officials, run jail cells, decide what access officials automatically get on citizens' land. | The country it's inside (if any) — free if it's not inside a country. |
| **Country** | The top-level government: everything a city has, plus country-wide laws (`no_fire`, `no_destruction`, `clothing_required`) with auto-enforcement, permits (per-player law exemptions), and Y-level/zone law overrides. | The server admin, at the configured admin rate. |

## Suggested tier ladder

These are starting points, not requirements — tune price/area to your economy.

### Land

| Tier | Price | Uses | Max area | Use case |
|---|---|---|---|---|
| **Starter** | $50–100 | 1 | ~1,000–2,500 blocks | A new player's first house plot. Cheap, small, disposable. |
| **Homestead** | $300–500 | 1–2 | ~5,000–10,000 blocks | A base with farms/storage. The "normal" player tier. |
| **Estate** | $1,000+ | 1 | 20,000+ blocks | Large builds, multi-structure compounds. Priced to be a real money sink. |

**Pros of more/cheaper tiers:** lets players claim exactly the size they need without
overpaying; a cheap 1-charge starter tier gives new players a low-risk way in.
**Cons:** more tiers = more admin upkeep and a more cluttered buy list; too cheap and
land claiming stops being a meaningful money sink.

### City

| Tier | Price | Uses | Max area | Use case |
|---|---|---|---|---|
| **Town** | $500–1,000 | 1 | ~10,000 blocks | A small settlement, one founder-run project. |
| **Metropolis** | $3,000–5,000 | 1 | 50,000+ blocks | A serious multi-player hub with recruited officials and land rent income. |

**Pros:** a cheap town tier lowers the barrier to community projects; a large
metropolis tier supports server-defining hub cities. **Cons:** city founding is
inherently higher-stakes than land (it creates an economy other players pay into) —
pricing it too low invites low-effort "cities" that squat on land without governing it.

### Country

| Tier | Price | Uses | Max area | Use case |
|---|---|---|---|---|
| **Nation** | $2,000–5,000 | 1 | 100,000+ blocks | The only tier most servers need — countries are meant to be rare, high-commitment endeavors covering multiple cities. |

**Pros:** a single expensive tier keeps countries scarce and meaningful, and caps how
much of the map one player can lock up with laws. **Cons:** if you only offer one
tier, smaller communities that just want *some* law enforcement (e.g. anti-grief on
wilderness) without full nation scale have no cheaper option — consider a second,
smaller "Province" tier if that's a problem on your server.

## Charges (`maxCharges`) — the other lever

Each shovel is single-purchase but can be used `maxCharges` times before breaking
(each *completed* corner selection consumes one charge, whether the resulting claim
is accepted or declined). A 1-charge shovel is simplest to price and reason about; a
multi-charge shovel is effectively a bulk discount for players who expect to claim
several plots, at the cost of making individual claims cheaper to "waste" on a
declined selection.

## Jurisdiction: what land/city/country combinations actually mean

City and country membership are independent layers — nothing requires a land claim to
sit inside a city, or a city inside a country. Here's what each combination actually
changes.

### 1. Land claim, no city, no country ("true wilderness house")
- No rent owed at all — `landRentBreakdown` only counts cities whose territory
  overlaps your claim; zero overlap = zero rent.
- No country laws apply (`no_fire`/`no_destruction`/`clothing_required` all require
  being inside a country's territory).
- **`reportable` rules are functionally dead here.** Filing a report requires a
  city/country whose territory overlaps the claim to have jurisdiction. With none,
  there's no government to file with. Use `blocked` instead if you want real
  protection.
- You still fully control `build`/`pvp`/`interact`/etc. and can trust individual
  players.

### 2. Land claim inside a city, city not inside a country
- You pay weekly rent to the city (`city.landClaimRatePerBlockCents × overlap
  blocks`).
- The city pays no rent upward (no country to pay).
- No country laws apply anywhere here — they all require an enclosing country, which
  doesn't exist.
- Reports: you *can* file with the city, since its territory overlaps your claim.
- If the city granted its officials automatic access to certain permissions
  (`officialaccess`), they get that access on your claim regardless of your own trust
  list.

### 3. Land claim inside a city, city inside a country
- Full chain: you pay the city, the city pays the country, the country pays the
  admin.
- Country laws apply to you — **including inside your own private land claim** for
  `no_fire` and `clothing_required` (those checks have no land-claim exemption).
  `no_destruction` is the exception: it explicitly skips any tile inside a land claim,
  because your claim's own `build` rule already governs that ground.
- Entering the area triggers the country's Accept/Decline border prompt regardless of
  whether you own land there — it's purely position-based, not claim-based.
- Reports: file with whichever government (city or country) actually overlaps your
  claim.

### 4. Land claim inside a country's territory but not inside any city ("countryside" claim)
- No city rent (nothing overlaps you).
- Country rent doesn't apply to land claims directly — only cities pay countries, and
  countries pay the admin. Your land itself owes nothing to the country.
- Country laws still apply (position-based, not city-based) — same fire/clothing/
  destruction rules as scenario 3.
- Reports: only the country has jurisdiction here.

### 5. Standalone city, no country
- Functions as a fully independent government — rates, officials, jail cells, land-
  claim rules all work normally.
- Owes nothing upward since there's no country.
- No country-level laws exist to inherit; if you want law enforcement you need an
  actual country layer.

### 6. Country exists, but you're just plain outside its borders
- None of its laws, rent, or border prompts touch you at all — territory checks are
  purely "is this x/z inside the country's claimed rectangles."

### Gotchas worth knowing
- **A land claim can straddle multiple cities.** `landRentBreakdown` sums rent per
  overlapping city, prorated to how many blocks fall in each — a claim spanning a
  city border pays both.
- **A location only ever belongs to one country.** Country territories are assumed
  not to overlap each other, unlike cities.
- **Fire/clothing laws beat claim ownership.** Even a fully private, fully-owned land
  claim doesn't exempt you from a country's `no_fire`/`clothing_required` — the only
  way out is the country granting `/land country <name> permit grant <player> <law>`.
- **`reportable` only means something where a government has jurisdiction.** Outside
  all city/country territory, it's a no-op setting.
