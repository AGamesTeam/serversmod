# Businesses

A business is a player-owned storefront: register one, fund it, manufacture or buy stock, physically shelve it in Sell Barrels, and sell to both real players (via the web panel) and physical [customer NPCs](Customer-NPCs-Reviews.md). Every business runs on a weekly billing cycle and pays government taxes on every sale made within taxed territory.

## Registering a business

Registration happens **only through the web panel** (`/user/business` → Register), not an in-game command:

- One business per player.
- Costs a one-time `registrationFeeCents` (default **$500**), deducted from your *personal* balance (the business has no funds yet).
- On success you receive a **Business Sign** item — delivered directly, or mailed to your [offline mailbox](Economy-Market.md) if you're not logged in.
- The business starts `ACTIVE`, empty, with default hours `9:00–21:00`, and 7 real days until its first bill.

## The Sell Barrel

A real 27-slot chest-like container (`SellBarrelBlockEntity`) — right-click to open it like a chest, or feed it with a hopper.

- Whoever places it becomes its recorded owner and it's registered to that owner's business automatically (no ownership check against an actual business at placement time).
- **This is the only place a business's storefront actually sells from.** Its physical contents (summed across every barrel the business owns) are the real, live stock — separate from the business's *virtual* inventory (see Manufacturing, below).
- Set a public sale price per item via the dashboard (`Business.listingPricesCents`); an item can be listed at a price even with 0 physical stock, showing "0 in stock" until you shelve some.
- Enabling the storefront (`listed = true`) requires owning at least one Sell Barrel.
- Costs `sellBarrelDailyFeeCents` (default **$1/day per barrel**), folded into the weekly bill.

## The Business Sign

Placed for free on registration (and re-claimable free any time if lost). Placing it:

- Spawns a floating text display above it showing the business name.
- Right-clicking it (no GUI, just chat) shows the business name, hours, live open/closed status, and star rating.
- Grants **permanent discoverability** to customer NPCs — once placed, `hasSignPlaced` never resets, even if the sign block is later destroyed.
- Costs `signFeeCentsPerMonth` (default **$1/month**, prorated weekly) as part of the weekly bill.

Discoverability matters: a business invisible to customer NPCs (no sign, no active advert, no queue point inside a government public square) gets **zero** physical customer traffic even if fully stocked and listed.

## Manufacturing

Admins (only) define server-wide recipes from the admin panel: up to 3 input item types → 1 output item + quantity, each recipe gets an ID. Any business can run any recipe as long as it has the inputs.

- Manufacturing consumes inputs from and adds output to the business's **virtual inventory** (`Business.inventory`), not physical barrels.
- To actually sell manufactured goods, the owner must "Withdraw" them to their personal inventory and physically carry (or hopper) them into a Sell Barrel.
- This two-step flow (buy/manufacture virtually → physically stock the storefront) is deliberate: it separates the wholesale economy from the retail storefront.

The business's virtual inventory can also be stocked by buying raw items from the shared [market](Economy-Market.md) at wholesale — something regular players (outside a business) can't do; the web panel deliberately only lets ordinary players *sell* to the market, not buy from it.

## Upgrades and licenses

**One-time storefront upgrades**, paid from the business's own balance:

| Upgrade | Default cost | Effect |
|---|---|---|
| Awning | $50 | +0.15 flat leniency on every review. |
| Price Board | $50 | Cuts price-sensitivity in reviews by 30%. |
| Loyalty Card | $100 | Every 5th completed sale gets +0.3 extra review leniency. |
| Guard Dog | $150 | Cuts theft chance by 80%. |

**Government licenses:** if any government taxing your business (i.e., any city/country whose territory contains one of your Sell Barrels) requires a license fee, you must buy it or lose **all** customer NPC traffic — a hard gate, separate from tax. License fees go straight to that government's treasury.

**Government-side actions toward a business** (not bought by the business, but affecting it): an **endorsement** (government pays to add a permanent reputation badge + a big temporary visibility boost), **state affiliation** (a government official designates the business for a permanent profit-share cut of every sale), a **subsidy** (direct treasury payment to the owner), or an **audit** (a risky action that can fine the business a percentage of recent revenue if it "catches" tax dodging).

## Hours and closing

Set `openHour`/`closeHour` (24-hour, wraps past midnight if `open > close`) and a manual "closed for now" toggle from the dashboard. Hours use the **server machine's real-world clock**, not the in-game day/night cycle. A closed business spawns no new customer NPCs, and any NPC already en route or waiting immediately abandons the trip with a `CLOSED` review the moment the shop closes.

## Advertising

Buy a timed advert (default **$10** for **24 hours** of discoverability) if you haven't placed a sign yet, or want extra visibility. Advertising also happens for free: an Influencer-trait customer whose purchase earns ≥3.5★ automatically triggers a free advert, and a government endorsement grants an even bigger one at the government's expense.

## Employee NPC

A purely cosmetic villager-model NPC that stands at the counter — it has no movement AI and does nothing when right-clicked itself. Its *real* function is separate: while `employeeHired` is on (default **$20/week**), the business auto-serves any customer NPC that reaches the front of the queue, without the owner needing to be online or manually accept/decline. It only serves customers still waiting — if the owner already opened a manual dialogue with a customer, the employee leaves that one alone.

## Customer queue points

`/business queue add` (at your position, facing the counter) / `/business queue remove` (removes the nearest one) — you must own the business and be standing on its claim. Each queue point independently holds up to `customerQueueLineCapacity` (default 6) waiting customers; a business needs at least one queue point (and one Sell Barrel) to receive any customer traffic at all. See [Customer NPCs & Reviews](Customer-NPCs-Reviews.md) for how the queue actually behaves.

## Billing and missed payments

Every business is billed weekly (`weeklyFeeCents` + barrel fees + sign fee + employee wage if hired). If the balance can't cover it, one of three admin-configured policies kicks in:

- **Suspend** — frozen immediately (blocks buying/selling/manufacturing/storefront purchases/customer NPCs) until reactivated.
- **Dissolve** — the business is deleted outright; any leftover balance is refunded to the owner personally.
- **Grace then suspend** — a configurable grace period before falling back to Suspend.

Reactivating pays the current amount due immediately and resumes normal weekly billing.

**Sales tax** is separate from billing — deducted per-transaction, not billed weekly. Every government whose territory contains one of your barrels takes its own independent cut (possibly several governments' cuts on the same sale, if barrels sit in overlapping jurisdictions), with a foreign-owner surcharge, a sanction surcharge, an allied-government "only the larger cut applies" rule, and a discount for businesses with strong recent reviews. See [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md).

## Reviews and reputation

Both NPC visits and completed web-panel purchases (one review per order) generate reviews on your storefront. Owners can post one public reply per review; only admins can delete a review. See [Customer NPCs & Reviews](Customer-NPCs-Reviews.md) for the full scoring/text-generation system, and [Web Panel](Web-Panel.md) for the directory/leaderboard badges (Top Rated, Most Popular, Fastest Growing) computed from this data.

## Theft

If enabled by an admin, unguarded storefronts (no land claim covering the barrels) can occasionally be robbed instead of legitimately sold from — a random stocked item, in a random quantity, taken with no payment. Guard Dog cuts this risk by 80%; being in a "trade war zone" (a sanctioned relationship between relevant governments) triples it.

## Commands and configuration

See [Commands](Commands.md) for `/business fee|policy|gracedays|queue`, and [Configuration](Configuration.md) for the full list of admin-tunable costs, intervals, and toggles (most business settings live in runtime data editable from the admin web panel, not the Forge config file).
