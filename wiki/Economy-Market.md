# Economy & Market

Every player has a persistent money balance, and every tradeable item has a live, fluctuating price. This page covers the core economy engine that everything else in the mod (businesses, customer NPCs, territory rent, taxes) spends and earns from.

## Player balances

- Stored as **integer cents** (never floating-point dollars) specifically to avoid rounding drift across thousands of buy/sell/reversion operations. `Money.format(cents)` renders it as `$12.34`; `Money.toCents(dollars)` converts input the other way.
- Persisted in `MoneyData`, a per-world `SavedData` keyed by player UUID — balances survive restarts and work for **offline players** (admin commands, trades, and business sales can all target someone who isn't logged in).
- A balance can never go negative — every add/subtract/set operation clamps to a minimum of 0.
- Every time a balance changes, the server pushes a `MoneySyncPacket` to that player (if online) so the [HUD overlay](Client-HUD.md) stays live. You also get a fresh sync on login.
- Manage it with `/money get`, and admins with `/money add|remove|set` — see [Commands](Commands.md).

## The fluctuating market

The market (`MarketData`) works, in the code's own words, "like a simple crypto market where each item is its own coin." Every tracked item has two numbers:

- **Base price** — the admin-set "fair value," changed only via the admin web panel (or the initial world-seed config).
- **Current price** — the live trading price that moves with every transaction and drifts back toward base price over time.

**Price impact on trade:**
```
delta = marketImpactRate × √quantity
buying:  currentPrice × (1 + delta)
selling: currentPrice × max(0.01, 1 − delta)
```
Impact scales with the *square root* of quantity traded, so dumping 100 items at once moves the price only 10× as much as trading 1 — not 100×. Buying pushes the price up; selling pushes it down.

**Reversion (drift back to base):** every `marketReversionIntervalTicks` (default 5 minutes), each tracked item's current price closes a fixed fraction of the gap to its base price:
```
currentPrice += (basePrice − currentPrice) × marketReversionRate
```
This is exponential-decay convergence — it approaches base price asymptotically rather than snapping to it, so a manipulated price recovers gradually.

**Bounds:** current price is always clamped between `marketMinMultiplier × base` (default 0.1×) and `marketMaxMultiplier × base` (default 10×), enforced after every trade and every reversion tick.

**Admin control:** admins set/change base prices and add/remove tradeable items from the live web panel. Changing a base price doesn't snap the current price to it — the market drifts there naturally over subsequent reversion ticks. Only items with a market entry can be sold at the Trader Block, Auto-Seller, or web sell page at all; anything else is refused outright.

See [Configuration](Configuration.md) for every market tuning value.

## Trader Block

Right-click it empty-handed to see your balance. Right-click it while holding an item to **instantly sell your entire held stack** at the item's current market price:

- Only items with a market entry are sellable — everything else is refused ("You can't sell that here").
- Unit price is the current market price in cents, rounded **down**; if that rounds to 0 (a very depressed price), the sale is refused rather than paying nothing.
- The whole stack is sold in one transaction, crediting your balance and pushing that item's current price down via the sell-impact formula above.
- Anyone can use any Trader Block — there's no ownership or cooldown.

## Auto-Seller

A block-entity machine that exposes an item-handler capability, so **only hoppers (or hopper-like transport)** can feed it — there's no GUI to manually deposit into it.

- Whoever **places** the Auto-Seller becomes its permanent owner; right-clicking it just shows an info message, it doesn't sell anything itself.
- The instant a hopper successfully inserts an item, it's sold: price is computed the same way as the Trader Block (floored current price, refused if 0), the owner is credited, and the market's sell-impact is applied. Nothing is ever stored in the machine — there's no accumulation slot and no internal timer; throughput is bounded purely by vanilla hopper transfer cadence (every 8 ticks per hopper).
- Items with no market entry, or a current price of 0 cents, are rejected by the hopper's insert check — so a hopper feeding a mixed input can route unsellable items elsewhere instead of losing them.
- An Auto-Seller placed by a player with no attached ownership context (or if `ownerId` is unset) sells nothing at all.
- Payouts always go to the **original placer's** UUID — there's no re-claim/transfer mechanism if the machine changes hands.

## Player-to-player trading

`/trade money <player> <amount>` and `/trade item <player> <item> <amount>` — see [Commands](Commands.md) for exact syntax. Both work with offline targets:

- `/trade money` checks your balance first and refuses the transfer if you can't afford it (unlike the admin `/money remove`, which just clamps to 0 instead of erroring).
- `/trade item` pulls the item from your real inventory (main inventory + offhand) and either hands it directly to the target if they're online, or queues it in their **mailbox** if they're offline.

## Offline mailbox

`MailboxData` holds items intended for a player who isn't currently loaded — "so they can't be placed straight into an inventory that isn't loaded." It delivers everything pending the moment you log back in (alongside your balance sync), dropping items at your feet instead of losing them if your inventory happens to be full.

Items get routed through the mailbox from several places, not just player trades:
- A Business Sign reward when registering (or re-claiming a sign) while offline.
- Withdrawing personal stock from a business's barrels while offline.
- **Buying from another player's business storefront** through the web panel while offline — see [Businesses](Businesses.md).
- `/trade item` targeting an offline player.
- Sending an item to another player from the web panel's user dashboard.

## Notes and edge cases

- Price rounding always favors the system: sales round the unit price **down**, and a business buying stock from the market rounds the price it pays **up** — never the other way around.
- A business stocking its shelves by buying from the market (`applyBuy`) and a Trader Block sale of the same item (`applySell`) push that item's price in opposite directions — they're mechanically opposing forces on the same market.
- The market sync packet only flows server → client; the HUD is a pure reflection of whatever the server last pushed, with no client-side prediction.
