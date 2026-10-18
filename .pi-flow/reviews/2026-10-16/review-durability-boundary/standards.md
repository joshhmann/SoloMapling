# Standards review — durability boundary (ticket 04)

- Axis: repository conventions + maintainability only (does not judge ticket fit).
- Target: uncommitted working tree at fixed point `9b7aed75` in `/root/SoloMapling`.
- Scope: `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotTransitionBoundary.java` (new),
  `.../GCMoveSystem/GCMovementDriver.java` (diff), `src/test/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotTransitionBoundaryTest.java` (new).
- Evidence read in full: the three files, `BotMovementManager.resetEntryStateAfterTeleport`/`clearTransientState`/
  `clearNavigationState` (BotMovementManager.java:185-208), `BotContactDamage.scheduleAlertReset` (:282-305),
  `BotPhysicsEngine.findGroundPoint` (:398-415), `BotMovementState` field list, `GCMovementDriver` diff and callers,
  and round-1 baseline `/root/SoloMapling/.pi-flow/reviews/2026-10-16/review-scalable-bot-frontier/standards.md`.

## Verdict

**No Blocker and no High findings.** The split is standards-conformant: a single package-private, private-constructor
class holding the durable/reconstructible contract, a two-call promotion API whose ordering requirement is stated
in the doc, delegation (not copy) of the existing teleport reset, and tests that drive the real promotion entry point.
The findings below are one accuracy/ownership defect and four judgment-call smells.

---

## Blocker

None.

## High

None.

## Medium

### M1 — `BotTransitionBoundary.java:83-87`: the alert-reset write does not do what its comment claims, and it defeats the owner's one-shot guard

Evidence — comment at 84-85 claims *"the one-shot reveal task must not fire a stance packet nobody asked for"*, then
`entry.alertResetScheduled = false;` (87). The owner does not gate the packet on the window:

```java
// BotContactDamage.java:285-304
if (entry.alertResetScheduled) { return; }          // owner's re-entrancy guard
entry.alertResetScheduled = true;
MethodScheduler.runAfterDelay(() -> {
    long now = System.currentTimeMillis();
    if (now < entry.alertedUntilMs) { entry.alertResetScheduled = false; scheduleAlertReset(entry); return; }
    entry.alertResetScheduled = false;
    if (entry.bot != null) { entry.bot.broadcastStance(); }   // fires anyway
}, delay);
```

Because the boundary also zeroes `alertedUntilMs` (86), an in-flight task always takes the `now >= alertedUntilMs`
path and broadcasts at :301 — the packet the comment says is prevented. What the write *does* change is the guard at
:286: with `alertResetScheduled` forced false while a task is still pending, the first `markAlerted` after promotion
(BotContactDamage.java:279 calls `scheduleAlertReset`) arms a second task, and each task clears the flag again, so the
guard no longer bounds the number of pending tasks. Impact: a stance packet the comment says cannot happen, plus a
weakened invariant for a field this class does not own.

Suggested fix — leave the owner's bookkeeping alone; suppress at the owner if suppression is wanted
(e.g. `if (entry.alertedUntilMs != 0L) entry.bot.broadcastStance();` in `BotContactDamage`):

```suggestion
        // Combat presentation: no offscreen hit or swing may keep the shouted ALERT pose alive. The
        // pending reveal task stays its owner's business (BotContactDamage.scheduleAlertReset) — a
        // cleared window is not this class's to re-arm.
        entry.alertedUntilMs = 0L;
```

Priority 2 · confidence 0.75.

## Low

### L1 — `BotTransitionBoundary.java:98-101`: the blocked-nav group is reset from two files, one write of it redundantly

Evidence — line 75 delegates `BotMovementManager.resetEntryStateAfterTeleport(entry)` →
`clearTransientState` (BotMovementManager.java:189-197) → `clearNavigationState` (:199-208), which already contains
`entry.navBlockedPosTicks = 0;` (:207). So `entry.navBlockedPosTicks = 0;` at boundary:98 re-writes a field the
delegated call just reset, while the three siblings it is grouped with (99-101) are reset nowhere else. The four
fields travel together everywhere in the codebase, yet the boundary's block reads as if it owns all four.
Matters because a maintainer touching blocked-nav state cannot tell which home is authoritative.
Judgment call (the grouping may be deliberate locality).

Suggested fix — keep the group but drop the duplicated write and say where the field came from:

```suggestion
        // navBlockedPosTicks was already zeroed by resetEntryStateAfterTeleport above; the rest of the
        // blocked-position state is not covered there.
        entry.navBlockedPosGiveUpTicks = 0;
        entry.navBlockedPosX = Integer.MIN_VALUE;
        entry.navBlockedPosY = Integer.MIN_VALUE;
```

Priority 3 · confidence 0.7.

### L2 — `BotTransitionBoundaryTest.java:178-180`: the anchor assertion is order-blind, so it cannot catch the ordering it exists to protect

Evidence — the comment at 178 says *"the swept anchor re-seeded where the bot actually landed"*, but the fixture is
`new BotMovementState(chr, null)` (:73) and the character has no map, so `BotPhysicsEngine.findGroundPoint` returns
`null` at its first guard (`if (map == null || position == null) return null;`, BotPhysicsEngine.java:398-401) and the
promotion teleport re-writes the same coordinates. `LANDED_AT` therefore holds whether `reseedObservedTrace` ran before
or after the snap — the exact contract `reconstructForObserved`/`reseedObservedTrace` were split for
(`BotTransitionBoundary.java:58-61`). The test claims coverage it does not have.

Suggested fix — minimal: stop claiming the landed position (comment only). Stronger: add a case whose promotion moves
the bot (a foothold index under the coarse point) and assert the anchor equals the post-snap position, not the pre-snap one.

```suggestion
                // contact damage: i-frames, and the swept anchor re-seeded from the promoted position
```

Priority 3 · confidence 0.9.

## Nit

### N1 — `BotTransitionBoundary.java:14-46`: the class doc claims an exhaustive two-way split it does not enumerate

The doc states *"Everything on the bot is therefore one of two kinds"*, yet `BotMovementState.followOffsetX`
(BotMovementState.java:121), `spawnWarmupMs` (:123) and `portalDropAtMs` (:140) appear under neither DURABLE nor
RECONSTRUCTIBLE and are not touched by the boundary. A maintainer classifying a new field has no rule to apply.
Suggested fix — one durable bullet, e.g. *"wall-clock timers that outlive the mode flip (spawnWarmupMs, portalDropAtMs)
are durable and left alone"*, or soften the exhaustiveness sentence. Judgment call. Priority 3 · confidence 0.6.

### N2 — `GCMovementDriver.java:330-336`: the private → package-private widening is acceptable but undocumented as a test seam

`reconstructPhysicsFromCoarse` (336) is now package-private, and its only production caller is the promotion edge at
GCMovementDriver.java:194; the widening therefore exists for `BotTransitionBoundaryTest.promote` (:117-119). Verdict:
acceptable — the repo routinely exposes package-private static seams (`BotPhysicsEngine.findGroundPoint`,
`BotMovementManager.resetEntryStateAfterTeleport`), and going through the driver keeps the
`BotPerformanceMonitor.recordPromotionReconstruct` wrapper + `teardown` path under test rather than calling
`BotTransitionBoundary` directly. Neighbouring code names such surfaces (GCMovement.java:17 "package-private plumbing";
BotContactDamage.java:21-22); this javadoc does not. Judgment call.

```suggestion
     * The durable half — identity, progression, commitments, lifecycle — is deliberately not touched.
     * Package-private so BotTransitionBoundaryTest can drive the promotion edge itself.
```

Priority 3 · confidence 0.6.

---

## Checked, no finding

- **Separate class vs. private method group — justified.** The reset is 35 lines of field writes; the class is
  `final`, package-private, private-constructor, matches the package's other plumbing types (BotContactDamage,
  LodCounts), and is the single place the durable/reconstructible contract is written down — including *why*
  (`GrindBrain.wasObserved` precedent, wall-clock pacing gates that self-heal on `MAX_CHATTER_MS` / `NUDGE_DEBOUNCE_MS = 1500`,
  the `BotSM.java:120/337` nudge throttle). Those cross-references were spot-checked and are accurate
  (BotChatterSystem/BotChatter.java:51,281; GrindBrain.java:152-160; BotSM.java:337).
- **Split of the reset (`reconstructForObserved` / `reseedObservedTrace`) — coherent.** The pre-snap half is pure state
  assignment; the post-snap half needs the landed position, and the API states the ordering requirement
  (BotTransitionBoundary.java:58-61, 114-118). No other patch-introduced value crosses a boundary without a consumer:
  both methods are called from exactly one place (GCMovementDriver.java:346 and :350) and the test.
- **No duplicate reset beyond L1.** Every field in the new block is assigned exactly once; the trail half is delegated
  to `resetEntryStateAfterTeleport`, not copied (`attackCooldownMs`, `graphWarmupFallback`, `observedOwnerStepX/Y`,
  `movementBroadcastValid` appear only in BotMovementManager.java:191-196).
- **Mob-anchor half is honest about itself.** `BotContactDamage` already zeroes i-frames offscreen, and
  BotTransitionBoundary.java:77-79 says so instead of claiming a fix; the anchor re-seed is the live defect.
- **Durable-set assertions are meaningful, not tautological.** `promotionLeavesIdentityAndProgressionAlone` drives the
  real promotion and then verifies `setLevel/setExp/setName/setJob/gainExp/gainMeso/setParty/setTrade` were never
  called on a spy, with `clearInvocations(bot)` (:208) taken *after* fixture setup so the bot's own construction does
  not produce false failures; the value assertions (`BOT_ID`, name, level 42, exp 1234) follow. The mock-based ones
  pair `assertSame` on identity with `verify(map, never()).addPlayer/removePlayer` (:293-296) or
  `verifyNoInteractions(party, playerShop, merchant)` (:318) — the repo's normal "identity + no side effects" idiom.
  Reflection on `TrainingBot.phase`/`currentTrainMapId` (:232-243) mirrors the existing white-box style in
  `BotHoldingsTest`/`TierDwellTest`.
- **`assertAll` usage is correct** (:158-159 with per-assertion labels, so a carried field is named rather than the
  first failure aborting the rest). No test leaks state: `settledMesos` mocks its own `MapleMap` per call, and
  `pendingRecruitStateSurvivesPromotion` clears handoffs first; the trade test exercises a live `Trade` across
  promotion and settles once, which is the point of the case.
- **Missing/empty activation is handled**: `reseedObservedTrace` null-guards `entry`, `bot` and `bot.getPosition()`
  (BotTransitionBoundary.java:120-122) — consistent with the guard style elsewhere in the package.

## Verdict fields

- `overall_correctness`: correct
- `explanation`: No Blocker or High findings; the new boundary follows the package's conventions (package-private
  plumbing, delegated reset, documented durable/reconstructible contract) and its tests drive the real promotion entry
  point with meaningful assertions. The one medium issue is a comment whose stated effect the owner code does not have
  (clearing `alertResetScheduled` cannot suppress `broadcastStance`, and it weakens the owner's one-shot guard); the
  rest are Low/Nit ownership, doc-completeness and test-claim smells.
- `confidence`: 0.8
