# Handoff — SoloMapling bot accounting & durability series

Written 2026-10-17 at commit `ced0c4a4`, for a session started in `/root/SoloMapling`.
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

## Series status: tickets 01–11 all `done`

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

## Open work

- **Deferred by the user (2026-10-17): the casino.** `decisions/06-casino-and-ambient-merchant-boundaries.md` records the agreed policy — pay the winner from the collected pot instead of conjuring a copy, a bot's stake stays a value-free prop, the blackjack 1.5x bonus is house-funded from the dealer's own ledgered holdings (needs a new ledgered *item* debit beside `BotHoldings.noteMesoSpent`). Findings 1–3 of ticket 10 carry the anchors and magnitudes; **nothing of it is implemented**. An id allowlist cannot implement the prop rule: `OPQBot` loots *real* reactor pieces and conjures copies with the same ids, so the mark has to be per drop instance, set where the drop is created.
- **Recorded, not fixed:** bot-owned `HiredMerchant` income is a sink by design (only players can withdraw); `EventInstanceManager.unregisterPlayer` calls `dropExclusiveItems` outside its try (no caller registers bots today, so hardening would be unverifiable).
- `spec.md` still lists open questions (e.g. `BOTS_PER_MAP` keyed by map id may conflate channel instances; the FULL map tier is forced by `GCMovement.markObservedNow` because a headless harness has no real player).
- The harness is not in CI (~5 min, needs MySQL + `wz/`), and absolute `ms/s` cost figures are host-specific.

## Verification recipes

Repo suite (no `wz/`, ~30 s): `cd /root/SoloMapling && mvn -o test`
→ expect `Tests run: 2024, Failures: 1, Errors: 4` — exactly the five known WZ-missing failures
(`GCWorldGraphTest.scansWorldAndRoutesAdjacentMaps`, `CoarseExecutorTest.factoryPlansARealRoute`,
`CoarseExecutorTest.interpolatesARealBakedEdge`, `GCMoveBakeTest.bakesHenesys`,
`GCMoveBakeTest.bakesEllinia_ladderHeavy`). Anything else is a real regression.

Real-WZ sandbox (if `/tmp/solomapling-e2e` is gone, rebuild it): copy `src/` into it together with the real
`wz/` (149 MB) and a MySQL; then re-apply the two port seds and **never rsync over them**:
`net/server/channel/Channel.java:78 BASE_PORT = 17575`, `net/server/Server.java:949 initLoginServer(18484)`.
`mvn -o test` there → `Tests run: 2024, Failures: 0, Errors: 0`, `BUILD SUCCESS` (~4 min).
Copy changed files with `cp` (mkdir the parent first — the test tree has no `provider/wz`, and `cp` fails quietly in a loop when it is missing).

Proof harness: `mvn -o -q package -DskipTests` → `target/Cosmic.jar`, then `bash /tmp/botproof_driver.sh`
(~5.5 min, exits 0). Green = `[PROOF] PROOF-RUN COMPLETE`, `[PROOF] PROOF-VERIFY PASS`, all bullets `PASS`,
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
