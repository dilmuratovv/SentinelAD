SentinelAD 1.3.1: two fixes found on a live Paper 26.2 server.

- **A horse's saddle (and horse armor) is no longer counted twice.** On Paper 26.x the saddle is in the horse's
  inventory and also in its equipment, and the ledger saw one sealed item in two places and removed one "copy"
  at 100%, taking a real saddle from the horse (horse, skeleton horse, donkey, mule, llama). An item the
  inventory scan already found in a holder is no longer counted again from that holder's equipment. A removal like
  this was logged as `removed duplicate sealed item ... horse at X and horse at X`; if you saw one, `/sad restore <id>`
  gives the item back.
- **`ConcurrentModificationException` in `LedgerService.drain`** is fixed: scans queued while another scan runs no
  longer break the queue loop, so the rest of the queue is no longer left for the next tick.

New harness step `saddle-seen-twice-is-one-item` fails on 1.3.0 (saddle removed) and passes on 1.3.1.
Tested on Paper 26.2, 26.3, 1.21.11 and 1.21.1: harness 25/25 (24/24 + 1 skip on 1.21.1, as before), SIGKILL crash test
passes on all, 41 unit tests.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Checksums are in `SHA256SUMS.txt`.
