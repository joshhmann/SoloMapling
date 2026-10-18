# 05: Promotion reconciliation of position and commitments

**What to build:** When a bot becomes observed, it reconciles from its durable state: position snaps consistently, physics/movement shadow is rebuilt, and any commitment mid-flight is restored rather than restarted.

**Blocked by:** 04 Durability boundary across observed and offscreen states.

**Status:** done (2026-10-16).

- [x] Promotion places the bot at its durable position with a rebuilt movement state and no visible desync.
- [x] An offscreen bot that travelled maps promotes at the landed map, not the pre-travel map.
- [x] Commitments interrupted by demotion (trade, recruit, party) resume or abort under one explicit rule.
- [x] Repeated observe/unobserve cycling does not accumulate drift in position or commitments.

## The one explicit rule

> A player-facing commitment in flight that spans the observed/offscreen boundary on its own - an open
> trade window, an armed party-invite window - is answered on the fast macro cadence whichever side of
> the boundary the bot is on, and the one commitment that owns position (an open trade) holds the bot
> in place. The boundary never clears, cancels or restarts a commitment: settlements happen only through
> each commitment's own owner (trade exit `BotSM.cleanupTradeState`, `BotTradeLogic.checkTradeQueue`,
> the invite window's own TTL).

Implemented as `soloMapling.ArtificialPlayer.BotCommitments` (the predicate), `BotSM.keepAwakeForCommitment()`
/ `BotSM.pinFastCadence()` (the cadence gate, consulted first in `BotSM.checkPrioritySpeed`), and
`GCMoveSystem.BotCommitmentHold` + `GCMovement.holdForCommitment`/`releaseCommitmentHold` (the position
hold, armed at the single TRADING entry, released at the single trade exit, and self-healing in the
movement driver when the trade dies without its owner ever running).

### Why each scope decision

- **Only signals whose owner can expire them became shared clauses.** A dialogue respondant is a bare
  latch (`Dispatcher` sets it on any answering bot; only SocialBot, TutorialBot, GameZoneHostBot,
  BlackjackDealerBot and the FINISHED case clear it), so a shared clause on it would pin the 2 s
  cadence forever for most types. Each type keeps its own live-local clause instead
  (`TrainingBot.menuActive()`, `SocialBot.hasActiveRespondant()`) and adds `keepAwakeForCommitment()`.
- **The pending trade *request* is not a clause.** Only trade-capable types drain `BotTradeQueue`, so a
  request left in the queue on any other type would pin the fast cadence forever. (A player's trade
  invite to a non-merchant bot is unanswered regardless of cadence - input for ticket 07.)
- **A demoted bot that owns a real `Trade` is never frozen by the flag alone.** `Character.getTrade()`
  is nulled on every trade death, so the driver releases a hold whose window is gone (both bots
  despawned, FSM stopped then restarted, a reused `GCMovement` entry).

## Evidence

- Repo (no `wz/`): `mvn -o test` -> 1968 tests, 1 failure / 4 errors = exactly the pre-existing
  WZ-data set (`CoarseExecutorTest`, `GCMoveBakeTest`, `GCWorldGraphTest`, "Map data not found for
  100000000|101000000"), identical to the pristine baseline of the same ticket's start.
- Real-WZ sandbox (`/tmp/solomapling-e2e`, 149 MB `wz/`): `mvn -o clean test` -> 1968 tests,
  0 failures, 0 errors, BUILD SUCCESS. (Four stale probe classes from earlier rounds were still in
  that tree and inflated the first sandbox tally by 8; they are deleted, so both counts are now the
  same 1968 and the equality is meaningful.)
- Red-before proof, run twice against the real production code:
  - gates inert (`if (false && keepAwakeForCommitment())`, `if (false && ...stillHolding(...))`) ->
    4/20 fail: the three cadence tests (`expected [2000]`, got 10919 / 9866 / 30000) and
    `heldBotDoesNotWalkWhileTheCommitmentIsOpen` (`[x=300,y=400]` -> `[x=1200,y=400]`, destination
    consumed, coarse advanced, bookkeeping ran).
  - self-heal inert -> 2/10 fail: `theHoldSelfHealsWhenTheTradeDiesWithoutItsOwner`,
    `aHoldWithoutAnOpenWindowNeverFreezesAnything`.
- Real-map runtime probe (throwaway, real `Map.wz`, deleted after the run): cross-map landing resync +
  promotion to the landed map's ground (`x=-810,y=292` on Ellinia from a spawn at `y=99`), five
  demote/promote cycles with the bot's x and promoted y returning to the same value every cycle and
  the durable set (id/name/level/exp/mapId/meso) unchanged, and a held bot pixel-frozen over 20 real
  ticks that resumes movement after release.
- Criterion 2 needed verification rather than code: promotion reads the live map/position
  (`GCMovementDriver.java:188-193` -> `reconstructPhysicsFromCoarse` -> `bot.getPosition()`), and the
  tick-top resync (`GCMovementDriver.java:155-157` -> `onMapChange`) reconciles the entry onto the
  landed map first, so no stored map id can outlive the travel. Only a real-map run can show this
  (the mocked maps have no footholds), which is what the probe did.

## Review findings folded in

Standards axis (scout report, 6 findings): restored `FollowerBot`'s trade teardown - the follow/travel
pollers warp a frozen bot out of its window (`GCTravel` hop-stuck at 12 s, `GCFollow` 400 ms), so the
hold cannot replace it (finding 1); `heldForCommitment` is now `volatile` (2); the conversation clause
was dropped from the shared rule per the reasoning above (3); the plan/nav teardown moved off the macro
tick and onto the driver's own thread so no movement field has two writers (note 4/5);
`BotCommitmentHold.stillHolding` self-heal added (note 5); a mid-air hold keeps the JUMP stance (note 6).

Spec axis (reviewer report, confidence 0.85, verdict `correct`, no must-fix) - all four criteria
`met`; its own bookkeeping note that its review bundle was a stale snapshot of this change set, so
every finding below is stated against the working tree:

- **F2 [should-fix] - fixed.** The arm at the trade entry was unconditional, but `DropGameBot` keeps no
  `BotTradeSM`, runs its own trade flow and rewrites TRADING back to RUNNING, so its TRADING exit can
  never release the hold (only the driver's watchdog could). The arm is now guarded on the window
  actually existing: `if (getChr().getTrade() != null) GCMovement.holdForCommitment(getChr());`. The
  latch is therefore unarmable without a window, and a self-managed trade flow is unbound by the same
  check the watchdog uses (`BotCommitmentHold.stillHolding`), which
  `theHoldSelfHealsWhenTheTradeDiesWithoutItsOwner` covers. Rejected the reviewer's alternative of
  releasing inside `DropGameBot`'s `TRADING` -> `RUNNING` rewrite: that rewrite runs on every tick
  while the window is live, so releasing there would re-arm next tick and toggle the freeze
  tick-by-tick instead of holding the bot for the window.
- **F1 [note] - acknowledged.** Re-checked the change set against the tree; the reviews' verdicts are
  anchored to the on-disk revision, which is what was verified and is what is committed.
- **F3 [note] - accepted, no code change.** `pinFastCadence` cannot pull a *pending* deep-grind tick
  forward (`updateScheduleDelay` early-returns when the delay is unchanged) and the wheel-lag governor
  can multiply the fast period by up to 4x. Both are load protection, and no reachable stall case
  exists (every commitment source is same-map or nudged). The genuine gap is the missing *wake* for a
  player's trade request (`BotTradeQueue` has no nudge) - recorded below as a ticket-07 input.
- **F4 [note] - accepted.** A trade that opens mid-jump freezes the bot with the JUMP stance while
  `idleOnGround` zeroes the fall, matching the repo's existing portal-drop idiom; snapping to the
  ground would be a visible drop instead, i.e. it is a presentation choice, not a desync.
- **F5 [note] - accepted.** The plan teardown is one driver tick late, so an unobserved bot could
  travel up to one period before freezing; a trade's map is observed by construction (the partner is
  co-located), so the window is milliseconds, and closing it fully would need a second writer on the
  plan. Same subject as the one-tick arming limit below.

## Known limits (inputs to tickets 06-08)

- **Travel during an active trade is not generally frozen.** The hold stops the movement layer, but
  `GCTravel`'s own 300 ms poller calls `bot.changeMap(...)` on a stalled hop - a warp is not a physics
  step, so only `FollowerBot`'s local pause guards its travel today. Ticket 07 criterion 4 is the
  general rule; `BotGeneration.placeBotOnMap` also bypasses `changeMap`'s trade cancel entirely.
- **A dialogue respondant has no expiry.** The pin now lives in each type's own clause, but a respondant
  left set by a type that never clears it (TutorialBot / GameZoneHostBot paths) still pins that type's
  fast cadence. Owner-side expiry is a ticket-07 input.
- **One-tick arming window.** The hold is armed on the macro tick and read on the driver's thread, so a
  bot can take one more movement step (up to one tick period; 50 ms observed) after the window opens.
- **Despawn still leaves registries behind** (`BotTickService.ENTRIES`, `GCMovement.STATES`,
  `BotAttackDriver`), so a despawned bot's entry can be reused with a held flag - handled since
  `stillHolding` checks the Character's trade, but the cleanup itself is ticket 06/07 work.
- The `BotTickService` wheel-lag governor (`FACTOR_MAX = 4.0`) still multiplies the fast cadence under
  wheel lag, so the commitment fast path is 2 s normally, 8 s at maximum lag. Deliberate: it is load
  protection, not a commitment mechanism.
