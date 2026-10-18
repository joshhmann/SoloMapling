# Synthesis — review of the ticket-04 durability boundary

Session: `/root/SoloMapling/.pi-flow/reviews/2026-10-16/review-durability-boundary/`
Axis records: `standards.md` (Standards axis, `BoundaryStandards`), `spec.md` (Spec axis, `BoundarySpec`).
Written by the session owner after both axes landed; the two axes stayed separate until the disposition table.
Fixed point: `9b7aed75` in `/root/SoloMapling`. Change under review: `GCMovementDriver.java` (6+/7-) + new
`BotTransitionBoundary.java` + new `BotTransitionBoundaryTest.java`.

Verdict: **no blocker, no High finding survived the fix round.** Both axes agree the split is correct and the
promotion edge is the only writer. One accuracy defect (a comment claiming an effect the owner code does not
have, found independently by both axes), three Low/Nit smells and two evidence-quality caveats were fixed; one
criterion is a **recorded deviation** (criterion 3's chatter/buff pacing), argued in code and pinned by a test.

## Disposition of every finding

| # | Axis | Finding | Disposition |
|---|---|---|---|
| M1 | Standards + Spec (Low there) | **Medium** — `BotTransitionBoundary.java:83-87` claimed clearing `alertResetScheduled` stops a stance packet, but `BotContactDamage.scheduleAlertReset` (`BotContactDamage.java:285-304`) broadcasts at `:301` whenever `now >= alertedUntilMs`, which zeroing that window makes always true; forcing the flag false also defeated the owner's re-entrancy guard at `:286` and let a later `markAlerted` arm a second task | **Fixed.** The write is gone; `alertedUntilMs = 0L` stays, with a comment saying the pending task is its owner's bookkeeping (`BotContactDamage`), not this class's to re-arm. Test now arms `alertResetScheduled = true` and asserts it **stays** true |
| L1 | Standards | **Low** — `navBlockedPosTicks` was reset twice (boundary + delegated `clearNavigationState`) while its three siblings were reset only in the boundary, so the group's authoritative home was unreadable | **Fixed.** All four moved into `BotMovementManager.clearNavigationState` (`BotMovementManager.java:199-213`) with a comment that the tracker qualifies the leg being dropped; the boundary no longer writes any of them. Callers of `clearNavigationState` re-checked (`BotContactDamage.java:215`, `GCMovementDriver.java:326/380`, `GCMovement.java:259`, `BotMovementManager.java:176/646/648/820`) — clearing the whole tracker is coherent at each |
| L2 | Standards | **Low** — the anchor assertion was order-blind: the fixture has no map, so `BotPhysicsEngine.findGroundPoint` returns `null` (`BotPhysicsEngine.java:398-401`) and the snap re-wrote the same point, so the assertion held whether the re-seed ran before or after the snap — the exact ordering it exists to protect | **Fixed.** The claim in the comment is now scoped to what the fixture can prove (plus the sandbox's real-WZ probe, which does snap to a different pixel), and a new test `theSweptTouchAnchorReadsTheLandedPositionNotTheCoarsePlan` (`BotTransitionBoundaryTest.java:278`) moves the bot and asserts the anchor follows it, is a **copy** (`assertNotSame`), and carries the new map id |
| N1 | Standards | **Nit** — the class doc claimed an exhaustive durable/reconstructible split but never classified `followOffsetX`, `spawnWarmupMs`, `portalDropAtMs` (nor the map-scoped foothold index) | **Fixed.** Doc gained a durable bullet: the spacing/entry-timing group is owner-chosen, `portalDropAtMs` is a wall-clock deadline (`GCMovementDriver.java:215-222`) that can only be past due after an offscreen span, and `lastMapId`/`fhIndex` are map-rather-than-mode scoped |
| N2 | Standards | **Nit** — the `private static` → package-`static` widening of `reconstructPhysicsFromCoarse` was acceptable but undocumented as a test seam | **Fixed.** Javadoc now says it is package-visible because it is the promotion seam the test drives, so the ordering and the `BotPerformanceMonitor.recordPromotionReconstruct` wrapper stay under test |
| — | Spec (criterion 2 caveat) | Seller stock / `sold` / credited mesos were asserted only as reference identity + `verifyNoInteractions` on mocks that carried no stock, so the test could not distinguish "intact" from "nothing to damage"; `BotRecruitManager.ARMED` was named in the doc but asserted by no test | **Fixed (evidence strengthened).** New `aCompletedSaleStaysOnTheSellersBooksAcrossPromotion` (`BotTransitionBoundaryTest.java:391`) builds a **real** `PlayerShop` for the seller, plants stock (`3` bundles of a 5-pot item at 250), runs the real `PlayerShop.botBuy` path with a second bot, then asserts after promotion: credited mesos (`1000 + 250 - Trade.getFee(250)`), one `sold` row, the same `PlayerShopItem` instance, its remaining bundles, and shop ownership. `pendingRecruitStateSurvivesPromotion` (`:348`) now arms the window through the real `BotRecruitManager.rollPartyAsk` and asserts `isArmed` + `armedInviterId` across the flip |
| C3 | Spec | **Criterion 3 PARTIAL** — chatter/buff pacing is not rebuilt; the change reclassifies it as durable | **Deviation, recorded and pinned.** Reason: those fields belong to `TrainingBot`/`BotChatter`, not the movement layer; reaching into them from the boundary would be feature envy across the wrong seam, and the behaviour is benign (an offscreen span can delay a line but never bank one — `TrainingBot.java:980-989` advances `nextChatterMs` before its observation check, `:996-1005` arms `nextBuffMs` only after an observed buff). New test `promotionLeavesTheObservationGatedPacingGatesAlone` (`:251`) pins the invariant in the direction that matters (promotion changes neither field) and the producer-side facts are pinned by asserting the unobserved chatter gate **does** advance. Stated in the ticket comment as a deliberate deviation from the criterion's list |
| C1, C2, C4 | Spec | Criteria 1, 2 and 4 **MET** | **Confirmed, no action.** The only writer on the edge is `GCMovementDriver.reconstructPhysicsFromCoarse0` (`GCMovementDriver.java:345-350`); `Trade.cancelTrade` is reachable only from `client/Character.java:1751/5651` and promotion changes no map and runs no interaction code |
| — | Spec (unrequested behavior) | The widenings only: `reconstructPhysicsFromCoarse` package-private, boundary's two package-private statics | **Accepted.** No production behaviour added |
| — | Both axes (forward inputs) | Ticket 07 `placeBotOnMap` bypassing `changeMap`; despawn registry leaks (06/07); recruit-atomicity / trade-vs-travel undecided; tickets 05-08 unimplemented | **Not this ticket.** Recorded as forward input in `decisions/03-offscreen-durability.md`'s still-open items |

## Criterion status at the end of the round

| Criterion | Status | Evidence |
|---|---|---|
| 1 — identity/name/level/exp/train target/accrual timestamps unchanged | met | `BotTransitionBoundaryTest.java:229` (spy: `never()` on `setLevel/setExp/setName/setJob/gainExp/gainMeso/setParty/setTrade`, values after) and `:298` (training bot's `phase`, `currentTrainMapId`, `currentMobLevel`, `grindUntilMs`, `lastExpAccrualMs`, `lastKnownLevel`) |
| 2 — party / trade / seller stock-sold-credited / pending recruit / lifecycle survive | met | `:419` (party, live `PlayerShop`, `HiredMerchant` identity + `verifyNoInteractions`), `:391` (real sale: wallet, `sold` ledger, stock, ownership), `:320` (live `Trade` window, decline settles ledger once), `:348` (pending leader/station + armed invite window), `:372` (map membership), spot claims |
| 3 — reconstructible set rebuilt | **deviation** | 6 of 7 named groups rebuilt (`BotTransitionBoundary.java:69-118`); chatter/buff pacing reclassified durable, argued in the class doc and pinned by `:251` |
| 4 — promotion during an active trade / recruit exchange stays consistent | met | `:320`, `:348` — non-interference, plus the boundary writes only `BotMovementState` (+ the caller's `setPosition`/`setStance`) |

## Verification after the fix round

- `BotTransitionBoundaryTest` (in-repo): **10 tests, 0 failures** (`mvn -o -q -Dtest=BotTransitionBoundaryTest`).
- `/root/SoloMapling` (`mvn -o test`): **1948 tests, 1 failure, 4 errors** — exactly the pre-existing WZ-data set
  (`CoarseExecutorTest.interpolatesARealBakedEdge`, `CoarseExecutorTest.factoryPlansARealRoute`,
  `GCMoveBakeTest.bakesEllinia_ladderHeavy`, `GCMoveBakeTest.bakesHenesys`,
  `GCWorldGraphTest.scansWorldAndRoutesAdjacentMaps`, all `Map data not found for 100000000|101000000` from
  `BotNavigationMapLoader.java:28`). No new failing method after the `clearNavigationState` move.
- `/tmp/solomapling-e2e` (full `wz/` + MySQL 8, `rsync -a src/` then `sh ./mvnw -o test`): **1948 tests,
  0 failures, 0 errors, BUILD SUCCESS**.
- Real-WZ promotion probe (`PromotionGroundSnapProbe`, sandbox-only): map `100000000`, 338 footholds; a bot
  mid-coarse-hop at `(4455,232)` above the rail `y=244` snaps to the rail `(4455,244)`, re-seeds the anchor at
  the landed pixel (not the stale `(10,10)`/map `999999999` fixture), drops the coarse plan, rebuilds the trail
  and watchdog and leaves identity untouched — 8/8 checks pass. This is the case the committed fixture cannot
  reach, because a mock map has no geometry.
- Bind check on the new evidence: mutating the boundary with `bot.setPlayerShop(null)` + `bot.gainMeso(-7)`
  failed three tests at once (`credited mesos survive promotion ==> expected: <1250> but was: <1243>`, the trade
  window `expected: <600> but was: <593>`, and the seller identity assertion), i.e. the new value assertions are
  load-bearing rather than decorative. Restored from `/tmp/BotTransitionBoundary.java.keep` and re-run green.

## Known limits, not defects

- The seller test covers the bot's own stall (`PlayerShop`); the `HiredMerchant` half stays identity + `no
  interactions`, because building a real merchant would need `World.activeMerchants` registration that no
  promotion-adjacent test needs. Recorded, not a defect.
- The static registries named as durable (`GCMovement.STATES`, `BotTickService`, `CharacterStorage`, `Channel`,
  `World.parties`) are still argued by construction rather than asserted; a test that observes them would be a
  lifecycle test, which is ticket 06/07 territory.
- `jumpCooldownMs` is inert (`BotMovementManager.java:371` is the only write and it writes 0), so the boundary
  deliberately does not reset it — resetting a dead field would be cargo cult.
