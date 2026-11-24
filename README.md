<div align="center">

# SentinelAD

### Every item has one place. Copies do not survive.

An anti-duplication plugin by **azicoo** for Paper servers: a global item ledger that catches duplicated gear wherever it ends up, and conservation checks that take back stackable valuables created from nothing.

[![Build](https://github.com/dilmuratovv/SentinelAD/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dilmuratovv/SentinelAD/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/dilmuratovv/SentinelAD?color=ec4899&label=release)](https://github.com/dilmuratovv/SentinelAD/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/paper-1.21%20%E2%80%93%2026.3-0ea5e9.svg)](#compatibility)
[![Author](https://img.shields.io/badge/author-azicoo-8b5cf6.svg)](https://github.com/dilmuratovv/SentinelAD)

[Download](https://github.com/dilmuratovv/SentinelAD/releases) · [How it works](#how-it-works) · [Configuration](#configuration) · [Commands](#commands) · [Compatibility](#compatibility)

</div>

---

## About

Dupe exploits come and go with every Minecraft and plugin update. SentinelAD does not chase individual exploits: it watches the **result**. Every unstackable item gets a unique seal, the plugin remembers where each seal is, and when the same seal shows up in two places at once the new copy is removed and the original stays.

**One JAR for every supported Paper version** (1.21 to 26.3). Players can join with any client version through ViaVersion/ViaBackwards; everything happens on the server.

It is part of the [Sentinel suite](#sentinel-suite).

**Created and maintained by azicoo.**

## Sentinel suite

Five plugins by **azicoo**, each with its own repository and release. Every one works on its own.

| Plugin | What it does |
|---|---|
| [SentinelAC](https://github.com/dilmuratovv/SentinelAC) | Anticheat: prediction-based movement, combat and packet checks. |
| [SentinelAD](https://github.com/dilmuratovv/SentinelAD) | Anti-dupe: a global item ledger that removes duplicated items. |
| [SentinelAE](https://github.com/dilmuratovv/SentinelAE) | Anti-ESP: hidden players and armor stands are never sent to the client. |
| [SentinelAX-ray](https://github.com/dilmuratovv/SentinelAX-ray) | Anti-xray: Paper's native engine set up per world, plus mining analysis. |
| [SentinelGuard](https://github.com/dilmuratovv/SentinelGuard) | Moderation: bans, mutes, history, `/sus`; collects the flags of AC, AD and AX-ray. |

Together: SentinelGuard adds the findings of SentinelAC, SentinelAD and SentinelAX-ray to `/sus`, and `/sg status` shows the state of SentinelAE and SentinelAX-ray. All five are GPL-3.0 and run on Paper 1.21 to 26.3 with one JAR each (SentinelGuard also has a Velocity JAR).

## Features

| Area | What SentinelAD provides |
|---|---|
| **Global ledger** | Weapons, armor, tools, shulker boxes and every other unstackable item get a unique seal the first time they are seen. |
| **Everywhere** | Player inventories (also the crafting grid, anvil and other work slots), ender chests, every block that stores items (chests, furnaces, crafters, jukeboxes, lecterns, chiseled bookshelves, decorated pots...), ground items, item frames, armor stands, storage entities, and what mobs hold or wear (allays, zombies, foxes, horse armor) — including items inside shulker boxes and bundles. |
| **Safe removal** | A duplicate is re-checked after a short delay, only the new copy is removed, and it is backed up: `/sad restore <id>` gives it back. A copy picked up from another player's drop is blamed on the player who dropped it, not the receiver; a duplicated shulker box cannot be placed as a block. |
| **Stackables** | While a container is open, watched valuables (diamonds, netherite, totems...) in player + container must not grow; items created from nothing are taken back (backed up). |
| **Event blocks** | Sealed copies, oversized stacks, breaking an open shulker, falling-block portals and storage entities going through portals. |
| **Other plugins** | `/give`, `/kit`, `/item`, clone, item-editor and rollback commands never cause a removal — only a staff alert. A command must exist and be allowed for the player, so typing `/i` is no excuse. |
| **Rollback aware** | A world restored from a backup is detected on start; item locations are rebuilt automatically. |
| **Light** | A per-tick scan budget; 60 bots clicking ~2,400 times per second kept the tick around 5–6 ms in testing. Buckets, potions, stews and maps are not sealed (`ledger.exclude-trivial`), and saving copies only references on the main thread. |

## How it works

### 1. The ledger

Every unstackable item gets a serial stored in its `custom_data` the first time SentinelAD sees it. After anything changes (a click, a pickup, a hopper, a chunk load...) the affected holders are scanned and the ledger records where each serial was last seen (`ledger.dat`, kept across restarts).

When a serial is found in a second holder, SentinelAD checks both places live. If the old place no longer holds it, the item simply moved. If both still hold it after `confirm-delay-ticks` (2 s by default, so short-lived states of other plugins pass), it is a duplicate: the **newer copy is removed** and saved to `plugins/SentinelAD/removed/`.

- Creative players and players with `sentinelad.bypass` are never stripped; their copy just gets a new serial.
- If the other place cannot be checked (offline player, unloaded chunk), nothing is removed — only a 60% alert.

### 2. Stackable items

Stackables cannot carry a serial, so they are protected by **conservation**: while a player has a chest, barrel, shulker box, hopper, dropper, dispenser or ender chest open, the watched items in player + container must not grow, except by pickups and hopper transfers. An unexplained gain is taken back and backed up (`stackables.on-gain: remove`), or only reported (`flag`).

### 3. Item commands

Plugins can create items or copies of sealed items through commands, which looks exactly like a dupe. A finding that follows an item command (`/give`, `/kit`, `/item`, clone, item-editor and rollback commands, also inside `/execute ... run`) within `item-commands.window-seconds`, or while the container was open, is **not removed** but flagged at 60% (visible in SentinelGuard `/sus`).

- **1.3:** a player's command counts only when it exists and the player may run it. Typing `/i` on a server without such a command, or without permission, excuses nothing.
- A player's own command excuses at most **one** finding per `player-trust-cooldown-minutes`, so spamming `/kit` cannot switch removal off. The copy is checked again later and removed if it is still a duplicate.
- Console, RCON, command blocks and staff (op or `sentinelad.bypass`) are trusted, but **only for the players the command names** (1.3): a vote plugin running `give Alice ...` does not hide Bob's dupe. A selector (`@a`) or a rollback tool (`/co rollback`, `/restoreinv`...) excuses everyone. A copy made that way is re-sealed as an item of its own.

Add your own kit and clone commands to `item-commands.commands`.

### 4. World rollbacks (new in 1.2)

`ledger.dat` stores each world's game time (not affected by `/time set`). A world that loads with an older time was restored from a backup or rolled back; SentinelAD logs it and rebuilds item locations instead of reporting false duplicates. Worlds loaded later (Multiverse and similar) are checked too.

## Installation

1. Download `SentinelAD-<version>-paper.jar` from [Releases](https://github.com/dilmuratovv/SentinelAD/releases).
2. Put it in `plugins/` and restart.

Everything is on by default. Staff with `sentinelad.alerts` (op) see alerts in chat; every finding is also logged to `plugins/SentinelAD/logs/`.

## Configuration

`plugins/SentinelAD/config.yml`, reload with `/sad reload`.

| Key | Purpose |
|---|---|
| `ledger.enabled`, `auto-seal`, `auto-seal-containers` | The ledger and automatic sealing. |
| `ledger.on-duplicate` | `remove` (default) or `flag`. |
| `ledger.confirm-delay-ticks`, `click-debounce-ticks`, `scan-budget-ms` | Accuracy and cost. |
| `ledger.exclude-materials` | Materials that are never sealed. |
| `stackables.enabled`, `on-gain`, `min-gain`, `materials` | Stackable protection (`"*"` watches every stackable). |
| `item-commands.enabled`, `window-seconds`, `player-trust-cooldown-minutes`, `commands` | Commands whose items are never removed. |
| `protection.*` | Event blocks: `sealed-duplicates`, `open-shulker`, `falling-block-portals`, `oversized-stacks`, `storage-entity-portals`, `ignore-creative`. |
| `escalation.*` | Optional console commands for repeat offenders (`threshold` serious findings within `window-minutes`). |

## Commands

**Main command:** `/sad` (alias `/sentinelad`)

| Command | Purpose | Permission |
|---|---|---|
| `/sad info` · `suite` · `help` | Status, other Sentinel plugins, help. | `sentinelad.info` · `.suite` · `.help` |
| `/sad inspect` | Where the held item's seal was last seen. | `sentinelad.inspect` |
| `/sad seal` | Seal the held item manually. | `sentinelad.seal` |
| `/sad ledger [stats\|reset]` | Ledger statistics, or forget all locations. | `sentinelad.ledger` |
| `/sad restore <id>` | Give back an item SentinelAD removed. | `sentinelad.restore` |
| `/sad recent [n]` | Latest findings. | `sentinelad.recent` |
| `/sad reload` | Reload `config.yml`. | `sentinelad.reload` |
| `/sad testflag <player>` | Send a test alert (never punishes). | `sentinelad.testflag` |

`sentinelad.admin` (op) grants all of the above. `sentinelad.bypass` exempts a player from blocks and removals (nobody has it by default).

## API

`uz.sentinel.ad.api.DupeFlagEvent` (not cancellable): `getPlayerId`, `getPlayerName`, `getTypeId` (`ledger-mismatch`, `stack-gain`, `uuid-duplicate`, ...), `getConfidence`, `getDetail`, `isTest`. Findings without a responsible player (for example a copy inside a chest) are logged and sent to staff, but fire no event.

## Compatibility

| Platform | File | Supported versions | Java |
|---|---|---|---|
| **Paper** | `SentinelAD-<version>-paper.jar` | 1.21 – 1.21.11, 26.1.1, 26.1.2, 26.2, 26.3 | 21 on 1.21.x, 25 on 26.x |

The JAR is compiled against the oldest supported API (Paper 1.21); every class and method it uses was checked to exist on each supported version. Tested on real Paper servers with ViaVersion/ViaBackwards and clients from 1.9.4 to 26.1. Other forks, Folia and Paper before 1.21 are not supported; 1.8 clients through ViaRewind are not tested.

### Good to know

- Stackable gains outside an open container (a `/give`, an item on the ground, piston/TNT machines) are not detected: conservation needs a boundary. Paper fixes the classic piston/TNT/rail/carpet dupes; SentinelAD warns at startup when `config/paper-global.yml` switches them back on (`unsupported-settings`).
- A copy in an offline player's inventory or an unloaded chunk is reported at 60% at once and checked again when it loads (1.3); the ledger is per server, so a copy carried to another server (HuskSync, a network) is not compared.
- A plugin that hands out items **without a command** (a GUI shop, an API call) while a container is open can still trigger a stackable removal; use `stackables.on-gain: flag` there. The same goes for plugins that copy sealed items without a command: use `ledger.on-duplicate: flag`.
- Do not restore `plugins/SentinelAD/` together with a world backup, or run `/sad ledger reset` after doing so; otherwise the rollback cannot be seen.
- Sealing adds `custom_data` to items; plugins that compare item NBT exactly may see sealed items as different (`ledger.auto-seal: false`).

## Build from source

You need **Maven 3.9+** and **JDK 21 or newer**.

```sh
git clone https://github.com/dilmuratovv/SentinelAD.git
cd SentinelAD
mvn -B package
```

The JAR appears in `plugin/target/SentinelAD-<version>-paper.jar`.

## Releases

Each release contains one JAR signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed) and a `SHA256SUMS.txt`. Check with `jarsigner -verify -certs SentinelAD-<version>-paper.jar`.

## Credits & license

**SentinelAD — created and maintained by azicoo.**

Distributed under **[GPL-3.0](LICENSE)**. Thanks to the maintainers of Paper.
