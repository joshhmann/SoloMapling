# Synthesis — review of the scalable-bot frontier (tickets 01-03)

Session: `/root/SoloMapling/.pi-flow/reviews/2026-10-16/review-scalable-bot-frontier/`
Axis records: `standards.md` (Standards axis), `spec.md` (Spec axis). Written by the session owner; the two axes
stayed separate until the disposition table below.

Verdict: **no blocker survived the fix round.** Every finding from both axes either got fixed and re-proved, or is
recorded here as a deliberate, documented deviation. The frontier is green in the full-WZ + DB sandbox and adds no
new failure in the sparse-checked-out working tree.

## Disposition of every finding

| # | Axis | Finding | Disposition |
|---|---|---|---|
| S1 | Spec | **Blocker** — an uncompleted bot trade minted the offered item (`Trade.cancel` credited the offer back, `settle` then spawned it) and a real held offer would duplicate on cancel | **Fixed.** `Trade.cancel` no longer returns items for a bot (`if (!isBot(chr))` around the return loop); `BotTradeCommands.addItemToTradeWindow`/`swapItemToTradeWindow` no longer touch holdings and `moveOfferOutOfHoldings` is deleted; `BotHoldings.takeItem` deleted (dead). Red-before: with the old bodies restored, 3/3 `BotTradeOfferTest` failed (`expected: <0> but was: <5>`, `expected: <600> but was: <1000>`, `expected: <0> but was: <500>`), then green after restore |
| S2 | Spec | High — the four sparse background phases printed a hardcoded `0.00 ms/s` with `nanos=0` | **Fixed.** All four are timed at their existing `BotPerfStats` increment sites (`BotSM` macro tick and nudge, `GCMovementDriver` movement tick, `MovementCommands` idle broadcast); `COUNTED_NAMES`/`COUNTED_ADDERS`/`COUNTED_PREV` and the literal line are gone; macro-tick and movement-tick are umbrellas so nav-resolve/pathfind/physics/move-\*/broadcast work is not counted twice from above |
| S3 | Spec | Medium — `enabled()` always true made the untimed fast path dead code | **Fixed.** The `enabled()`-guarded branch in `BotMovementManager.broadcastMovement` and `enabled()` itself deleted |
| S4 | Spec | Low — `HiredMerchant.botBuy` granted one bundle regardless of `quantity` | **Fixed.** Quantities multiplied exactly like `PlayerShop.botBuy` |
| S5 | Spec/Standards | Low — budget used `world.getDropRate()` only, while the live drop path also uses `getBossDropRate()` | **Deliberate, documented.** Boss maps are not bot grind targets; under-estimating them is the safe direction. Comment corrected to say so (Standards axis independently judged this a design choice, not a defect) |
| S6 | Spec | Nit — `METERS` keyed by map id, not per instance | **Deliberate, documented.** Channels of one map share a meter, which under-produces instead of multiplying world yield; class comment now says exactly that |
| T1 | Standards | High — trade payouts never left the `BotHoldings` ledger, so `settle` re-spawned spent mesos | **Fixed.** `BotHoldings.noteMesoSpent`/`noteMesoRefund` + hooks in `Trade.setMeso` (after the real debit) and `Trade.cancel`'s refund branch. Red-before: with the hooks disabled, `offeredMesosAreNotSpawnedBySettleWhileTheTradeIsStillOpen` failed `expected: <600> but was: <1000>`; with only the refund hook disabled, `declinedTradeReturnsTheBotsOfferedMesos` failed `expected: <1000> but was: <600>` |
| T2 | Standards | High — `pickUpAndCredit` credited outside the drop's lock, so two bots could take one drop | **Fixed.** Same discipline as `Character.pickupItem`: `lockItem()` → re-check `isPickedUp()` → credit → `pickItemDrop` inside try/finally `unlockItem()`. Red-before: 2/2 `DropCommandsPickupTest` failed with the unlocked body |
| T3 | Standards | Medium — broadcast phases marked `nested` hid fan-out work recorded outside any move-\* phase (`GCMovement.face`, `teleportTo`, `BotNavigationManager`) | **Fixed.** `broadcast-move`, `broadcast-raw`, `broadcast-fanout` now count towards the ACTIVE total; the class doc states the resulting bounded overlap (broadcast time emitted inside a move-\* phase is counted in both) and why hiding it was worse. Monitor test asserts the flags and the new total |
| T4 | Standards | Medium — the ledger lock was decorative (synchronized map, unlocked values) | **Fixed.** `Ledger` mutations go through `synchronized addItem/addMeso` and `settle` reads `items()`/`meso()` snapshots; the multi-thread writer set is documented on the class |
| T5 | Standards | Low — `BotHoldingsTest` shared static ledger state | **Fixed.** `BotHoldings.clearForTest()` + `@BeforeEach` |
| T6 | Standards | Nit — `recordPathfind(String caller, …)` ignored `caller` | **Fixed.** Parameter dropped, single call site (`BotNavigationManager`) updated |
| T7 | Standards | Nit — nudge sample recorded outside try/finally | **Fixed.** Wrapped like the macro-tick hook |
| S7 | Spec | Ticket 03 criterion 3 names "promotion burst" inside the within-active breakdown, while criterion 1 requires transition cost reported *separately* | **Deviation, recorded.** The burst stays in TRANSITION (criterion 1 is the more specific requirement and the conflict is internal to the ticket). All five named cost sources are attributed in one readout: physics stepping, movement broadcast, combat scan/damage/loot in the ACTIVE split; promotion burst as the TRANSITION total with its own `promotion-reconstruct` line |
| S8 | Spec | Ticket 01 criterion 3 was PARTIAL: the giving side of a trade never settled | **Fixed by S1's change.** A bot now pays real mesos (ledger-tracked, `Trade.setMeso`) and its generated item offer is window-only — never minted, never duplicated |
| S9 | Spec | Not verified: `client/Client.java:1253` might remove a bot without a settle | **Resolved by the session owner.** `checkChar(accid)` is reached only from `LoginPasswordHandler.java:133` with the logging-in account's id, and matches on `getAccountID()` equality; bots share `BotClientHandler`'s single `BotClient` (account id 0, no login) so it cannot match them. Even if it could, the loss direction is safe: settlement is a projection, so unsettled value vanishes instead of being minted |
| S10 | Spec | Scope creep: `MapleMap.broadcastMessage` return type, `getCurrentSpawnRate` made public, `ItemInformationProvider.getSlotMax` split, `BotPerformanceMonitor` surface, `GrindBrain.lootAtFeet` indirection | **Accepted.** Each is the minimum needed by the ticket it serves (02 needs the spawn-rate rule and item data; 03 needs the recipient count, the phase surface and a timed loot call site). Player-side values and paths are unchanged |

## Verification after the fix round

- `/root/SoloMapling` (sparse `wz/`): `mvn -o test` → **1938 tests, 1 failure, 4 errors**, exactly the pre-existing
  data-dependent set — `CoarseExecutorTest` ×2, `GCMoveBakeTest` ×2, `GCWorldGraphTest` ×1, all
  `Map data not found for 100000000|101000000` from `BotNavigationMapLoader.java:28`. Identical to the pristine
  47b61b5 baseline.
- `/tmp/solomapling-e2e` (full WZ + MySQL 8.4 schema/data): `sh ./mvnw -o test` → **1938 tests, 0 failures,
  0 errors, BUILD SUCCESS**.
- Red-before/green-after proofs for the three behavioural fixes (S1, T1, T2) as listed above, each restored from a
  `/tmp` backup and re-run green afterwards.
- WZ + DB budget probe (`OffscreenBudgetWzProbe`, sandbox-only): map `100000002` (17 slots, mob level 8) yields
  389 mesos per 60 s window at 1 occupant, 441 at 3, 519 at 6, 3893 at dropRate 2 / mesoRate 5; three bots × 60 s
  totalled 441 — exactly one window — with 39 items ≤ Σ ceil(per-item allowance); the real
  `TrainingBot.accrueAbstractLoot` gave bot 1 389 mesos + `{4000012=16, 2061000=4}` and bot 2 0 mesos +
  `{2061000=13}`; maps `100000003` (1600/min) and `100000004` (2403/min) scale as expected.
- Operator surface (`PerfSurfaceProbe`, sandbox-only, drives the real `!env perf` readout): sparse window
  (1.108 s) → ACTIVE 0.00, BACKGROUND 35.74 ms/s (observer-poll 27.08, movement-tick 5.42, macro-tick 3.25,
  offscreen-accrual nested), TRANSITION 0.00; crowded window (1.113 s) → ACTIVE 60.74 ms/s (combat-damage 32.35,
  move-ground 10.78, combat-loot 7.91, combat-scan 6.47, physics-step 4.31 nested, broadcast-move 3.23,
  broadcast-fanout units 240), BACKGROUND 0.00, TRANSITION 1.35 ms/s (promotion-reconstruct).
- New permanent tests: `BotHoldingsTest` 7, `BotTradeOfferTest` 4, `DropCommandsPickupTest` 2,
  `BotPerformanceMonitorTest` 2, `OffscreenRewardBudgetTest` 9.

## Known limits, not defects

- Completed-trade permanence (the bot's payout staying spent) has no test: completing needs a locked two-sided
  `Trade`, and nothing on that path refunds, so there is no regression surface to pin.
- The monitor is always on (no toggle). That is deliberate for ticket 03 — the toggle only ever gained a
  dead branch — and costs two `System.nanoTime()` calls plus LongAdder increments per hooked operation.
