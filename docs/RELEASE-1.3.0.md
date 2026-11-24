SentinelAD 1.3.0: closes the bypasses and blind spots found in a review of 1.2. Paper 1.21 through 26.3 (one JAR, Java 21; Java 25 on 26.x).

**New: stackable dupes outside an open chest**
Up to 1.2, diamonds, netherite and other stackables were only protected while a container was open. 1.3 covers the three ways stackables are really duplicated on Paper servers:
- **Chunk rollback dupes** (`rollback-guard`). A chunk that does not save after a player took items out of it comes back with them. The watched stackables in every chunk's containers, storage entities, frames and ground items are counted when it unloads (and when the server stops) and again when it loads; nothing can change in an unloaded chunk, so a surplus is a rollback. It is taken out of the chunk (backed up) and the players who took watched items out of that chunk are flagged at 90%.
- **Disconnect dupes** (`offline-check`). Inventory, cursor, crafting grid, open menus and ender chest are counted just after the server saved the leaving player (1 tick and 1 second later, the lower amount) and again on join, before other plugins' join rewards. Items that leave the player after the save (dropped, handed over) are exactly what such a dupe brings back. A surplus is taken back (backed up) and flagged at 90%. Admin/console item commands that named the player while offline (OpenInv, rollbacks) excuse it; HuskSync and other cross-server sync plugins switch the check off automatically.
- **Plugin storage menus** (`stackables.storage-menus`). Backpacks and player vaults are checked like chests (items may only move between the menu and the player); shops, auction houses and crates are not listed and keep handing out items.

**Bypasses closed**
- **Item commands no longer excuse a dupe by just typing them.** Up to 1.2 any player could type `/i` (even where no such command exists, or without permission) and get 10 seconds in which a duplicate was not removed, about six free dupes an hour. Now a player's command counts only when it exists and the player may run it.
- **Console commands are targeted.** A console/RCON/command-block or staff command excuses only the players it names, so a vote or crate plugin running `give Alice ...` no longer hides everyone's dupes. Selectors and rollback tools (`/co rollback`, `/restoreinv`, `/ir`...) still excuse everyone; the copy they make is re-sealed as its own item instead of being removed later.
- Excused findings are flagged at 60% (visible in SentinelGuard `/sus`) instead of 50% (hidden).

**Blind spots covered**
- Copies hidden in a jukebox, lectern, chiseled bookshelf, decorated pot, furnace, brewing stand or crafter (any block that stores items).
- Copies held or worn by mobs (allay, zombie, fox, horse armor...).
- Copies hidden in the crafting grid, anvil, smithing table and other work slots while the view is open.
- A duplicated shulker box cannot be placed as a block (it could be emptied before the item was removed).
- A placed shulker box keeps its seal: the game drops an item's custom data when the box is placed, so placing and breaking it gave it a new seal (and could launder a copy). The seal is now kept on the block and given back to the dropped item, whether the box is broken by hand, an explosion or a piston (found with a real client).
- A copy whose other half is in an offline inventory or unloaded chunk is no longer silenced for 10 minutes: it is reported at 60% at once and confirmed and removed as soon as the other place loads.

**Wrong-player fixes**
- Taking diamonds out of a bundle while a chest is open was counted as items from nothing (removed, 90% flag). Bundle and shulker contents are now counted.
- A copy picked up from another player's drop (a trade) is blamed on the player who dropped it, and the giver's copy is the one removed: the receiver keeps what they traded for.
- Plugin menus that look like an anvil or crafting table (rename and text-input GUIs) and show a copy of the player's item are not counted as a duplicate.
- Mob gear (every skeleton's bow, zombie weapons) is no longer sealed; a copy put on a mob is still found because it is already sealed.
- A stackable gain inside a bundle or shulker box is reported as not removed instead of "(removed)".

**Other**
- Startup warning when `config/paper-global.yml` turns on a dupe or block-break exploit that Paper fixes (`unsupported-settings`), since stackables made that way are not tracked.
- Buckets, potions, stews, maps and book-and-quills are no longer sealed (`ledger.exclude-trivial: true`), and ledger saving only copies references on the main thread.
- `co`, `coreprotect`, `rollback`, `inventoryrollback`, `ir`, `restore` added to the item-command list.

**Validation**
- 41 unit tests (command permission/targeting, trust cooldown, offline edits, Paper setting audit, ledger store, amounts, menu titles).
- Dupe simulator (`tools/dupe_probe.py` with the test-only `tools/dupe-harness` plugin): Paper 26.3 (build 230) + ViaVersion/ViaBackwards 5.12.2, two bot players. The harness makes copies through the API (what a server bug amounts to) and legitimate moves; 24/24 as expected plus the Paper setting warning: trivial item not sealed; legit chest move clean; copies in a chest, crafting grid, allay hand and jukebox removed; fake `/i`, forbidden `/give` and a console `give Bob` did not excuse Alice; console `give Alice` re-sealed the copy at 60%; bundle unpack with a chest open clean; diamonds from nothing removed; handed-over copy taken from the giver, receiver keeps it and is not flagged; legit handover clean; plugin anvil menu showing the item clean; mob bow not sealed; duplicated shulker placement refused; offline copy reported at 60% and removed after the player rejoined; backpack-menu dupe removed while a "Backpack Shop" purchase stays; a chunk reloaded normally is clean; a real chunk rollback (unloaded without saving after Alice took 64 diamonds) came back with +64, which were removed and Alice flagged; a plugin menu handing its items back on quit is clean; a disconnect dupe (inventory saved with 32 diamonds, which then landed on the ground) lost the 32 on rejoin. An earlier design counted the inventory before the save: the simulator showed it would flag a player whose plugin menu returned items on quit and miss the real dupe, so it was replaced.
- Real client (`tools/ad_realclient.py`): Minecraft 1.21.11 with Meteor Client (no module on) in a virtual display, real mouse and keyboard, on the same server: shift-clicking a sealed sword and diamonds into and out of a chest, unpacking a bundle into an open chest, an ender chest round trip, the 2x2 crafting grid, placing / filling / breaking a sealed shulker box, dropping and picking up, travelling 2000 blocks away and back (real chunk unloads with a full chest), and killing the game with diamonds in the crafting grid and on the cursor then rejoining: 8/8 without a finding and with every seal kept. The first run found that a placed shulker lost its seal; fixed and rerun.
- Load: a ledger of 500,000 sealed items, saves every 10 s, two players. Before the fix every save cost a 30–50 ms tick; after it the tick profile matches the same server without SentinelAD (median tick ~2 ms; occasional spikes of 35–60 ms in both runs). The 500,000-entry `ledger.dat` is 49 MB.
- Not covered: real exploits (the copies and rollbacks are made by the harness), Paper 1.21.x (only 26.3 was run for 1.3.0), jukebox insertion and shulker placement of a copy were simulated events.
- Known gaps: a chunk rollback caused by a server crash is not checked (after an unclean shutdown the saved chunk counts may be older than the chunks, so they are dropped rather than risk false removals); a dupe between servers that sync player data is not seen; items made by Paper's own unsupported dupe settings are not tracked (startup warning).

**Upgrade:** replace the JAR and restart. Existing `config.yml` keeps working; new keys use the bundled defaults. Add `co`, `rollback` and similar to your own `item-commands.commands` list if you set one.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs <jar>`. Checksums are in `SHA256SUMS.txt`. License: GPL-3.0.
