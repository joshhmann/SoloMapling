package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;

import java.awt.Point;

/*
 * The observed/offscreen durability boundary for one bot.
 *
 * A GCMoveSystem bot runs in one of two modes — FULL/HALO physical simulation while a real player can
 * see its map, COARSE analytic movement while nobody can — and it flips between them lazily, on its own
 * tick, off the map-level ObserverTracker verdict. Everything on the bot is therefore one of two kinds:
 *
 * DURABLE — identical either side of a transition. Written by its owner, never by the movement layer
 * and never by this class:
 *   • identity + progression: Character id/name/job/level/exp/mesos and the inventories/equipment
 *     behind them, plus the BotHoldings ledger; TrainingBot's phase, train map, current mob level,
 *     grindUntilMs, lastExpAccrualMs and lastKnownLevel.
 *   • position and map id: the coarse plan's output IS the bot's real position, so promotion reads it
 *     as the reconciliation input instead of re-deriving one.
 *   • player-facing commitments: party membership, an in-progress Trade, seller stock/sold/credited
 *     mesos, and pending recruit state (BotRecruitManager ARMED / PENDING_LEADER / PENDING_STATION).
 *   • lifecycle ownership: the bot's map/Channel/PlayerStorage membership, its CharacterStorage entry,
 *     its spot claims, this layer's registration (GCMovement.STATES + the driver task) and the macro
 *     wheel registration (BotTickService) — a transition only changes their cadence, never their owner.
 *   • spacing and map-entry timing: followOffsetX and the per-bot spawn stagger (skipDelayMs,
 *     spawnWarmupMs) were chosen by their owner at spawn/follow time, and portalDropAtMs is a wall-clock
 *     deadline for the organic map-entry drop (GCMovementDriver.java:215-222) — by the time the bot is
 *     observed again it can only be past due, which is the same "no drop pending" state the driver reads.
 *     The foothold index (lastMapId, fhIndex) is map-scoped rather than mode-scoped and is rebuilt by the
 *     map change that invalidates it.
 *   • pacing gates whose owner measures them on the wall clock rather than per mode:
 *     TrainingBot.maybeSelfBuff arms nextBuffMs only after an actual observed buff (BotTypes/TrainingBot.java
 *     :996-1005), while maybeGrindChatter advances nextChatterMs before its own observation check
 *     (:980-989), so an offscreen span can only delay the next line, never bank one; BotChatter's
 *     engagement map self-heals on its MAX_CHATTER_MS deadline, and BotSM.lastNudgeMs is a 1.5 s
 *     coalescing throttle. A transition may not credit offscreen time to any of them, and none of them can
 *     fire a backlog once observed again — so the boundary leaves them alone.
 *
 * RECONSTRUCTIBLE — rebuilt on promotion by the two methods below instead of being carried:
 *   • the coarse plan and its clock;
 *   • the physics shadow (rebuilt by the caller's ground snap + BotPhysicsEngine.teleportTo);
 *   • the movement trail: nav state, the observed-owner step, and the broadcast dedup cache;
 *   • combat presentation: the ATTACK/ALERT substitution window and its pending stance reset;
 *   • contact damage: the i-frame window and the swept-touch anchor;
 *   • watchdog state: the stuck / frozen-air / blocked-nav counters and the no-progress move clock;
 *   • the AI tick debounce and the cosmetic pause timers (duck, player-reaction hold, player scan).
 *
 * The combat half of the boundary already lives with its owner and is the model for this one:
 * GrindBrain.wasObserved drops the grind heartbeat on the unobserved edge and re-seeds
 * lastCombatProgressMs on the observed edge (BotGrindSystem/GrindBrain.java:152-160), because the
 * offscreen bot was abstract-leveling rather than swinging.
 *
 * Demotion itself needs no work: an offscreen bot stops being simulated, and everything the coarse
 * mode does not touch is rebuilt by the promotion path when the map comes back (which is also why a
 * stale value can only ever survive one direction).
 */
final class BotTransitionBoundary {

    private BotTransitionBoundary() {
    }

    /*
     * COARSE -> FULL/HALO: drop every field whose value was produced under the other mode, and release
     * the active-mode trail/nav/broadcast state. Called by GCMovementDriver before it rebuilds the
     * physics shadow, so the caller still owns the position it snaps to; call reseedObservedTrace once
     * the bot has landed.
     */
    static void reconstructForObserved(BotMovementState entry) {
        // Coarse plan + its clock: the analytic ETA is meaningless once physics resumes, and the map id
        // and start time would otherwise outlive the plan that produced them.
        entry.coarseActive = false;
        entry.coarsePlan = null;
        entry.coarsePlanStartMs = 0L;
        entry.coarsePlanTarget = null;
        entry.coarsePlanMapId = -1;

        // Movement trail: attack cooldown, nav state, observed-owner step and the broadcast dedup cache.
        // The next observed tick must be able to publish a packet even if the bot stopped moving while
        // unobserved, which is exactly what this reset guarantees.
        BotMovementManager.resetEntryStateAfterTeleport(entry);

        // Contact damage: i-frames and the swept-touch anchor. An offscreen tick already zeroes the
        // i-frames (BotContactDamage), so the reset here is self-containment rather than a fix; the anchor
        // re-seed at the landed position below is the live defect this boundary closes.
        entry.mobHitCooldownMs = 0;
        entry.lastMobTouchCheckPos = null;
        entry.lastMobTouchMapId = -1;

        // Combat presentation: drop the ATTACK/ALERT substitution window so no offscreen hit or swing keeps
        // the shouted pose alive. alertResetScheduled is deliberately NOT touched: a true flag means the
        // owner has a reset task in flight (BotContactDamage.scheduleAlertReset:285-304), that task re-reads
        // alertedUntilMs when it fires and clears the flag itself, and forcing it false here would let a
        // post-promotion markAlerted arm a second task while the first is still pending.
        entry.alertedUntilMs = 0L;

        // Watchdog: every counter below was measured against pre-demotion positions, so a carry would
        // either fire a recovery teleport immediately or hide a real stall.
        entry.stuckMs = 0;
        entry.unstuckCooldownMs = 0;
        entry.stuckCheckX = Integer.MIN_VALUE;
        entry.stuckCheckY = Integer.MIN_VALUE;
        entry.airStuckTicks = 0;
        entry.airStuckX = Integer.MIN_VALUE;
        entry.airStuckY = Integer.MIN_VALUE;
        // navBlockedPos*, the other "measured against a position" counters, are reset by the delegated
        // clearNavigationState instead: that is where the nav leg they qualify is dropped.
        // No-progress move clock: 0 means "not seeded yet", which restarts the window from the position
        // the bot actually landed on (see GCMovementDriver.giveUpStalledMove).
        entry.moveBestDist = Integer.MAX_VALUE;
        entry.moveProgressAtMs = 0L;

        // Tick debounce + cosmetic pauses.
        entry.aiTickAccumulatorMs = 0;
        entry.duckUntilMs = 0L;
        entry.reactingUntilMs = 0L;
        entry.nextPlayerScanMs = 0L;
    }

    /*
     * Anchor the swept contact-damage trace at the position the bot actually landed on. Must run after
     * the promotion teleport: a stale anchor turns the next sweep into a box spanning the whole
     * offscreen travel, which reads as a mob hit the bot never touched.
     */
    static void reseedObservedTrace(BotMovementState entry, Character bot) {
        if (entry == null || bot == null || bot.getPosition() == null) {
            return;
        }
        entry.lastMobTouchCheckPos = new Point(bot.getPosition());
        entry.lastMobTouchMapId = bot.getMapId();
    }
}
