package soloMapling.ArtificialPlayer.GCMoveSystem;

/**
 * The movement layer's half of the commitment rule ({@code soloMapling.ArtificialPlayer.BotCommitments}):
 * while a player-facing commitment owns the bot's position - an open trade window - the driver holds
 * the bot exactly where the commitment found it instead of walking its move intent away.
 *
 * <p>The hold is owned by the commitment's lifecycle, not by the movement layer: {@code BotSM} arms it
 * on the single TRADING entry and releases it in {@code cleanupTradeState()}, the single trade exit.
 * Holding is idempotent, as is releasing, so a repeated edge cannot leak a frozen bot.
 */
final class BotCommitmentHold {

    private BotCommitmentHold() {
    }

    /**
     * Freeze the bot for a commitment. Called from the bot's macro tick, so it touches nothing the
     * movement driver owns beyond the one volatile flag: the plan and nav teardown happen on the
     * driver's own thread in {@link #stillHolding} (see {@link #dropHeldPlan}).
     */
    static void hold(BotMovementState entry) {
        entry.heldForCommitment = true;
    }

    /**
     * Driver-side question, asked once per movement tick: should this held bot stay frozen? Returns
     * false - and drops the hold - when the fact the hold stands for is gone, because a bot whose
     * trade died without its FSM reaching {@code cleanupTradeState} (both bots despawned mid-window,
     * FSM stopped then restarted, a re-wrapped Character reusing this entry) must never be frozen by
     * a latch nobody owns any more. {@code Character.getTrade()} is nulled on every trade death, so it
     * is the authoritative "no window is open" signal.
     */
    static boolean stillHolding(BotMovementState entry) {
        if (!entry.heldForCommitment) {
            return false;
        }
        if (entry.bot != null && entry.bot.getTrade() == null) {
            release(entry);
            return false;
        }
        dropHeldPlan(entry);
        return true;
    }

    /**
     * Drop the analytic plan and the nav leg on the driver's thread (idempotent). The move intent
     * (moveTarget / farmAnchor) survives untouched so the bot resumes its destination when the
     * commitment settles; the coarse plan does not, because it is interpolated against a wall clock
     * that is about to stand still - kept, the next coarse tick would clamp to the plan's end and
     * teleport the bot away from the commitment.
     */
    private static void dropHeldPlan(BotMovementState entry) {
        if (entry.coarsePlan == null && !entry.coarseActive && entry.navTargetPos == null) {
            return;
        }
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        entry.coarsePlanStartMs = 0L;
        entry.coarsePlanMapId = -1;
        // The bot is standing still from a position that is now authoritative, so the physics shadow is
        // exact rather than analytic: on release an unobserved bot re-demotes from a fresh plan.
        entry.coarseActive = false;
        BotMovementManager.clearNavigationState(entry);
    }

    /**
     * Let the bot move again: re-seed the no-progress window so the first free tick measures progress
     * from where the bot stands now, exactly as a fresh {@code GCMovement.move} would. Without this a
     * trade longer than the stall window would look like a stalled move and the driver would abandon
     * the surviving destination.
     */
    static void release(BotMovementState entry) {
        if (!entry.heldForCommitment) {
            return;
        }
        entry.heldForCommitment = false;
        entry.moveBestDist = Integer.MAX_VALUE;
        entry.moveProgressAtMs = System.currentTimeMillis();
    }
}
