SentinelAD 1.2.0: global anti-duplication ledger for Paper 1.21 through 26.3 (one JAR, Java 21; Java 25 on 26.x). Works with any client version through ViaVersion/ViaBackwards.

**How it works**
- Every unstackable item (weapons, armor, tools, shulker boxes...) gets a unique seal the first time it is seen. AD scans players, ender chests, containers, ground items, item frames, armor stands and storage entities, and remembers where each seal was last seen. A seal in two places at once, confirmed again after a short delay, is a duplicate: the new copy is removed (backed up, `/sad restore <id>`), the original stays. Copies inside shulker boxes and bundles are caught too.
- Stackable valuables (diamonds, netherite, totems...) are protected by conservation while a container is open: items that appear from nothing are taken back (backed up).
- Event-level blocks: sealed copies, oversized stacks, breaking an open shulker, falling-block and storage-entity portal dupes.
- `DupeFlagEvent` API; SentinelGuard 2.2 adds findings to `/sus`.

**New in 1.2.0**
- **Item commands never cause a removal.** A duplicate or stackable gain that follows `/give`, `/kit`, `/item`, clone or item-editor commands (also inside `/execute ... run`) within 10 seconds, or while the container was open, is only flagged at 50% (below SentinelGuard's `/sus` bar). A player's own command excuses at most one finding per 10 minutes, so spamming `/kit` cannot switch removal off. Configure under `item-commands`.
- **World rollback detection.** `ledger.dat` stores each world's game time; a world restored from a backup is detected on start (or when loaded later) and item locations are rebuilt automatically, no `/sad ledger reset` needed.
- Fixed the startup tip claiming SentinelGuard was not installed when it loads after AD.

**Upgrade:** replace the JAR and restart. Existing `config.yml` keeps working; the new `item-commands` section uses the bundled defaults until you add it.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs <jar>`. Checksums are in `SHA256SUMS.txt`. License: GPL-3.0.

