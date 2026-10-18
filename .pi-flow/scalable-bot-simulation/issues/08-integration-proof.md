# 08: End-to-end integration proof

**What to build:** One runnable demonstration that a persistent finite-resource bot population earns, spends, persists across restart, reconciles on promotion, and stays within budget, with sparse/crowded costs reported.

**Blocked by:** 01, 02, 04, 05, 06, 07 Shared resource accounting path; Offscreen reward budget; Durability boundary; Promotion reconciliation; Persistence store; Remaining reconciliation rules.

**Status:** done (2026-10-17)

- [x] A bot loots, purchases, and trades with correct debits and credits across a full observed/offscreen cycle.
- [x] A restart preserves that bot's holdings and commitments under the stated settlement rule.
- [x] Offscreen tradable output stays inside the per-map budget for the elapsed window.
- [x] Cost attribution separates background, active, and transition terms for the run.
- [x] No player-facing behavior regresses (seller credit, stock, sold ledger, party, trade).

## The demonstration

One harness, two phases, run inside a real booted server:

- `src/test/java/soloMapling/ArtificialPlayer/BotPopulationProof.java` (test scope, package `soloMapling.ArtificialPlayer`; the class is named `BotPopulationProof`, not `*Test`, so `mvn test` never picks it up — it is not a unit test, it needs real WZ data, MySQL and a live world).
- Phase `run`: `Server.main(new String[0])` boots the server in-process, waits for the population, then drives every criterion through production APIs and writes `/tmp/botproof/state.txt`, then exits so the registered shutdown hook runs the settlement sweep.
- Phase `verify`: a second JVM boots against the same database, re-finds the recorded bot id, and compares identity, progression, meso and holdings against the state file, writing `/tmp/botproof/verify.txt` and printing `PROOF-VERIFY PASS|FAIL` (exit 0/5).

```
# sandbox with real WZ data and MySQL (`/tmp/solomapling-e2e`)
mvn -o -q package -DskipTests                                    # jar + test-classes
cd /tmp/solomapling-e2e
java -Xmx3g -cp target/Cosmic.jar:target/test-classes \
     soloMapling.ArtificialPlayer.BotPopulationProof run
java -Xmx3g -cp target/Cosmic.jar:target/test-classes \
     soloMapling.ArtificialPlayer.BotPopulationProof verify
```

Driver used for the recorded run: `/tmp/botproof_driver.sh` (waits on log lines only, never `pgrep -f`, which self-matches the invoking shell); logs `/tmp/botproof_run.out`.

Why a harness and not a test: every criterion needs the whole stack at once — a real `MapleMap` (`MapleMap.addPlayer` cannot run headless because `Server.getWorld(int)` returns null, `net/server/Server.java:248-256`), real WZ item metadata for shops and drops, a real `World` for the reward meter (`OffscreenRewardBudget.draw` returns `Reward.NONE` for a null world), and real MySQL for the store. No integration-test convention exists in the repo (no failsafe, no `*IT.java`).

## Criterion 1 — loot, purchase, trade across an observed/offscreen cycle

Tracked bot: id `577332`, slot `557332`, name `CashShopper`, job `311`, level 73, on map `211040300`.

| Step | Observation | Result |
|---|---|---|
| loot (offscreen, `observed=false`) | item `2000000` on map `211040300` | held 1 -> 2, drop picked up=true |
| force observation of the bot's own map | `GCMovement.markObservedNow` every 400 ms for 4 s (`ObserverTracker` `FORCE_TTL_MS = 2000`) | `observed=false` -> `observed=true (forced observed)` -> 4 s later `observed=false (after the force lapsed)` |
| purchase (inside the forced-observed window) | shop of bot `290954` on map `910000017`, item `4010001`, price 2227, `Trade.getFee(2227)=0` | bought=true; buyer meso 472783 -> 470556; seller meso 0 -> 2227; bundles 11 -> 10; sold ledger 0 -> 1; buyer item count 0 -> 1; buyer-observed credits 2227 = debits 2227 |
| trade (inside the forced-observed window) | bot `577332` -> bot `577156`, 400 mesos, two-sided via `BotTradeCommands`/`Trade.completeTrade` | first wallet 470556 -> 470156 (offer); second wallet 93502 -> 93902; both windows closed |
| loot again (after the lapse, `observed=false`) | item `2000000` on map `211040300` | held 2 -> 3, drop picked up=true |

The purchase ran through `PlayerShop.botBuy` (the real production path): buyer debit through `BotHoldings.spendMeso`, seller credit net of `Trade.getFee`, bundle decrement, `SoldItem` append and the `getPlayerShopOwnerUpdate` packet (a no-op for a bot client). The loot ran through `DropCommands.botLootSingleDrop`, the same call the drop command uses. The trade ran through the two-sided production sequence (request -> accept -> offer -> confirm twice).

## Criterion 2 — restart preserves holdings and commitments

The verify phase is a second boot of the same database. All checks PASS (`/tmp/botproof/verify.txt`):

- the bot is back in the population, id `577332`, name `CashShopper`;
- the store adopted it this run (`BotStore.isAdoptedInThisRun=true`) and has a row for its slot;
- identity survived (name and job identical; level/exp recorded 73/17778, stored 73/17778 — not behind);
- **the open trade window was settled before the row was written**: wallet before the offers 470156, wallet with the window still open 469906, stored meso 470156. The run phase deliberately exits with a 250-meso offer outstanding (`open window: bot 577332 offers 250 mesos to bot 577238`), so this line is the settlement rule doing the work at shutdown, not an absence of commitment.
- every recorded item stack came back, and the live bot holds everything the store restored (row meso 470156 = live meso 470156; identical 19-stack fingerprint, including the item bought from the shop `4010001:1`);
- the live wallet still holds what it held before the offers.

Merchant stock, party membership and pending recruits are deliberately **not** claimed here — they are process-only (no `player_shops` table), which ticket 06 recorded as a declared limit.

## Criterion 3 — offscreen output stays inside the per-map budget

`OffscreenRewardBudget.draw(mapId, occupants, 60_000, now, world)` asked 25 times per map and per window:

| Map | Occupants | Round 1 | Same window again | One real 60 s window later |
|---|---|---|---|---|
| `100000000` (the busiest, app-drained) | 296 resident bots | 0 mesos / 0 items (0 of 25 paid) | 0 / 0 | — |
| `101030404` (bot-free) | 0 resident bots | 13622 mesos / 219 items (11 of 25 paid) | 0 / 0 (0 of 25) | 13622 mesos / 219 items (11 of 25 paid) |

Assertion recorded as PASS: *one window paid the map's yield once, however many bots asked*. The busiest map shows the clamp from the other side: the resident population had already drawn that window's yield, so extra drawers got nothing. The bot-free map shows the meter is per map and per window, and that a new window pays the same yield again (map time, not asker count, drives accrual).

## Criterion 4 — cost attribution separates the three terms

`BotPerformanceMonitor.report()` (delta-based; primed once before the first reading) windows from the same run:

| Term | Sparse window (6.000 s) | Crowded window (15.442 s) | After demotion (6.001 s) |
|---|---|---|---|
| ACTIVE total | 0.42 ms/s (0.04%) | 7.78 ms/s (0.78%) | 2.84 ms/s (0.28%) |
| —— combat-damage | 0.00 | 1.02 | 0.00 |
| —— move-ground / move-air / move-climb | 0.00 / 0.39 / 0.00 | 1.85 / 2.53 / 0.58 | — |
| —— broadcast-move | 0.02 | 1.80 | — |
| —— physics-step (nested) | 0.31 | 1.70 | — |
| BACKGROUND total | 4202.23 ms/s (420%) | 6387.68 ms/s (639%) | 9088.72 ms/s (909%) |
| —— macro-tick | 4117.45 (2698 samples) | 6114.65 (4859) | 9034.06 (963) |
| —— movement-tick | 83.91 | 272.32 | 54.02 |
| —— pathfind (nested) | 77.52 | 244.61 | 39.24 |
| —— offscreen-accrual (nested) | 80.48 | 236.65 | 42.64 |
| TRANSITION total | 0.00 (221 samples) | 0.00 (1635 samples) | 0.00 (336 samples) |
| —— coarse-demotion / tier-flip | 215 / 6 | 1620 / 15 | 330 / 6 |

The crowded window is created with `EnvironmentManager.spawnAttackTestBots(2)` (attack-test bots bypass the LOD gate, so they do the real combat/physics/broadcast path) plus an 8 s forced observation of map `104040000`; `LodCounts` then reports `observed=true full=0 halo=0 active=0 dynamic=1815 tracker=true` — FULL requires a real player, which a headless harness cannot have, so the observed tier was reached through `markObservedNow`.

Read this as a **separation**, not a budget of record: the absolute values are host-dependent (32 cores, load average ~12-13 during the run) and BACKGROUND `macro-tick` exceeds 100 % of wall time because it is summed across tick threads. Ticket 03's numbers remain the reference figures for the per-term costs.

## Criterion 5 — no player-facing behaviour regressed

- **Seller credit**: 2227 mesos credited net of the fee, exactly the buyer's debit.
- **Stock**: bundles 11 -> 10 on the sold line, the rest of the shop untouched.
- **Sold ledger**: `getSold()` 0 -> 1 with a `SoldItem` for the transaction.
- **Trade**: two-sided meso trade moved 400 mesos in one direction and closed both windows; the shutdown sweep then refunded an outstanding 250-meso offer instead of losing it.
- **Party**: `Party.createParty` succeeded and a real 6-member party refused a seventh join (`partySeventhRefused=true`), through `World.updateParty(JOIN)` -> `Party.addMember` -> `Party.MAX_MEMBERS`.
- **Suite**: repo `mvn -o test` 2010 tests with only the pre-existing WZ-data failures (`GCWorldGraphTest.scansWorldAndRoutesAdjacentMaps`, `GCMoveBakeTest.bakesEllinia_ladderHeavy`/`bakesHenesys`, `CoarseExecutorTest.interpolatesARealBakedEdge`/`factoryPlansARealRoute`); sandbox with real WZ 2010 tests, 0 failures, 0 errors.

## Evidence

- Run: sandbox `/tmp/solomapling-e2e` (real 149 MB `wz/`, ports `BASE_PORT=17575` in `src/main/java/net/server/channel/Channel.java:78`, login 18484 in `src/main/java/net/server/Server.java:947`, DB `cosmic_ticket06boot2`), jar built from the ticket-06/07/08 source, booted via the harness, both phases completed, `/tmp/botproof_run.out` has **0** lines matching `Exception|ERROR`.
- Both phases: `saw [All bots initialized] after 21s`, run phase `PROOF-RUN COMPLETE`, verify phase `PROOF-VERIFY PASS` after 5 s.
- The harness change set is test-scope only (one new file under `src/test/java`), so it cannot alter production behaviour: `git status --short` in the repo shows only that new file (plus the untracked `graphify-out/`), and `diff -rq src` between the repo and the sandbox differs by exactly the two port lines — so the boot above ran the committed production source.
- Cold-start caveat: `state.txt` records 0-meso/0-item budget draws for the busiest map and, in the first run of the harness, a purchase leg that found no affordable bot shop — the final harness widened the shop scan to bot-held stores **and** map-object stores (`MapleMap.getPlayerStores()`), and a bot-free map is used for the budget clamp. Both numbers above are from the run with that fix.

## Known limits

- **Absolute performance figures are host-specific.** Only the ACTIVE/BACKGROUND/TRANSITION separation is asserted; the ms/s values scale with cores and load.
- **Shops are not restart-durable.** Stock and the sold ledger live in the process (no `player_shops` table), so criterion 2 covers holdings, progression and the open trade window only. Party membership and pending recruits are likewise process-only (ticket 06's declared limits).
- **The observed tier is forced, not natural.** Without a real client there is no FULL map (a real player id <= 20000 is required); `GCMovement.markObservedNow` reaches the same code path with a 2 s TTL.
- **The harness is not in CI.** It is a `main` in test scope: it needs MySQL + the WZ archive, and it takes ~5 minutes for both phases. Wiring it into a nightly job is a follow-up candidate.
- **The population keeps moving under the harness.** Training/social bots can travel or despawn mid-run; every leg re-reads live objects and reports rather than asserts on the bot's own decisions (a bot-initiated travel would cancel an open trade window — which is the ticket-07 rule-4 behaviour and still refunds).
- **Fixed by issue 09**: the LOD-demotion / map-disposal shutdown race (bot ticks running while `Channel.shutdown()` disposes maps) was present in the tree when this proof was recorded; a recorded boot produced one `MapleMap.calcPointBelow` NPE after "Worlds and channels are offline", and the second verify phase produced 9 lines in three stack shapes after `PROOF-VERIFY PASS` (see issue 09 for the risk review and the stacks). Issue 09 closed it with a bot-activity gate, the wheel drain and the cancellations listed there; the post-shutdown exception count on this same proof is now 0.
