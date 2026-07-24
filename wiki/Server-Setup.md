# Setting Up Your Server

ServerMod is feature-complete out of the box, but several systems are deliberately **off or empty by default** until an admin configures them. This page is a step-by-step setup guide — install, first boot, the checklist of things you must configure before players can use everything, and a verification pass.

## 1. Prerequisites and installation

- **Minecraft 1.20.1**, **Minecraft Forge 47+**, running as a dedicated (or LAN) server.
- Drop the mod jar into your server's `mods/` folder like any other Forge mod. No additional dependencies or plugins are required — the web panel is built into the mod itself.
- Start the server once to generate its config file, then stop it before making changes (some Forge config values are read at load, not live).

## 2. Before you generate the world: seed the market

`marketSeedPrices` in the Forge config (`config/servermod-common.toml`, under the `COMMON` config type) is a list of `"item=price"` entries used to create the market's **base prices** — but only the very first time the world's market data is created. Once a world exists, adding/removing/re-pricing items is done live from the admin web panel instead, and this config list is ignored.

**If you want a different starting item list than the default** (diamond, emerald, gold ingot, iron ingot, coal), edit `marketSeedPrices` *before* first launching into a fresh world. If you've already generated a world with the defaults, don't worry — you can add/remove items and change prices from the admin panel at any time; you just can't re-trigger the initial seed.

See [Configuration](Configuration.md) for the full config reference.

## 3. Start the server and reach the web panel

By default the web panel starts automatically, bound to `127.0.0.1:8080` (localhost only). If you're running the server on the same machine you're browsing from, open `http://127.0.0.1:8080/` once the server has fully started.

If you're on a **remote/hosted server**, you have two options:
- **SSH tunnel** (recommended, no config changes needed): `ssh -L 8080:127.0.0.1:8080 youruser@yourserver`, then browse to `http://127.0.0.1:8080/` locally. The panel never leaves localhost on the server side.
- **Widen the bind address** (`webServerBindAddress = "0.0.0.0"` in config) if you need direct browser access without a tunnel. **Do this only behind a TLS-terminating reverse proxy** (nginx/Caddy/etc.) — the panel itself is plain HTTP with no CSRF protection. See the security notes in [Web Panel](Web-Panel.md) before doing this.

**Logging in as admin:** choose "Admin login" on the panel — a one-time code gets sent in chat to every currently **online server operator**. So the very first admin session requires you to be logged into the game as an op at the same time you're opening the panel. Have `/op <yourname>` already run (standard vanilla), join the game, then request the admin login code.

## 4. Required setup: claim shovel tiers

**Land, City, and Country claim shovels have no tiers at all by default.** Until you add at least one tier per type, players cannot buy a shovel of that type — `/land buy land|city|country <tier>` will have nothing to select.

From the admin panel (Land admin section) or in-game:
```
/land admin tier add land <name> <price> <maxCharges> <maxArea>
/land admin tier add city <name> <price> <maxCharges> <maxArea>
/land admin tier add country <name> <price> <maxCharges> <maxArea>
```
- `price` — one-time cost to buy the shovel.
- `maxCharges` — how many separate claim selections the shovel can make before it breaks (each accepted *or* declined confirmation spends one charge).
- `maxArea` — the largest single claim selection allowed per use, in blocks.

A sensible starting point is one cheap, small-area, single/low-charge tier per type so you can test the flow, plus a pricier, larger, multi-charge tier once you're happy with the balance. You can add and remove tiers at any time — existing shovels already in players' inventories keep whatever tier they were bought at.

Also set the flat rate every country pays the server admin: `/land admin rate country <amount>` (defaults to essentially free — 1 cent/block/week — until you change it).

## 5. Required setup: turn on customer NPCs

**Physical customer NPCs, ambient wanderers, and theft are all switched off by default** at the world level (`BusinessData.physicalNpcEnabled`, `wanderersEnabled`, `theftEnabled` all default `false`) — a business can be perfectly registered, stocked, and listed and still see zero foot traffic until you flip this on.

From the admin panel's business settings form (`/admin` → Business settings), enable:
- **Physical customer NPCs** — the master switch. Without this, nobody's storefront ever gets a real customer, no matter what individual businesses do.
- **Ambient wanderers** (optional) — idle NPCs that roam near online players and can discover businesses by spotting a sign, on top of the regular scheduled spawns.
- **Theft** (optional) — adds a small robbery risk to unprotected storefronts; leave off for a gentler economy, on for more risk/reward.

While you're in that form, review (and adjust for your server's economy scale) the registration fee, weekly fee, barrel fee, missed-payment policy, sign fee, advert cost, and upgrade costs — the built-in defaults are reasonable starting points but aren't tuned to any particular server size or currency pace. See [Configuration](Configuration.md) and [Businesses](Businesses.md) for what each one does.

## 6. Decide your missed-payment policies

Both businesses and each territory tier (land/city/country) bill weekly and need a policy for what happens if the bill goes unpaid: **Suspend**, **Dissolve**, or **Grace-then-suspend**. The default for all of them is `Suspend`, which is a reasonably safe, forgiving choice — `Dissolve` is much harsher (the business/claim/government is deleted) and worth reserving for servers that want real economic stakes rather than a casual economy.

Set them from the admin panel, or:
```
/business policy <suspend|dissolve|grace_then_suspend>
/business gracedays <days>
/land admin policy <land|city|country> <suspend|dissolve|grace_then_suspend>
/land admin gracedays <land|city|country> <days>
```

## 7. Optional: government commerce costs

The flat, server-wide costs for government-level actions (festivals, endorsements, audits, public squares and their max radius) live in the same admin panel section as the land tiers. The defaults are usable, but worth a glance if your server's economy runs at a very different scale than the built-in dollar amounts assume.

## 8. Verify everything works

A short smoke test after configuration, ideally with a second (non-op) test account:

1. **Trader Block** — place one, right-click holding an item from your seeded/added market list, confirm you get paid and the item disappears.
2. **Land claim** — buy a Land Claim Shovel (`/land buy land <tier>`), right-click two corners, accept the confirmation, confirm `/land status` shows it.
3. **Business** — log into the web panel as a `User`, register a business, place a Sell Barrel, stock and list an item, and confirm the storefront page shows it.
4. **Customer NPCs** — if you enabled them, add a queue point (`/business queue add`) near your Sell Barrel, wait out one spawn interval, and confirm a customer NPC actually shows up and walks to the queue.
5. **Web panel access** — confirm both the admin login (op-only, in-chat code) and user login (any player, in-chat code) work end to end.
6. **City/Country founding**, if you added tiers for them — buy a shovel, found one, confirm `/land status` reflects it and the government appears in the (fully public, no-login-required) government directory page.

## 9. Ongoing admin workflow

Day to day, almost everything is manageable from the web panel without touching config files or restarting the server — market prices, business settings, recipes, review moderation, land tiers, and government commerce costs are all **runtime, per-world data**, editable live. Only the handful of Forge config values (web server port/bind address, market fluctuation tuning, market seed list, customer-service/review tuning) require editing `config/servermod-common.toml` and a server restart to take effect. See [Configuration](Configuration.md) for exactly which is which.

## See also

- [Configuration](Configuration.md) — every config value, default, and where it lives
- [Web Panel](Web-Panel.md) — full panel walkthrough and security notes
- [Commands](Commands.md) — every admin and player command
- [For Server Owners](For-Server-Owners.md) — the case for running this mod and what to watch as it matures
