# Handoff — SoloMapling bot accounting & durability series

Written 2026-10-17 at commit `ced0c4a4`, for a session started in `/root/SoloMapling`; **revised 2026-10-18**
for the ticket-13 durability set, which is committed as `8d5fd93e420dc3f97a62930aa071d49adb7684a5` on top of
`a7a0f91b`, together with its `.pi-flow` records, in the commit that carries this file — so
the commit column below says what is committed and the `Open work` section says what is not.
Read this, then the spec and the newest ticket. Nothing here is a live instruction: it is state.

## Where things are

| What | Where |
| --- | --- |
| Working tree (all edits + commits) | `/root/SoloMapling` — branch `main`, remote `origin = github.com/joshhmann/SoloMapling.git`; **commits are local, never pushed** |
| Project records (this tree) | `/root/SoloMapling/.pi-flow/` — spec, map, `decisions/NN-*.md`, `issues/NN-*.md`, `docs/adr/*.md`, `reviews/`, `explorations/` |
| Upstream reference (read-only, never edit) | `/tmp/solomapling-analysis` @ `47b61b53` (sparse clone of `MadaraGameDev/SoloMapling`) |
| Real-WZ sandbox (suites + proof harness) | `/tmp/solomapling-e2e` — **ephemeral**, recreate per below |
| Evidence logs | `/tmp/ticket0*-evidence/`, `/tmp/ticket10-cleanup-repo-suite.out` |
| Throwaway probes (never committed) | `/tmp/wzbench/` (`WZLockBench.java`, `ab.sh`) |
| MySQL | container `solomapling-verify-db`, mysql 8.4, **root with empty password**, schemas `cosmic`, `cosmic_ticket06boot2` |

Doc conventions: a ticket is `issues/NN-slug.md` with `**What to build:**`, `**Blocked by:**`, `**Status:**`,
checked criteria, then `## What was built (as landed)` (anchor → change table), findings, `## Verification`.
A grilling outcome is `decisions/NN-slug.md` with `# Ticket NN: <question>`, `Type:`, `Status:`, `## Question`, `## Answer`.
ADRs live in `.pi-flow/docs/adr/` and carry plain paths, not markdown links. Use the pi-flow skills
(`skill://explore`, `skill://grilling`, `skill://code-review`, …) — they expect `.pi-flow/` in the cwd.

## Series status: tickets 01–13 all `done`

| Ticket | Commit | What it did |
| --- | --- | --- |
| 01 shared resource accounting | `9b7aed75` | `BotHoldings` ledger: bots earn/spend real value; `DropCommands.pickUpAndCredit`, `PlayerShop.botBuy`, `HiredMerchant.botBuy`, `Trade.completeTrade` |
| 02 offscreen reward budget | `9b7aed75` | one token meter per map, capped at one window; fed by respawn slots × occupants × effective drop |
| 03 cost attribution | `9b7aed75` | `BotPerformanceMonitor` + `!env perf` |
| 04 durability boundary | `f4d92027` | `BotTransitionBoundary.reconstructForObserved`: promotion rebuilds movement/plan/combat presentation |
| 05 promotion reconciliation | `8c8ea10d` | `BotCommitments` + `pinFastCadence()` + `holdForCommitment`: player-facing promises survive demotion |
| 06 persistence store | `48378416` | `BotStore` (`planSlot(wave, task, ordinal)`), `BotGeneration.createBot` adopt-or-create, shutdown sweep, hourly autosave |
| 07 reconciliation rules | `1a28a130` | five rules (no quest state, silent atomic offscreen level gains, claims survive demotion, abort-then-travel out of a trade, atomic follower reservation, party cap 6) |
| 08 integration proof | `e371f2b7` | `src/test/java/soloMapling/ArtificialPlayer/BotPopulationProof.java` — the run/verify harness |
| 09 shutdown quiescence | `2a8f93f3` | `BotShutdown` gate + cancel hooks, `BotTickService.stop()` drain; teardown exception lines 9 → 0 |
| 10 value-path audit | `d051b01b` | audited every remaining value path; fixed the one leak (bot-owned `PlayerShop` proceeds via `BotHoldings.noteMesoEarned`) |
| 11 WZ read concurrency | `ee5aeb4e` | one shared read lock per parsed WZ document (`XMLDomMapleData`), 44 synchronized provider caches; the per-boot `fNodeListCache` NPE is gone |
| cleanup | `9f7aca06` | dropped the dead merchant-owner write for bot owners (finding 4) |
| records | `ced0c4a4` | moved `.pi-flow` from `/root/maplestory_dump` into this repo |
| 12 casino pot payout | `1dfc85a4589d7451820fbc541302f16fef2d3390` | `Mark fabricated bot drops as props and pay casino pots from the house`: phase 1 pays a casino pot from the house (`BotHoldings.payOut`, blackjack `payUnit`, dice); phase 2 marks fabricated drops as props (`MapItem.conjured` across `DropCommands`/`MapleMap`/`CustomReactor`) and hands a real instance back via `BotHoldings.payOutItem`; settles decision 06 |
| 13 ledger durability | `8d5fd93e420dc3f97a62930aa071d49adb7684a5` | the `BotHoldings`/`BotStore` durability set (S1-S5, B1, B2, D1, R1, R4): a delta is journalled in the ledger or already in the row — a refund credits only what the inventory really took back, a debit that will not write takes its stored claim down, the capture stamp is read either side of the capture and an unstable capture is retried, the item snapshot is copied under the tab's own lock, and every ledger read holds the monitor a delta lands under. The failure direction is always DESTROY, never MINT |

## Open work

- **The casino pot rule is implemented and settled (ticket 12, `1dfc85a4589d7451820fbc541302f16fef2d3390`).** Phase 1 pays from the pot (`BotHoldings.payOut`, blackjack `payUnit`, dice); phase 2 makes provenance a per-drop-instance flag (`MapItem.conjured`) that now rides every fabrication site — `DropCommands` items and meso, `MapleMap`'s meso and reactor spawns, `CustomReactor` — with bot loot removing a prop without crediting it. The provenance mechanism is complete across all three classes; the old id-allowlist constraint is satisfied and closed. `decisions/06-casino-and-ambient-merchant-boundaries.md` records the policy and now marks it landed.
- **The durability set is committed (ticket 13, `issues/13-ledger-durability.md`, `8d5fd93e420dc3f97a62930aa071d49adb7684a5`).** The pot rule above is only an invariant while the `BotHoldings` ledger and the `BotStore` row agree on what a bot holds, so the set closes the ten ways they could disagree: S1 refunds credit only the units `BotHoldings.restock`/`putBack` really put back; S2 `hasUnpersistedDebit`/`hasUnpersistedItemDebit` route a debit into `BotStore.recordDebit`, which retries the write and only then takes the stored claim down via `destroyStoredHoldings`; S3 the item snapshot is copied under the tab's own lock (`Inventory.removeItem(short,short,boolean)` is one locked step); B2 each stored item is a detached copy (`BotStore.copyForWrite`); S4 the provenance test asserts the map's own `MapItem.isConjured()` instead of pinning a mock; S5 a cursed scroll destroys the held weapon through `BotHoldings.destroyHeldItem`; B1 an over-credit cannot be minted by `settle`; D1 `BotStore.save` returns the capture stamp so a delta that arrived mid-write stays; R1 `writeRow`/`writeHoldings` bracket the capture with a stamp read either side and return `UNSTABLE_CAPTURE` for a retry when it spans two moments; R4 `hasUnpersistedDebit`/`hasUnpersistedItemDebit`/`ledgerStamp` hold the `ledgers` monitor for the whole read, so a delta that already landed cannot be missed. The rule: a value move is either in the ledger or in the row, and the failure direction is always DESTROY, never MINT. The final suite counts, the commit `8d5fd93e420dc3f97a62930aa071d49adb7684a5`, and the eight src md5s that identify the verified tree are in ticket 13's `## Verification`.
- **Two more paths are BOUNDED — no value path (recorded in `issues/10-value-path-audit.md`).** `BotCustomization.EquipItem` (`soloMapling/ArtificialPlayer/BotCustomization.java:83`) writes only fabricated clean equips into `InventoryType.EQUIPPED` with no ledger call, and nothing reads a bot's `EQUIPPED` tab as value (`ItemConstants.getInventoryType` maps any equip id to `EQUIP(1)`, never `EQUIPPED(-1)`); `Character.closePlayerShop` (`client/Character.java:5683`) is bot-reachable and does NPE (`InventoryManipulator.java:182` derefs a null `getPlayer()` for a bot) but its stock is the fabricated premade list. `HiredMerchant.closeShop` (`server/maps/HiredMerchant.java:430`) has the same shape with its gate **unconfirmed**, recorded as unconfirmed rather than closed.
- **Live open thread — the two faucets (decisions/07, deferred):** `decisions/07-drop-game-and-nx-faucets.md` records the remaining places where a bot hands a player value it never held: `DropGameBot`'s paid prize (`DropGameBot.java:540`, fabricated with no debit while the player's stake *is* ledgered via `Trade.creditReceivedMeso` -> `BotHoldings.grantMeso`) and the NX gift-card mints (`NXMerchantBot.java:96-97`, `TutorialBot.java:357-361` -> `NXCodeManager.createCompleteNXCode`, DB-inserted with no debit). The rule is settled (value a bot hands out must come from its own holdings); implementation is deferred with the rest of the gambling-side work.
- **Recorded, not fixed (ticket 10):** `EventInstanceManager.unregisterPlayer` (`EventInstanceManager.java:389-406`) calls `dropExclusiveItems` (`:940-946`) outside its `try`, and the reward APIs start from `Client.getPlayer()`; no caller registers a bot today, so hardening would be unverifiable — a future event that registers bots would NPE there rather than fail cleanly. Separately, bot-owned `HiredMerchant` income is a sink by design (only players can withdraw).
- **Retained seam, no caller:** `DropCommands.botThrowEquip(Character, Equip, Point)` (`src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java:108`) is the documented caller-supplied, non-conjuring overload (it passes `conjured = false` because the caller owns the instance). A `grep -rn "botThrowEquip(" src/` finds no production caller — the only invocations are its own declaration and one mock in `src/test/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommandsProvenanceTest.java:89`; a commented-out line at `src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:1025` is the only other mention. Kept as the seam for a caller that has real value to hand over; `TutorialBot` now uses `BotHoldings.payOutItem` instead.
- `spec.md` still lists open questions (e.g. `BOTS_PER_MAP` keyed by map id may conflate channel instances; the FULL map tier is forced by `GCMovement.markObservedNow` because a headless harness has no real player).
- The harness is not in CI (~5 min, needs MySQL + `wz/`), and absolute `ms/s` cost figures are host-specific.

## Verification recipes

Repo suite (no `wz/`, ~30 s): `cd /root/SoloMapling && mvn -o test`
→ on the ticket-12 tree, expect `Tests run: 2044, Failures: 1, Errors: 4` — 2024 plus ticket 12's 20 tests,
failing with exactly the five known WZ-missing failures
(`GCWorldGraphTest.scansWorldAndRoutesAdjacentMaps`, `CoarseExecutorTest.factoryPlansARealRoute`,
`CoarseExecutorTest.interpolatesARealBakedEdge`, `GCMoveBakeTest.bakesHenesys`,
`GCMoveBakeTest.bakesEllinia_ladderHeavy`). Anything else is a real regression.
On the current tree (ticket 13, `8d5fd93e420dc3f97a62930aa071d49adb7684a5`) that same command is the verified
count: `Tests run: 2064, Failures: 1, Errors: 4` — the ticket-12 2044 plus the set's 20 new tests
(11 in `BotStoreTest`, 9 in `BotHoldingsTest` — 7 at the S1/B1/S5 work and 2 with R4 —, none added by the
reworked `DropCommandsProvenanceTest`), with the same five WZ-missing failures and nothing else. The full
numbers, and the eight md5s that identify the verified tree, are in `issues/13-ledger-durability.md`
`## Verification`.

Real-WZ sandbox (if `/tmp/solomapling-e2e` is gone, rebuild it): copy `src/` into it together with the real
`wz/` (149 MB) and a MySQL; then re-apply the two port seds and **never rsync over them**:
`net/server/channel/Channel.java:78 BASE_PORT = 17575`, `net/server/Server.java:949 initLoginServer(18484)`.
`mvn -o test` there → on the ticket-12 tree `Tests run: 2044, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (~33 s);
on the current tree (ticket 13, `8d5fd93e420dc3f97a62930aa071d49adb7684a5`) `Tests run: 2064, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (34.18 s wall).
Copy changed files with `cp` (mkdir the parent first — the test tree has no `provider/wz`, and `cp` fails quietly in a loop when it is missing).

Proof harness: `mvn -o -q package -DskipTests` → `target/Cosmic.jar`, then `bash /tmp/botproof_driver.sh`
(~5.5 min, exits 0). Green = `[PROOF] PROOF-RUN COMPLETE`, `[PROOF] PROOF-VERIFY PASS`, 12 bullets `PASS`,
`grep -c fNodeListCache` = 0, and teardown ordered as
`Shutting down the server!` → `[BotShutdown] bot activity quiesced (N schedulers cancelled)` →
`[BotShutdown] macro wheel drained in Xms (residue N ticks)` → channel/world disposal.
`src/test/resources/log4j2-test.xml` sets `<Root level="off">`, so harness logs carry no log4j lines —
anchors are the `[PROOF]` / `[BotShutdown]` System.out lines.

Graph: `cd /root/SoloMapling && graphify update .` (ASCII, no API) → ~24.5 K nodes; `graphify-out/` is gitignored.

## Trap list

- `/root/SoloMapling` is a **cone-mode sparse checkout** (`.mvn .pi-flow database docs scripts src`). A new
  top-level directory must be added with `git sparse-checkout add <dir>` or `git add` refuses it while
  `git status` hides it. The pattern list is local to the clone (`git sparse-checkout list`).
- A path that *ends* in `..` NPEs in `XMLDomMapleData.getChildByPath` by design (`substring(indexOf("/") + 1)`
  when `indexOf` returns -1); use `../name`.
- Bots never send packets and nothing in `soloMapling/` dispatches into packet handlers: any path reachable
  only through `Client`/packet handlers is unreachable for bots. The one leak shape that matters is code a
  *player* action runs with a bot as counterparty (that is how the `PlayerShop` leak happened).
- Every bot value move must go through `BotHoldings` (the shared headless `BotClient` has no player, so
  `InventoryManipulator` cannot credit a bot). The ledger equals what the bot holds relative to its persisted
  baseline; `settle()` hands back net-positive deltas where the bot stands.
- The ledger and the row are one record, and they are updated from different threads (see `Open work`,
  ticket 13). Any new bot value move has to leave a delta that a write can see: a move applied away from
  `BotHoldings`' `mutate` (its ledger monitor) is invisible to the capture stamp and will be minted back by a
  restart. The rule is DESTROY, never MINT — refuse a capture that spans two moments and retry it, and take a
  stored claim down rather than leave value the bot already gave away.
- The proof driver also waits for the log4j-string teardown markers `Shutting down the server!` and
  `Worlds and channels are offline.` (`net/server/Server.java:1940` and `:2005`), but those can never be
  satisfied: the harness runs with `target/test-classes/log4j2-test.xml`, whose `<Root level="off">` silences
  the console appender. The observable teardown markers are the `[BotShutdown]` System.out lines, and they do
  appear in order.
