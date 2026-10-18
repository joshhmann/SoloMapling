# Spec axis — ticket 04 durability boundary

Axis: spec conformance only (style/maintainability is the standards axis).
Target: `/root/SoloMapling` working tree vs `9b7aed75`. Change = `GCMovementDriver.java` (6+/7-) +
new `BotTransitionBoundary.java` + new `BotTransitionBoundaryTest.java` (7 tests).
Authority: `issues/04-durability-boundary.md` criteria 1-4; supporting `spec.md:21,24` and
`decisions/03-offscreen-durability.md`.
Reviewer: `BoundarySpec`.

**Verdict: 1 met · 2 met · 3 partial (one named group not rebuilt) · 4 met.** No blocker. One
low-severity doc-vs-code overclaim.

## Criterion 1 — identity/name/level/exp/train target/accrual timestamps unchanged — MET

The only writer on the edge is `GCMovementDriver.reconstructPhysicsFromCoarse0`
(`src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:345-350`): it calls
`BotTransitionBoundary.reconstructForObserved(entry)` (BotMovementState fields only), then
`BotPhysicsEngine.findGroundPoint` + `teleportTo`, then `BotTransitionBoundary.reseedObservedTrace`.
`teleportTo` (`BotPhysicsEngine.java:688-692`) writes `bot.setPosition`, the physics shadow
(`clearMovementState`, `BotPhysicsEngine.java:1977-2002`) and `bot.setStance`
(`syncCharacterState`, `BotPhysicsEngine.java:1643-1649`). No identity/progression writer exists on
the path.

Evidence: `BotTransitionBoundaryTest.java:206-226` (`promotionLeavesIdentityAndProgressionAlone`:
`never()` on `setLevel/setExp/setName/setJob/gainExp/gainMeso/setParty/setTrade`, plus id/name/
level/exp values); `:228-248` (`promotionDoesNotTouchTheTrainingBotsDurableProgression`:
`currentTrainMapId`, `currentMobLevel`, `grindUntilMs`, `lastExpAccrualMs`, `lastKnownLevel`, and
`phase` identity). Accrual timestamps (`grindUntilMs`, `lastExpAccrualMs`) are asserted explicitly.

Position (in the ticket's "What to build" sentence, not in criterion 1) is *durable as the
reconciliation input*: the edge reads `bot.getPosition()` and snaps to ground
(`GCMovementDriver.java:347-349`). That ground snap is pre-existing (context lines of the diff, not
introduced here), so it is not a finding; note only that "position survives unchanged" is achieved
by reading the live position, while the y-snap is the ADR-mandated reconciliation
(`docs/adr/2026-10-16-offscreen-simulation-contract.md`).

## Criterion 2 — party / trade / seller stock-sold-credited / pending recruit / lifecycle ownership survive — MET

Is anything on the promotion path touching them? No. The path writes `BotMovementState` fields plus
`Character.setPosition` and `Character.setStance`. `Trade.cancelTrade` is reachable only from
`Character.changeMapInternal` (`client/Character.java:1751`) and `closeTrade`
(`client/Character.java:5651`), and promotion changes no map and runs no interaction code.

Survival, per commitment:

| Commitment | Established how | Evidence |
|---|---|---|
| Party | reference identity + no calls | `BotTransitionBoundaryTest.java:304-318` (`assertSame(party)`, `verifyNoInteractions(party)`) |
| In-progress trade | real `Trade` round-trip, value-level | `:250-276` (`assertSame(trade)`, wallet 600 during offer, window item intact, `heldPotions==0`, decline → wallet 1000 and `settledMesos==1000`, i.e. ledger followed the wallet exactly once) |
| Seller stock / `sold` / credited mesos | by construction only | `:310-316` mocks `PlayerShop`/`HiredMerchant`, `assertSame` + `verifyNoInteractions` — **no value is planted or read**, so the test cannot distinguish "intact" from "untouched because the mocks carry no stock" |
| Pending recruit | identity-keyed static maps + round-trip | `:278-289` (`setPendingLeader(4242)`/`markStationHere` → consume == 4242 / true); storage `BotRecruitManager.java:41-44` |
| Lifecycle ownership | membership + spot claims | `:291-302` (`assertSame(map)`, `never()` `addPlayer`/`removePlayer`); `:317-321` (`BotSpotClaims.holders==1`, same slot) |

Caveats (evidence quality, not criterion failure): the seller-stock/sold/credited-mesos row is
absence-of-calls plus reference identity, never a value assertion; `ARMED`
(`BotRecruitManager.java:41,67`) is named in the boundary doc
(`BotTransitionBoundary.java:21-22`) but asserted by no test; `CharacterStorage` entry / Channel /
`GCMovement.STATES` / `BotTickService` registration are likewise construction-argued, not asserted.

## Criterion 3 — reconstructible set explicitly rebuilt — PARTIAL (chatter/buff pacing carried)

Rebuilt, each to the exact declared initializer in `BotMovementState.java:28-205`:

- **Coarse plan + clock** — `BotTransitionBoundary.java:66-70`: `coarseActive=false`, `coarsePlan=null`,
  `coarsePlanStartMs=0L`, `coarsePlanTarget=null`, `coarsePlanMapId=-1`.
- **Movement trail** — `BotTransitionBoundary.java:75` → `BotMovementManager.resetEntryStateAfterTeleport`
  → `clearTransientState` (`BotMovementManager.java:185-197`) + `clearNavigationState` (`:199-208`):
  `attackCooldownMs`, `graphWarmupFallback`, `observedOwnerStepX/Y`, `navTargetPos`, `navEdge`,
  `navJumpLaunchEdge`, `navJumpLaunchX`, `navJumpLaunchDelaySteps`, `navTargetRegionId`,
  `navPreciseTarget`, `navBlockedPosTicks`, `movementBroadcastValid=false` (forces the next observed
  broadcast, which is the stated purpose at `:72-74`).
- **Combat presentation** — `BotTransitionBoundary.java:86-87`: `alertedUntilMs=0L`,
  `alertResetScheduled=false`.
- **Contact damage** — `BotTransitionBoundary.java:80-82` + `reseedObservedTrace`
  (`:119-125`: anchor `= new Point(bot.getPosition())`, map id re-stamped); physics shadow via
  `GCMovementDriver.java:347-349`.
- **Watchdog** — `BotTransitionBoundary.java:91-105`: `stuckMs`, `unstuckCooldownMs`,
  `stuckCheckX/Y=MIN_VALUE`, `airStuckTicks`, `airStuckX/Y=MIN_VALUE`, `navBlockedPosGiveUpTicks`,
  `navBlockedPosX/Y=MIN_VALUE`, `moveBestDist=MAX_VALUE`, `moveProgressAtMs=0L`.
- **Tick debounce + cosmetic pauses** — `BotTransitionBoundary.java:108-111`: `aiTickAccumulatorMs`,
  `duckUntilMs`, `reactingUntilMs`, `nextPlayerScanMs`. The debounce reset is load-bearing, not
  cosmetic: `consumeAiTick` accumulates `TICK_MS` per tick (`GCMovementDriver.java:447-454`) and is
  called at `:197`, i.e. *before* the coarse branch at `:251`, so offscreen ticks bank accumulator
  time that would otherwise spend as an immediate observed AI tick.
- **MISSING — chatter/buff pacing.** No reset, in this change or anywhere on the edge. The change
  instead reclassifies the group as durable and argues it (`BotTransitionBoundary.java:26-32`):
  `TrainingBot.nextBuffMs` is armed only after an actual observed buff
  (`BotTypes/TrainingBot.java:996-1005`), `maybeGrindChatter` advances `nextChatterMs` before its own
  observation check (`TrainingBot.java:980-989`), the `BotChatter` engagement map self-heals on
  `MAX_CHATTER_MS` (`BotChatterSystem/BotChatter.java:262`), `BotSM.lastNudgeMs` is a 1.5 s throttle
  (`ArtificialPlayer/BotSM.java:335-340`).
  The ticket enumerates chatter/buff pacing as reconstructible (`issues/04-durability-boundary.md`
  criterion 3), and so do `spec.md:21` and `decisions/03-offscreen-durability.md`; no code and no
  test realises that (`BotTransitionBoundaryTest.java` asserts none of these fields, either
  direction). Behaviour at the edge: the first observed tick force-buffs immediately
  (`nextBuffMs` stale in the past) and a chatter line can be delayed but not banked — low impact and
  arguably the owner's intent (`TrainingBot.java:722` "buff up as soon as a player can see it"), but
  the criterion as written is unmet. Either the code or the criterion must move; the ticket's list is
  the authority until amended.

Residual fields not reset — checked, no finding (all outside criterion 3's enumeration and either
durable, map-scoped or inert): `fallPeakPhysY` (`BotPhysicsEngine.java:703-705,897-898,937`) is
provably `+INFINITY` at the edge because `tryCoarseAdvance0` refuses to enter coarse while
`inAir||climbing` (`GCMovementDriver.java:272-274`) and every landing re-seeds it, so post-promotion
fall damage is computed from the real apex; `navGraph`, `lastEdgeBlockReason`, `lastNavDecision`
(map-scoped cache / diagnostics; `lastEdgeBlockReason` is read at `BotNavigationManager.java:262-263`
but cleared on every resolve path, `:520/589/633/638/645/652`); `portalEnterReadyTicks`,
`swimMoveDir/swimVerticalHold/swimJumpRequested` (per-tick or bounded), `swimNextJumpAtMs`
(wall clock), `jumpCooldownMs` (only ever assigned 0, `BotMovementManager.java:371`), `climbVerticalDir`,
`ropeEntryY` (inert: `clearMovementState` clears climbing and rope-entry intent). `moveTarget`/
`moveTargetPrecise`/`moveTargetSource`/`farmAnchor` are deliberately kept while the no-progress clock
is restarted (`BotTransitionBoundary.java:102-105`), consistent with `giveUpStalledMove`
(`GCMovementDriver.java:229,359-374`).

## Criterion 4 — promotion during an active trade / recruit exchange leaves the commitment consistent — MET (edge non-interference)

Promotion writes only movement state + position/stance; it neither changes map nor runs trade/recruit
code. `BotSM`'s TRADING teardown (`ArtificialPlayer/BotSM.java:266-283`) fires on
`isTradeComplete`/`verifyTradePartner`/`!isOfferAccepted`, and `verifyTradePartner`
(`BotTradeSystem/BotTradeHandler.java:49-69`) is partner-presence + `verifyPlayerOnSameMap` — neither
is altered by the edge (same map, same partner, same `Character.getTrade()`).

Evidence: `BotTransitionBoundaryTest.java:250-276` (offer survives promotion, decline leaves
wallet+ledger consistent, settles exactly once) and `:278-289` (pending recruit handoff consumed
intact). Caveat: what is proven is non-interference, not a positive atomicity rule; the recruit
`ARMED` invite window keeps running on wall clock across an offscreen span
(`BotRecruitManager.java:67`), which belongs to the still-undecided "recruit atomicity" /
"trade-vs-travel" items (`decisions/03-offscreen-durability.md`, `spec.md:24`) and to tickets 05-08.
Forward input only.

## Unrequested behavior

- `GCMovementDriver.reconstructPhysicsFromCoarse` widened `private static` → package-private `static`
  (`GCMovementDriver.java:336`) purely as the test seam; the boundary class exposes two package-private
  statics. No production behaviour added.
- The reset set is entirely inside "presentation state is rebuilt on promotion" plus the coarse-plan
  clock fields; nothing in the diff reaches outside the promotion edge. No scope creep found.

## Incorrect / overclaimed

- **Low — `BotTransitionBoundary.java:84-85` overclaims the alert-reset reset.** "the one-shot reveal
  task must not fire a stance packet nobody asked for" is not achieved: `alertResetScheduled` is only a
  re-arm guard (`BotContactDamage.java:285-301`), and a `MethodScheduler` task already queued before
  promotion still runs and calls `bot.broadcastStance()`. Clearing the flag additionally lets a later
  `markAlerted` queue a second task while the stale one is pending (two `broadcastStance` calls).
  Impact is bounded and cosmetic: both resolve to STAND (`alertedUntilMs=0`) and are sent to the
  observed map only. No test covers this either way.

## Not this ticket (forward inputs, not findings)

Ticket 07 (`placeBotOnMap`/`warpBotToLocation` bypassing `changeMap`), despawn registry leaks
(06/07), tickets 05-08 unimplemented, and the undecided recruit-atomicity / trade-vs-travel rules
(`spec.md:24`).
