# Configuration

Most of ServerMod's tuning knobs live in two places: a Forge config file (`Config.java`, world-independent, edited on disk or via server restart) and **runtime settings stored in world data** that admins can change live from the web panel without restarting (mainly `BusinessData` and the territory tier/policy system — see [Businesses](Businesses.md), [Land, Cities & Countries](Land-Cities-Countries.md)). This page documents the Forge config file specifically.

## Market

| Option | Default | Range | Purpose |
|---|---|---|---|
| `marketSeedPrices` | `diamond=100, emerald=50, gold_ingot=25, iron_ingot=10, coal=2` | — | Items tradeable at the [Trader Block / web panel](Economy-Market.md) and their starting base price. Only used to seed a **brand-new world** — after that, base prices are managed at runtime from the admin web panel. Format: `"<item id>=<price>"`. |
| `marketImpactRate` | `0.02` | 0.0–1.0 | How much a single buy/sell moves an item's current price, per unit traded (scaled by √quantity). |
| `marketReversionRate` | `0.05` | 0.0–1.0 | Fraction of the gap between current and base price closed on every reversion tick. |
| `marketReversionIntervalTicks` | `6000` (5 min) | 20–max | How often, in server ticks, prices drift back toward base price. |
| `marketMinMultiplier` | `0.1` | 0.01–1.0 | Current price can never fall below this fraction of base price. |
| `marketMaxMultiplier` | `10.0` | 1.0–1000.0 | Current price can never rise above this multiple of base price. |

## Web panel

| Option | Default | Range | Purpose |
|---|---|---|---|
| `webServerEnabled` | `true` | — | Whether the built-in HTTP admin/user web panel starts with the server. |
| `webServerPort` | `8080` | 1–65535 | TCP port the web panel listens on. |
| `webServerBindAddress` | `"127.0.0.1"` | — | Bind address. `127.0.0.1` = localhost-only (default, safe). `0.0.0.0` opens it to the network/internet. **The panel is plain HTTP, not HTTPS** — only widen this on a trusted network or behind a TLS-terminating reverse proxy. |

## Customer NPCs & reviews

| Option | Default | Range | Purpose |
|---|---|---|---|
| `customerServiceTimeoutTicks` | `6000` (5 min) | 20–max | How long a customer NPC waits at the front of the queue before giving up and leaving a bad review. |
| `customerQueueLineCapacity` | `6` | 1–64 | Max customer NPCs waiting at once, per queue point. |
| `reviewRecencyWindowHours` | `72` | 1–8760 | Only reviews within this many hours count toward a business's spawn-rate/basket-size score. |
| `customerMinRateMultiplier` | `0.4` | 0.05–1.0 | Spawn-chance/basket-size multiplier a business's poor recent reviews (0★) can drag it down to. |
| `customerMaxRateMultiplier` | `1.6` | 1.0–5.0 | Spawn-chance/basket-size multiplier good recent reviews (5★) can boost it up to. |

See [Customer NPCs & Reviews](Customer-NPCs-Reviews.md) for exactly how these feed the spawn/review formulas, and note that most of the *day-to-day* customer NPC tuning (spawn intervals, wander settings, theft, base spend) actually lives in per-world `BusinessData`, adjustable from the admin web panel, not in this file.

## Leftover template fields

`Config.java` also still defines four fields inherited from the Forge MDK example template that are **not used by any ServerMod feature**: `logDirtBlock`, `magicNumber`, `magicNumberIntroduction`, and `items` (a list of items to log on startup). They're vestigial and safe to ignore.

## Where other settings live

- **Business fees, missed-payment policy, grace period, barrel fee** — set via `/business fee ...`, `/business policy`, `/business gracedays` (op-only commands, stored in `BusinessData`). See [Commands](Commands.md).
- **Claim shovel tiers, prices, area caps, per-type missed-payment policy** — set via `/land admin tier|policy|gracedays|rate` (op-only). See [Land, Cities & Countries](Land-Cities-Countries.md).
- **Per-business runtime settings** (NPC spawn interval, base spend, wanderers, theft, advert duration) — set from the admin web panel, stored in `BusinessData`. See [Businesses](Businesses.md) and [Customer NPCs & Reviews](Customer-NPCs-Reviews.md).
- **Per-city/country tax rates, sales tax, victim share, laws** — set via `/land city ...` / `/land country ...` subcommands by government officials. See [Land, Cities & Countries](Land-Cities-Countries.md) and [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md).
