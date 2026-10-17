package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import server.Trade;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Ticket 05 (promotion reconciliation): the movement layer's half of the commitment rule. A player-facing
 * commitment that owns the bot's position - an open trade window - has to hold the bot exactly where the
 * commitment found it, without losing the destination the bot was walking to, and the hold has to survive
 * every boundary reset the observed/offscreen crossing performs. Driven through the real seams
 * (BotCommitmentHold, GCMovementDriver.reconstructPhysicsFromCoarse, the driver's own tick) rather than
 * through re-implementations of them.
 */
class BotCommitmentHoldTest {

    private static final int BOT_ID = 21000; // > 20000 so BotHelpers.isBot() is true
    private static final int SHOP_MAP_ID = 100000002;
    private static final Point STANDING_AT = new Point(300, 400);
    private static final Point WALKING_TO = new Point(1200, 400);

    @Test
    void holdOnlyArmsTheFlagAndTheDriverDropsThePlan() {
        // Two writers, two threads, one owner each: the macro tick only sets the volatile flag; the plan
        // and nav teardown happens on the driver's own thread when it asks the hold question. Keeping the
        // teardown off the arming edge is what stops the movement fields from having two writers.
        BotMovementState entry = heldCandidate(newBot());

        BotCommitmentHold.hold(entry);

        assertAll(
                () -> assertTrue(entry.heldForCommitment, "the hold is armed"),
                () -> assertNotNull(entry.coarsePlan, "arming touches nothing the driver owns"),
                () -> assertTrue(entry.coarseActive, "not even the analytic shadow"),
                () -> assertNotNull(entry.navTargetPos, "nor the held leg"));

        assertTrue(BotCommitmentHold.stillHolding(entry), "the window is open, so the hold stands");

        assertAll(
                () -> assertNull(entry.coarsePlan, "a wall-clock plan would clamp and teleport the bot away"),
                () -> assertNull(entry.coarsePlanTarget, "plan-scoped"),
                () -> assertEquals(0L, entry.coarsePlanStartMs, "plan-scoped"),
                () -> assertEquals(-1, entry.coarsePlanMapId, "plan-scoped"),
                () -> assertFalse(entry.coarseActive, "standing still is exact, not analytic"),
                () -> assertNull(entry.navTargetPos, "the held leg is dropped with the plan"),
                () -> assertEquals(WALKING_TO, entry.moveTarget, "the destination outlives the trade"),
                () -> assertEquals(SHOP_MAP_ID, entry.farmAnchorMapId, "so does the farm anchor"));
    }

    @Test
    void holdIsIdempotent() {
        BotMovementState entry = heldCandidate(newBot());
        BotCommitmentHold.hold(entry);
        assertTrue(BotCommitmentHold.stillHolding(entry));

        // A second hold edge (e.g. the bot re-enters TRADING state on a later tick) must not disturb the
        // entry, and neither must the second question: the teardown is idempotent, so nothing that the
        // running tick has since written is clobbered.
        entry.coarsePlanStartMs = 999L;
        BotCommitmentHold.hold(entry);

        assertTrue(entry.heldForCommitment);
        assertTrue(BotCommitmentHold.stillHolding(entry));
        assertEquals(999L, entry.coarsePlanStartMs, "second hold + second question are both no-ops");
    }

    @Test
    void releaseReseedsTheNoProgressWindow() {
        BotMovementState entry = heldCandidate(newBot());
        BotCommitmentHold.hold(entry);
        entry.moveBestDist = 120;
        entry.moveProgressAtMs = 5_000L;
        long before = System.currentTimeMillis();

        BotCommitmentHold.release(entry);

        long after = System.currentTimeMillis();
        assertFalse(entry.heldForCommitment, "the bot may move again");
        assertEquals(Integer.MAX_VALUE, entry.moveBestDist, "progress restarts from where the bot stands");
        assertTrue(entry.moveProgressAtMs >= before && entry.moveProgressAtMs <= after,
                "the no-progress window is re-seeded at release time, not at trade time");
    }

    @Test
    void releaseWithoutHoldIsANoOp() {
        BotMovementState entry = heldCandidate(newBot());
        entry.moveBestDist = 120;
        entry.moveProgressAtMs = 5_000L;

        BotCommitmentHold.release(entry);

        assertFalse(entry.heldForCommitment);
        assertEquals(120, entry.moveBestDist, "an unheld move's progress window is untouched");
        assertEquals(5_000L, entry.moveProgressAtMs);
    }

    @Test
    void boundaryResetKeepsTheHoldAndTheDestination() {
        // The observed/offscreen crossing runs resetEntryStateAfterTeleport (demotion, promotion and map
        // change all funnel through it). A commitment in flight must survive that reset unchanged - the
        // boundary is a movement-shadow concern and never owns a commitment (ticket 05, criterion 3).
        BotMovementState entry = heldCandidate(newBot());
        BotCommitmentHold.hold(entry);

        BotMovementManager.resetEntryStateAfterTeleport(entry);
        BotTransitionBoundary.reconstructForObserved(entry);

        assertTrue(entry.heldForCommitment, "the crossing never releases a commitment");
        assertEquals(WALKING_TO, entry.moveTarget, "nor does it drop the destination behind it");
    }

    @Test
    void mapChangeResyncsTheEntryOntoTheLandedMap() {
        // Criterion 2: the resync (and therefore promotion) happens against the map the bot actually
        // landed on - onMapChange is driven by the live map id, and the promotion entry point reads the
        // live map/position, so a bot that travelled offscreen promotes where it stands.
        Character bot = newBot();
        MapleMap map = mockMap(SHOP_MAP_ID);
        bot.setMap(map);
        bot.setPosition(new Point(640, 320));
        BotMovementState entry = heldCandidate(bot);
        entry.lastMapId = 999999999; // the map the bot left, per the driver's own bookkeeping

        GCMovementDriver.onMapChange(entry, bot);

        assertAll(
                () -> assertEquals(SHOP_MAP_ID, entry.lastMapId, "the entry is resynced onto the landed map"),
                () -> assertNull(entry.coarsePlan, "no plan from the previous map survives"),
                () -> assertFalse(entry.coarseActive, "the bot is not moving analytically on arrival"),
                () -> assertNotNull(entry.fhIndex, "the landed map's footholds are indexed"));
    }

    @Test
    void heldBotDoesNotWalkWhileTheCommitmentIsOpen() throws Exception {
        Character bot = newBot();
        MapleMap map = mockMap(SHOP_MAP_ID);
        bot.setMap(map);
        BotMovementState entry = heldCandidate(bot);
        entry.lastMapId = bot.getMapId();
        entry.lastProfileRefreshMs = System.currentTimeMillis(); // not the subject here
        entry.aiTickAccumulatorMs = 0;
        BotCommitmentHold.hold(entry);

        driveOneTick(entry);

        assertAll(
                () -> assertEquals(STANDING_AT, bot.getPosition(), "held: the bot stays where the trade found it"),
                () -> assertEquals(WALKING_TO, entry.moveTarget, "held: the destination is not consumed"),
                () -> assertEquals(0, entry.moveDir, "held: no steering is issued"),
                () -> assertFalse(entry.inAir, "held: no physics runs"),
                () -> assertFalse(entry.coarseActive, "held: no coarse advance either"),
                // The tick's own progress bookkeeping happens after the hold's early return, so this would
                // move for any bot the hold does not cover.
                () -> assertEquals(0, entry.aiTickAccumulatorMs, "held: the tick returns before its own bookkeeping"));
    }

    @Test
    void unheldBotWithTheSameIntentIsDrivenByTheMovementLayer() throws Exception {
        // The control for the test above: same entry, same destination, no commitment - now the tick runs
        // its bookkeeping and the movement layer works the destination instead of freezing the bot.
        Character bot = newBot();
        MapleMap map = mockMap(SHOP_MAP_ID);
        bot.setMap(map);
        BotMovementState entry = heldCandidate(bot);
        entry.lastMapId = bot.getMapId();
        entry.lastProfileRefreshMs = System.currentTimeMillis();
        entry.aiTickAccumulatorMs = 0;

        driveOneTick(entry);

        assertTrue(entry.aiTickAccumulatorMs > 0,
                "unheld: the tick reached its bookkeeping (the hold branch returned, this bot did not)");
    }

    @Test
    void theHoldSelfHealsWhenTheTradeDiesWithoutItsOwner() throws Exception {
        // Both bots can despawn mid-window, an FSM can be stopped and restarted, and GCMovement.enable
        // reuses a live entry - so the movement layer must not trust the flag alone. The trade ref is
        // nulled on every trade death, so it is the authoritative "window is gone" signal: a stale latch
        // releases itself here instead of freezing the bot forever.
        Character bot = newBot();
        MapleMap map = mockMap(SHOP_MAP_ID);
        bot.setMap(map);
        BotMovementState entry = heldCandidate(bot);
        entry.lastMapId = bot.getMapId();
        entry.lastProfileRefreshMs = System.currentTimeMillis();
        entry.aiTickAccumulatorMs = 0;
        BotCommitmentHold.hold(entry);
        bot.setTrade(null); // the window died while this bot's FSM never reached its cleanup

        assertFalse(BotCommitmentHold.stillHolding(entry), "the stale latch is dropped by the driver's own question");
        assertAll(
                () -> assertFalse(entry.heldForCommitment, "and the flag goes with it"),
                () -> assertEquals(WALKING_TO, entry.moveTarget, "the freeze never consumed the destination"),
                () -> assertNotNull(entry.coarsePlan, "nor was a plan torn down for a freeze that never ran"));

        driveOneTick(entry);

        assertTrue(entry.aiTickAccumulatorMs > 0, "the bot is driven again instead of staying frozen");
    }

    @Test
    void aHoldWithoutAnOpenWindowNeverFreezesAnything() {
        // The arming edge only sets a flag, so the question is what actually freezes: a flag armed on a
        // bot with no window (mis-ordered trade teardown, or a reused entry) must be a no-op.
        Character bot = newBot(); // no trade window
        BotMovementState entry = heldCandidateWithoutWindow(bot);

        BotCommitmentHold.hold(entry);

        assertFalse(BotCommitmentHold.stillHolding(entry), "no window -> nothing is held");
        assertFalse(entry.heldForCommitment);
        assertNotNull(entry.coarsePlan, "and nothing the driver owns was torn down");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    /**
     * A bot walking to a destination when a player opened a trade window on it. The window is a real
     * {@link Trade} on the Character, exactly as {@code Trade.inviteTrade} leaves it, because that is the
     * signal the hold self-heals against.
     */
    private static BotMovementState heldCandidate(Character bot) {
        bot.setTrade(new Trade((byte) 1, bot));
        BotMovementState entry = new BotMovementState(bot, null);
        entry.moveTarget = new Point(WALKING_TO);
        entry.farmAnchor = new Point(WALKING_TO);
        entry.farmAnchorMapId = SHOP_MAP_ID;
        entry.moveBestDist = Integer.MAX_VALUE;
        entry.navTargetPos = new Point(900, 400);
        entry.coarseActive = true;
        entry.coarsePlan = mock(MovementPlan.class);
        entry.coarsePlanStartMs = 50L;
        return entry;
    }

    private static BotMovementState heldCandidateWithoutWindow(Character bot) {
        BotMovementState entry = heldCandidate(bot);
        bot.setTrade(null); // the window the fixture opened is already gone
        return entry;
    }

    private static Character newBot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BOT_ID);
        chr.setName("HoldBot");
        chr.setLevel(42);
        chr.setPosition(new Point(STANDING_AT));
        return chr;
    }

    private static MapleMap mockMap(int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getAllPlayers()).thenReturn(List.of());
        FootholdTree footholds = mock(FootholdTree.class);
        when(footholds.getAllFootholds()).thenReturn(List.of());
        when(map.getFootholds()).thenReturn(footholds);
        return map;
    }

    /** The driver's own tick entry point; package-private seam reached through reflection like the driver. */
    private static void driveOneTick(BotMovementState entry) throws ReflectiveOperationException {
        Method tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
        try {
            tick.invoke(null, entry);
        } catch (InvocationTargetException e) {
            throw new AssertionError("driver tick threw", e.getCause());
        }
    }
}
