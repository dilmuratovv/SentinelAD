SentinelAD 1.3.0: closes the bypasses and blind spots found in a review of 1.2. Paper 1.21 through 26.3 (one JAR, Java 21; Java 25 on 26.x).

**Bypasses closed**
- **Item commands no longer excuse a dupe by just typing them.** Up to 1.2 any player could type `/i` (even where no such command exists, or without permission) and get 10 seconds in which a duplicate was not removed, about six free dupes an hour. Now a player's command counts only when it exists and the player may run it.
- **Console commands are targeted.** A console/RCON/command-block or staff command excuses only the players it names, so a vote or crate plugin running `give Alice ...` no longer hides everyone's dupes. Selectors and rollback tools (`/co rollback`, `/restoreinv`, `/ir`...) still excuse everyone; the copy they make is re-sealed as its own item instead of being removed later.
- Excused findings are flagged at 60% (visible in SentinelGuard `/sus`) instead of 50% (hidden).

**Blind spots covered**
- Copies hidden in a jukebox, lectern, chiseled bookshelf, decorated pot, furnace, brewing stand or crafter (any block that stores items).
- Copies held or worn by mobs (allay, zombie, fox, horse armor...).
- Copies hidden in the crafting grid, anvil, smithing table and other work slots while the view is open.
- A duplicated shulker box cannot be placed as a block (it could be emptied before the item was removed).
- A copy whose other half is in an offline inventory or unloaded chunk is no longer silenced for 10 minutes: it is reported at 60% at once and confirmed and removed as soon as the other place loads.

**Wrong-player fixes**
- Taking diamonds out of a bundle while a chest is open was counted as items from nothing (removed, 90% flag). Bundle and shulker contents are now counted.
- A copy picked up from another player's drop (a trade) is blamed on the player who dropped it, not on the receiver.

**Other**
- Startup warning when `config/paper-global.yml` turns on a dupe or block-break exploit that Paper fixes (`unsupported-settings`), since stackables made that way are not tracked.
- Buckets, potions, stews, maps and book-and-quills are no longer sealed (`ledger.exclude-trivial: true`), and ledger saving only copies references on the main thread.
- `co`, `coreprotect`, `rollback`, `inventoryrollback`, `ir`, `restore` added to the item-command list.

**Validation**
- 36 unit tests (command permission/targeting, trust cooldown, Paper setting audit, ledger store).
- Live test (`tools/dupe_probe.py` with the test-only `tools/dupe-harness` plugin): Paper 26.3 (build 230) + ViaVersion/ViaBackwards 5.12.2, two bot players on 26.1 protocol. The harness makes copies through the API (what a server bug amounts to) and legitimate moves; 17/17 as expected: trivial item not sealed; legit chest move clean; copies in a chest, crafting grid, allay hand and jukebox removed; fake `/i`, forbidden `/give` and a console `give Bob` did not excuse Alice; console `give Alice` re-sealed the copy at 60%; bundle unpack with a chest open clean; diamonds from nothing removed; handed-over copy blamed on the giver, receiver clean; legit handover clean; duplicated shulker placement refused; offline copy reported at 60% and removed after the player rejoined; Paper exploit-setting warning shown.
- Limits of that test: the jukebox interaction and the shulker placement were simulated events, not real client clicks; the dupes are made by the harness, not by real exploits; ledger performance with a very large ledger was not re-measured.

**Upgrade:** replace the JAR and restart. Existing `config.yml` keeps working; new keys use the bundled defaults. Add `co`, `rollback` and similar to your own `item-commands.commands` list if you set one.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs <jar>`. Checksums are in `SHA256SUMS.txt`. License: GPL-3.0.
