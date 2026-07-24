# Client & HUD

ServerMod's client-side footprint is intentionally small: one HUD overlay, two NPC renderers that reuse vanilla assets, and no custom in-game GUI screens or keybinds at all. Almost everything you'd expect to be a "screen" is instead a **clickable chat message**.

## Money overlay

`client/MoneyOverlay.java`, registered as a Forge `IGuiOverlay` (`event.registerAboveAll("money_overlay", ...)`).

- Draws your current balance (formatted like `$12.34`) in the **top-right corner** of the screen, 8 pixels from the edge, in light green (`#55FF55`), with a text drop shadow.
- Reads from `client/ClientMoneyData.java`, a simple static cache that is **not persisted** — it starts empty each session and is populated only once the server pushes a sync packet.
- Automatically hides whenever vanilla's "hide GUI" is active (the **F1** key), and if no player is loaded. There is no dedicated toggle or keybind for it beyond that.
- Updated live via the `MoneySyncPacket` network packet (see below) — every time your balance changes server-side (trades, sales, fees, purchases), the server pushes a fresh value and the overlay updates on the next frame.

## No custom GUI screens

The mod has no inventory-style screens, menus, or Forge `Screen` classes of its own (beyond the Sell Barrel's plain 27-slot vanilla-style container, which is just a normal block inventory, not a custom screen). Every interactive "prompt" you'd expect a mod like this to show as a popup is instead delivered as an **in-chat message with clickable buttons** (`ClickEvent.RUN_COMMAND` / `COPY_TO_CLIPBOARD`) that silently invoke a command for you:

| Prompt | Triggered by | Runs |
|---|---|---|
| Customer NPC purchase offer | A customer NPC reaching the counter | `/business npcaccept`, `/business npcdecline`, or `/business npchaggle` |
| Claim confirmation | Finishing a claim shovel's corner selection | `/land accept <id>` / `/land decline <id>` |
| Country border crossing | Walking across a country's border | `/land border accept` / `/land border decline` |
| Web panel login code | Requesting admin panel access | Copies a login code to your clipboard |

See [Commands](Commands.md) for the full list these buttons call under the hood.

## NPC rendering

`client/CustomerNpcRenderer.java` and `client/EmployeeNpcRenderer.java` render the mod's [Customer and Employee NPCs](Customer-NPCs-Reviews.md) using vanilla's villager model and texture. Visually they look like villagers, but their behavior is fully custom (queueing, walking to counters, standing at a shop) rather than vanilla villager AI or trading.

## Keybinds

None. The mod does not register any custom key mappings.

## Networking behind the HUD

`network/NetworkHandler.java` defines a single packet, `MoneySyncPacket` (server → client only, one `int` cents payload), sent via the channel `servermod:main`. It's pushed whenever a balance changes and once on login (alongside mailbox delivery — see [Economy & Market](Economy-Market.md)). There is no client → server custom packet; every player-initiated action goes through ordinary commands instead.
