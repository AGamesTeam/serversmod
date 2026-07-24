# Command Reference

ServerMod registers two top-level commands: `/money` and `/trade` (plus `/business`) from `ModCommands.java`, and the large `/land` command tree from `TerritoryCommands.java`. Unless noted, permission level 2 means the sender must be a server operator.

Many "prompts" in this mod (accept/decline dialogues, purchase confirmations) are **not GUIs** — they are chat messages with clickable links that silently run one of the commands below. You will rarely need to type these by hand; they're documented here for completeness. See [Client & HUD](Client-HUD.md) for more on this.

---

## `/money` — balance administration

| Command | Permission | Description |
|---|---|---|
| `/money get` | anyone | Reports your own balance. |
| `/money get <player>` | op (2) | Reports another player's balance (online or offline). |
| `/money add <player> <amount>` | op (2) | Adds `<amount>` to a player's balance. Works offline. |
| `/money remove <player> <amount>` | op (2) | Subtracts `<amount>` from a player's balance. |
| `/money set <player> <amount>` | op (2) | Sets a player's balance directly. |

`<amount>` is a double ≥ 0. See [Economy & Market](Economy-Market.md) for how balances work.

## `/trade` — direct player-to-player transfers

| Command | Permission | Description |
|---|---|---|
| `/trade money <player> <amount>` | anyone | Sends money from your own balance to another player. `<amount>` ≥ 0.01. Fails if you lack funds or target yourself. |
| `/trade item <player> <item> <amount>` | anyone | Removes `<amount>` of `<item>` from your inventory and gives it to `<player>`. If the target is offline, the item is queued in their **mailbox** for delivery on next login. |

## `/business` — business & economy administration

| Command | Permission | Description |
|---|---|---|
| `/business fee registration <amount>` | op (2) | Sets the one-time business registration fee. |
| `/business fee weekly <amount>` | op (2) | Sets the recurring weekly business fee. |
| `/business fee barrel <amount>` | op (2) | Sets the Sell Barrel's daily fee (per barrel, per day). |
| `/business policy <policy>` | op (2) | Sets the global missed-payment policy. One of `suspend`, `dissolve`, `grace_then_suspend`. |
| `/business gracedays <days>` | op (2) | Sets the grace period (days) before the missed-payment policy applies. |
| `/business queue add` | business owner | Adds a customer queue point at your position, facing the direction you're looking. Must be standing on your own business's claim. |
| `/business queue remove` | business owner | Removes the nearest queue point belonging to your business. |
| `/business npcaccept <id>` | chat-link only | Accepts a customer NPC's purchase offer. |
| `/business npcdecline <id>` | chat-link only | Declines a customer NPC's purchase offer. |
| `/business npchaggle <id>` | chat-link only | Accepts a haggled (negotiated) price from a customer NPC. |

See [Businesses](Businesses.md) and [Customer NPCs & Reviews](Customer-NPCs-Reviews.md).

## `/land` — claims, cities, countries, law & order

### Claims & shovels

| Command | Permission | Description |
|---|---|---|
| `/land accept <id>` | chat-link only | Confirms a pending land/city/country claim after finishing corner selection with a claim shovel. |
| `/land decline <id>` | chat-link only | Discards a pending claim. |
| `/land name <name>` | shovel holder | Names the city/country claim shovel you're holding, before finishing corner selection. Not needed/valid for Land-type shovels. |
| `/land buy land <tier>` | anyone | Buys a Land Claim Shovel of the given tier for its configured price. |
| `/land buy city <tier>` | anyone | Buys a City Claim Shovel of the given tier. |
| `/land buy country <tier>` | anyone | Buys a Country Claim Shovel of the given tier. |
| `/land status` | anyone | Shows your owned claims (count, total area, suspended count) and any government you manage. |
| `/land reactivate` | claim owner | Reactivates a suspended land claim you're standing on, paying owed rent. |

### Border crossing

| Command | Permission | Description |
|---|---|---|
| `/land border accept` | chat-link only | Accepts a country's laws when crossing into its border. |
| `/land border decline` | chat-link only | Declines the country's laws; teleports you back outside the border. |

### Claim rules & trust (must own the claim you're standing on)

| Command | Permission | Description |
|---|---|---|
| `/land claim rule <permission> <mode>` | claim owner | Sets a permission rule on the current claim. Permissions: `build`, `container_open`, `container_take`, `interact`, `pvp`, `mob_damage`. Modes: `allowed`, `blocked`, `reportable`. |
| `/land claim trust <player> <permission>` | claim owner | Grants a trusted player a specific permission on the claim. |
| `/land claim untrust <player>` | claim owner | Removes a player's trust on the claim. |

### Reports

| Command | Permission | Description |
|---|---|---|
| `/land report city <gov> <player> <permission> <reason>` | anyone | Files a report against a player to a named city government (only valid where the claim rule is `reportable`). |
| `/land report country <gov> <player> <permission> <reason>` | anyone | Same, against a country government. |
| `/land reports city <gov>` | `MANAGE_JAILS` | Lists a city's pending reports. |
| `/land reports country <gov>` | `MANAGE_JAILS` | Lists a country's pending reports. |
| `/land report resolve <id> <fine> <cell> <minutes> <quota>` | `MANAGE_JAILS` | Resolves a report: fines and optionally jails the offender. `<cell>` = `none` for no jailing. |
| `/land report deny <id>` | `MANAGE_JAILS` | Dismisses a pending report. |
| `/land release <player>` | op (2) | Pardons a player currently serving a jail sentence. |

### City government — `/land city <name> ...`

| Subcommand | Permission | Description |
|---|---|---|
| `rate <amount>` | `MANAGE_RATES` | Sets the city's per-block/week claim rate. |
| `reactivate` | `MANAGE_RATES` | Reactivates a suspended city, paying owed rent. |
| `victimshare <percent>` | `MANAGE_JAILS` | Sets the % of a fine paid to the report's victim. |
| `salestax <percent>` | `MANAGE_RATES` | Sets sales tax % taken from Sell Barrel transactions in the city. |
| `jail add <cell> <radius> <maxCapacity>` | `MANAGE_JAILS` | Adds a jail cell centered on your position. |
| `jail remove <cell>` | `MANAGE_JAILS` | Removes a named jail cell. |
| `officialaccess <permission> <access>` | `MANAGE_LAND_RULES` | Toggles whether city officials automatically get a given claim permission on land within the city. |
| `official grant <player> <permission>` | `MANAGE_OFFICIALS` | Grants a player a government permission. |
| `official revoke <player>` | `MANAGE_OFFICIALS` | Removes a player as official. |
| `square add <radius>` | `MANAGE_COMMERCE` | Founds a public square at your position (costs treasury funds). |
| `square remove <index>` | `MANAGE_COMMERCE` | Removes a public square by index. |
| `square list` | `MANAGE_COMMERCE` | Lists the city's public squares. |

### Country government — `/land country <name> ...`

Same subcommands as city (`rate`, `reactivate`, `victimshare`, `salestax`, `jail add/remove`, `official grant/revoke`, `square add/remove/list`), **plus** country-only law management:

| Subcommand | Permission | Description |
|---|---|---|
| `law enable <law> <enabled>` | `MANAGE_LAWS` | Toggles a country law. Laws: `no_fire`, `no_destruction`, `clothing_required`. |
| `law configure <law> <fine> <cell> <minutes> <quota>` | `MANAGE_LAWS` | Sets the fine/jail sentence for violating a law. |
| `law maxy <y>` | `MANAGE_LAWS` | Sets the Y-level ceiling up to which laws apply. |
| `law zone add <x1> <z1> <x2> <z2>` | `MANAGE_LAWS` | Adds a rectangular zone that overrides the Y-cap. |
| `law zone remove <index>` | `MANAGE_LAWS` | Removes an override zone. |
| `permit grant <player> <law>` | `MANAGE_PERMITS` | Grants a player an exemption from a law. |
| `permit revoke <player> <law>` | `MANAGE_PERMITS` | Revokes an exemption. |

Government permission types: `manage_laws`, `manage_jails`, `manage_permits`, `manage_rates`, `manage_officials`, `manage_land_rules` (plus `manage_commerce` used by public squares).

See [Land, Cities & Countries](Land-Cities-Countries.md) and [Laws, Jail & Diplomacy](Laws-Jail-Diplomacy.md).

### `/land admin` — server-wide territory configuration (op only, permission level 2)

| Subcommand | Description |
|---|---|
| `admin tier add <type> <name> <price> <maxCharges> <maxArea>` | Adds a new claim-shovel tier. `<type>` is `land`, `city`, or `country`. |
| `admin tier remove <type> <name>` | Removes a tier. |
| `admin rate country <amount>` | Sets the country→server per-block/week rate. |
| `admin policy <type> <policy>` | Sets the missed-payment policy per claim type. |
| `admin gracedays <type> <days>` | Sets the grace period (days) per claim type. |
