package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;

/**
 * The one explicit rule for player-facing commitments across the observed/offscreen boundary.
 *
 * <p>A commitment a real player started that spans the boundary on its own - an open trade window, an
 * armed party-invite window (the bot said "invite me") - is answered on the observed cadence no matter
 * which side of the boundary the bot currently sits on. The observer/dwell state is an LOD hint for
 * how much physics and how much macro work to run; it is never a reason to make a player wait out a
 * background heartbeat, and no bot type's background pacing override may starve a commitment either.
 *
 * <p>Signals whose owner cannot expire them are deliberately NOT clauses here: a dialogue respondant
 * is a bare latch ({@code Dispatcher} sets it on any answering bot; only SocialBot, TutorialBot,
 * GameZoneHostBot, BlackjackDealerBot and the FINISHED case ever clear it), so a shared clause on it
 * would pin the fast cadence forever on most types. Each type keeps its own live-local clause instead
 * - {@code TrainingBot.menuActive()}, {@code SocialBot.hasActiveRespondant()} - and adds
 * {@link BotSM#keepAwakeForCommitment()} to it.
 *
 * <p>The same rule pins the bot in place for the one commitment that owns its position - an open
 * trade - so the bot cannot walk out from under the window it just opened (see
 * {@code GCMoveSystem.BotCommitmentHold}, armed by {@link BotSM} at the TRADING entry and released
 * at its single exit in {@link BotSM#cleanupTradeState()}).
 *
 * <p>Demotion never clears, cancels or restarts a commitment. The observed/offscreen transition
 * touches only the movement shadow (coarse plan + position), so a commitment in flight survives it
 * unchanged and settles through its owner's own path: a trade at {@link BotSM#cleanupTradeState()},
 * a trade request in {@code BotTradeLogic.checkTradeQueue}, an invite by its own window/TTL.
 */
public final class BotCommitments {

    private BotCommitments() {
    }

    /**
     * Is a player-facing commitment in flight for this bot? Consulted once per macro tick, from
     * {@link BotSM#checkPrioritySpeed()} via {@link BotSM#keepAwakeForCommitment()}.
     */
    public static boolean inFlight(BotSM bot) {
        if (bot == null) {
            return false;
        }
        Character chr = bot.getChr();
        if (chr == null) {
            return false;
        }
        return inFlight(chr, bot.getState());
    }

    /**
     * The rule itself, as a pure predicate over the commitment sources: one clause per owner that can
     * end it, no lookup the caller could have answered differently.
     */
    static boolean inFlight(Character chr, BotSM.BotState state) {
        if (chr == null || state == BotSM.BotState.FINISHED) {
            return false;
        }
        // An open trade window: the partner is watching the bot's side of it.
        if (state == BotSM.BotState.TRADING) {
            return true;
        }
        // The bot said "invite me": the invite can land inside the armed window, so the bot keeps
        // draining the incoming invite promptly instead of on the slow wheel.
        return BotRecruitManager.isArmed(chr.getId());
    }
}
