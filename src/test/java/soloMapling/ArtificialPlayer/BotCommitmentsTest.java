package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 05, criterion 3/4 (offline, no WZ): the one explicit rule for player-facing commitments
 * across the observed/offscreen boundary, and the cadence gate that enforces it.
 *
 * <p>The rule is asserted as a pure predicate over its sources, then driven through a real bot whose
 * observation state and type-specific pacing override are the only things faked: whatever the LOD
 * state and whatever the bot type, a commitment in flight resolves to the fast cadence, and once the
 * commitment ends the next tick re-asserts the bot's actual state instead of sticking fast. That last
 * half is the regression guard for the stale-HIGH hazard the per-type overrides used to have (they
 * pinned the fast cadence without clearing {@code cadenceObserved}, so ending the reason never
 * restored the slow cadence).
 */
class BotCommitmentsTest {

    private static final int BOT_ID = 21000;   // above BotHelpers.BOT_BASE_ID, so isBot() holds
    private static final int PLAYER_ID = 777;
    private static final long FAST_MS = 2000;       // BotSM.setPriorityHigh() -> updateScheduleDelay(2000)
    private static final long NORMAL_MIN_MS = 2000; // getRandomDelay() = 2000 + rand(4000)
    private static final long NORMAL_MAX_MS = 6000;
    private static final long LOW_MIN_MS = 9000;    // lowPriorityDelayMs() = 9000 + rand(3000)
    private static final long LOW_MAX_MS = 12000;
    private static final long SOCIAL_IDLE_MS = 30000;

    /**
     * The invite window is process-wide state keyed by bot id ({@link BotRecruitManager#isArmed}) and the
     * cadence rule reads it, so a window leaked by an earlier test in the same JVM would make every bot
     * here look like it had an invite in flight. Start every test from a clean slate for this id: the
     * suite must not depend on which class ran first.
     */
    @BeforeEach
    void clearRecruitWindow() {
        BotRecruitManager.clearArmed(BOT_ID);
        BotRecruitManager.clearHandoffs(BOT_ID);
    }

    @Test
    void openTradeWindowIsACommitment() {
        assertTrue(BotCommitments.inFlight(newBot(), BotSM.BotState.TRADING),
                "a player is staring at the bot's side of an open trade window");
    }

    @Test
    void aConversationIsNotASharedClauseButItsOwnerStillHonoursIt() {
        // A respondant is a bare latch with no owner-side expiry for most types, so the shared rule
        // deliberately excludes it: a plain bot mid-conversation is not pinned by the boundary rule...
        Character player = newBot(PLAYER_ID);
        PaceProbe plain = new PaceProbe(newBot(), false);
        plain.getInteractors().setRespondant(player);

        plain.checkPrioritySpeed();

        assertLow(plain.delays);

        // ...while the type that owns the conversation keeps its own live-local clause, exactly as before
        // ticket 05 - the shared gate is added to it, not a replacement for it.
        SocialPaceProbe social = new SocialPaceProbe(newBot(), false);
        social.getInteractors().setRespondant(player);

        social.checkPrioritySpeed();

        assertEquals(List.of(FAST_MS), social.delays,
                "the type that owns the conversation still answers it on the fast cadence");
    }

    @Test
    void armedInviteWindowIsACommitment() {
        Character bot = newBot();
        assertFalse(BotCommitments.inFlight(bot, BotSM.BotState.RUNNING),
                "nothing in flight before the bot says yes");
        BotRecruitManager.rollPartyAsk(bot, newBot(PLAYER_ID), 1.0, false);
        try {
            assertTrue(BotCommitments.inFlight(bot, BotSM.BotState.RUNNING),
                    "the bot said \"invite me\": the invite must be drained promptly");
        } finally {
            BotRecruitManager.clearArmed(BOT_ID);
        }
        assertFalse(BotCommitments.inFlight(bot, BotSM.BotState.RUNNING),
                "window closed -> no commitment");
    }

    @Test
    void retiredBotHasNoCommitments() {
        Character bot = newBot();
        BotRecruitManager.rollPartyAsk(bot, newBot(PLAYER_ID), 1.0, false);
        try {
            assertFalse(BotCommitments.inFlight(bot, BotSM.BotState.FINISHED),
                    "a retired bot must not be pinned awake by a leftover commitment source");
        } finally {
            BotRecruitManager.clearArmed(BOT_ID);
        }
    }

    @Test
    void idleBotWithNothingInFlightIsNotACommitment() {
        assertFalse(BotCommitments.inFlight(newBot(), BotSM.BotState.RUNNING));
    }

    @Test
    void commitmentOutranksUnobservedPacing() {
        PaceProbe bot = new PaceProbe(newBot(), false);
        bot.state = BotSM.BotState.TRADING;

        bot.checkPrioritySpeed();

        assertEquals(List.of(FAST_MS), bot.delays, "an open window is answered on the fast cadence even offscreen");
    }

    @Test
    void unobservedIdleBotFallsToLowCadence() {
        PaceProbe bot = new PaceProbe(newBot(), false);

        bot.checkPrioritySpeed();

        assertLow(bot.delays);
    }

    @Test
    void observedIdleBotReturnsToNormalFromTheLowCadence() {
        // startScheduledTask already begins at the normal cadence, so the upgrade is only observable as a
        // transition: a player arriving on an offscreen bot's map must pull it back off the low wheel.
        PaceProbe bot = new PaceProbe(newBot(), false);
        bot.checkPrioritySpeed();
        assertLow(bot.delays);

        bot.delays.clear();
        bot.observed = true;
        bot.checkPrioritySpeed();

        assertNormal(bot.delays);
    }

    @Test
    void socialIdlePacingDoesNotStarveACommitment() {
        // SocialBot's own unobserved pacing is a flat 30s. A commitment must outrank it, and ending the
        // commitment must hand the bot back to that 30s pacing - not leave it stuck on the fast wheel.
        SocialPaceProbe bot = new SocialPaceProbe(newBot(), false);

        bot.checkPrioritySpeed();
        assertEquals(List.of(SOCIAL_IDLE_MS), bot.delays, "nothing in flight -> the type's own idle pacing");

        bot.delays.clear();
        bot.state = BotSM.BotState.TRADING;
        bot.checkPrioritySpeed();
        assertEquals(List.of(FAST_MS), bot.delays, "commitment outranks the type's idle pacing");

        bot.delays.clear();
        bot.state = BotSM.BotState.IDLE;
        bot.checkPrioritySpeed();
        assertEquals(List.of(SOCIAL_IDLE_MS), bot.delays, "commitment over -> the type's pacing resumes");
    }

    @Test
    void endingACommitmentHandsTheBotBackToItsObservedCadence() {
        // The gate must not disturb the observation-driven cadence in either direction: a commitment in
        // flight is fast, and the moment it settles the next tick re-derives the cadence from the bot's
        // own LOD state instead of leaving it pinned.
        PaceProbe bot = new PaceProbe(newBot(), false);
        bot.state = BotSM.BotState.TRADING;

        bot.checkPrioritySpeed(); // offscreen, window open -> fast
        assertEquals(List.of(FAST_MS), bot.delays);

        bot.delays.clear();
        bot.state = BotSM.BotState.IDLE;
        bot.checkPrioritySpeed(); // window closed, still offscreen -> low
        assertLow(bot.delays);

        bot.delays.clear();
        bot.observed = true;
        bot.checkPrioritySpeed(); // window closed, a player is here -> normal
        assertNormal(bot.delays);
    }

    // ── Test doubles ─────────────────────────────────────────────────────────

    private static void assertNormal(List<Long> delays) {
        assertEquals(1, delays.size(), "expected exactly one cadence decision: " + delays);
        long ms = delays.get(0);
        assertTrue(ms >= NORMAL_MIN_MS && ms < NORMAL_MAX_MS, "expected the normal cadence, got " + ms);
    }

    private static void assertLow(List<Long> delays) {
        assertEquals(1, delays.size(), "expected exactly one cadence decision: " + delays);
        long ms = delays.get(0);
        assertTrue(ms >= LOW_MIN_MS && ms < LOW_MAX_MS, "expected the unobserved low cadence, got " + ms);
    }

    /**
     * A real bot with the two things this test controls faked: whether a real player is on its map, and
     * the recorded cadence. {@code state} is the protected FSM field the real transitions write.
     */
    private static class PaceProbe extends BotSM {

        final List<Long> delays = new ArrayList<>();
        boolean observed = true;

        PaceProbe(Character chr, boolean observed) {
            super(chr);
            this.observed = observed;
        }

        @Override
        public boolean checkMainPlayersOnMap() {
            return observed;
        }

        @Override
        public synchronized void updateScheduleDelay(long newDelayMs) {
            delays.add(newDelayMs);
        }
    }

    /** SocialBot carries its own pacing override, so it is driven as-is through the shared gate. */
    private static class SocialPaceProbe extends SocialBot {

        final List<Long> delays = new ArrayList<>();
        boolean observed = true;

        SocialPaceProbe(Character chr, boolean observed) {
            super(chr);
            this.observed = observed;
        }

        @Override
        public boolean checkMainPlayersOnMap() {
            return observed;
        }

        @Override
        public synchronized void updateScheduleDelay(long newDelayMs) {
            delays.add(newDelayMs);
        }
    }

    private static Character newBot() {
        return newBot(BOT_ID);
    }

    private static Character newBot(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("CommitBot" + id);
        chr.setLevel(42);
        return chr;
    }
}
