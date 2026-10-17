package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import net.server.world.Party;
import org.mockito.ArgumentCaptor;
import server.Trade;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager.RecruitAnswer;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * Ticket 04 (durability boundary): the COARSE -> FULL/HALO promotion is the only place a bot changes
 * mode, so it is the only place the durable and reconstructible sets could bleed into each other. These
 * tests drive the real promotion entry point (GCMovementDriver.reconstructPhysicsFromCoarse) and assert
 * both halves: every mode-scoped field is rebuilt, and identity/progression/commitments/lifecycle are
 * untouched.
 */
class BotTransitionBoundaryTest {

    private static final int BOT_ID = 21000; // > 20000 so BotHelpers.isBot() is true
    private static final int RECRUITER_ID = 777;
    private static final int BUYER_ID = 22000; // second bot, so the sale runs the real bot-buy path
    private static final int TRAIN_MAP_ID = 100000002;
    private static final int POTION = 2000000;
    private static final int SPOT_MAP_ID = 100000002;
    private static final int SPOT_ID = 3;
    private static final Point LANDED_AT = new Point(777, 888);

    private static Character newBot() {
        return newBot(BOT_ID, "DurabilityBot");
    }

    private static Character newBot(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(42);
        chr.setExp(1234);
        chr.setPosition(new Point(LANDED_AT));
        return chr;
    }

    /*
     * The state of a bot that has been moving offscreen: the coarse plan the analytic executor was
     * advancing, plus every active-mode field that was last written while the map still had a viewer.
     */
    private static BotMovementState offscreenState(Character chr) {
        BotMovementState entry = new BotMovementState(chr, null);
        entry.coarseActive = true;
        entry.coarsePlan = mock(MovementPlan.class);
        entry.coarsePlanStartMs = 111L;
        entry.coarsePlanTarget = new Point(999, 999);
        entry.coarsePlanMapId = TRAIN_MAP_ID;

        entry.attackCooldownMs = 900;
        entry.navTargetPos = new Point(50, 60);
        entry.graphWarmupFallback = true;
        entry.observedOwnerStepX = 3;
        entry.observedOwnerStepY = 4;
        entry.movementBroadcastValid = true;
        entry.navBlockedPosTicks = 9;
        entry.navBlockedPosGiveUpTicks = 4;
        entry.navBlockedPosX = 15;
        entry.navBlockedPosY = 16;

        entry.mobHitCooldownMs = 2500;
        entry.lastMobTouchCheckPos = new Point(10, 10);
        entry.lastMobTouchMapId = TRAIN_MAP_ID;

        entry.alertedUntilMs = System.currentTimeMillis() + 5000;
        entry.alertResetScheduled = true;

        entry.stuckMs = 5000;
        entry.unstuckCooldownMs = 1500;
        entry.stuckCheckX = 11;
        entry.stuckCheckY = 12;
        entry.airStuckTicks = 7;
        entry.airStuckX = 13;
        entry.airStuckY = 14;
        entry.moveBestDist = 120;
        entry.moveProgressAtMs = 5000L;

        long future = System.currentTimeMillis() + 60_000;
        entry.duckUntilMs = future;
        entry.reactingUntilMs = future;
        entry.nextPlayerScanMs = future;
        entry.aiTickAccumulatorMs = 777;
        return entry;
    }

    /* The same call the driver makes on the promotion edge (GCMovementDriver.tick -> reconstruct...). */
    private static void promote(BotMovementState entry, Character chr) {
        GCMovementDriver.reconstructPhysicsFromCoarse(entry, chr);
    }

    private static int heldPotions(Character bot) {
        int total = 0;
        for (Item item : bot.getInventory(InventoryType.USE).listById(POTION)) {
            total += item.getQuantity();
        }
        return total;
    }

    private static int settledMesos(Character bot) {
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.settle(bot);
        ArgumentCaptor<Integer> dropped = ArgumentCaptor.forClass(Integer.class);
        verify(map, times(1)).spawnMesoDrop(dropped.capture(), any(Point.class), eq(bot), eq(bot), eq(true),
                eq((byte) 2), eq((short) 0));
        return dropped.getValue();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name, Character arg) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, Character.class);
        method.setAccessible(true);
        method.invoke(target, arg);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void promotionRebuildsEveryModeScopedFieldInsteadOfCarryingIt() {
        Character chr = newBot();
        BotMovementState entry = offscreenState(chr);

        promote(entry, chr);

        // assertAll so a regression reports every field that was carried instead of stopping at the first.
        assertAll("reconstructible state",
                () -> assertFalse(entry.coarseActive, "coarseActive"),
                () -> assertNull(entry.coarsePlan, "coarsePlan"),
                () -> assertEquals(0L, entry.coarsePlanStartMs, "coarsePlanStartMs"),
                () -> assertNull(entry.coarsePlanTarget, "coarsePlanTarget"),
                () -> assertEquals(-1, entry.coarsePlanMapId, "coarsePlanMapId"),

                // trail: attack cooldown, nav leg, observed-owner step, broadcast dedup
                () -> assertEquals(0, entry.attackCooldownMs, "attackCooldownMs"),
                () -> assertNull(entry.navTargetPos, "navTargetPos"),
                () -> assertFalse(entry.graphWarmupFallback, "graphWarmupFallback"),
                () -> assertEquals(0, entry.observedOwnerStepX, "observedOwnerStepX"),
                () -> assertEquals(0, entry.observedOwnerStepY, "observedOwnerStepY"),
                () -> assertFalse(entry.movementBroadcastValid, "movementBroadcastValid"),
                // the blocked-nav tracker rides along with the nav leg (BotMovementManager.clearNavigationState)
                () -> assertEquals(0, entry.navBlockedPosTicks, "navBlockedPosTicks"),
                () -> assertEquals(0, entry.navBlockedPosGiveUpTicks, "navBlockedPosGiveUpTicks"),
                () -> assertEquals(Integer.MIN_VALUE, entry.navBlockedPosX, "navBlockedPosX"),
                () -> assertEquals(Integer.MIN_VALUE, entry.navBlockedPosY, "navBlockedPosY"),

                // contact damage: i-frames, and the swept anchor re-seeded from the position the bot ended
                // up at. With no map in this fixture findGroundPoint returns null and the promotion teleport
                // is a no-op, so this pins the value; the ordering against a real snap is the next test plus
                // the sandbox's real-WZ probe.
                () -> assertEquals(0, entry.mobHitCooldownMs, "mobHitCooldownMs"),
                () -> assertEquals(LANDED_AT, entry.lastMobTouchCheckPos, "lastMobTouchCheckPos"),
                () -> assertEquals(chr.getMapId(), entry.lastMobTouchMapId, "lastMobTouchMapId"),

                // combat presentation: the ALERT window is dropped, but the owner's one-shot reset guard is
                // left exactly as the owner left it (fixture arms it), because a false here would let a
                // second reset task be armed while the owner's first one is still in flight.
                () -> assertEquals(0L, entry.alertedUntilMs, "alertedUntilMs"),
                () -> assertTrue(entry.alertResetScheduled, "alertResetScheduled stays the owner's"),

                // watchdog
                () -> assertEquals(0, entry.stuckMs, "stuckMs"),
                () -> assertEquals(0, entry.unstuckCooldownMs, "unstuckCooldownMs"),
                () -> assertEquals(Integer.MIN_VALUE, entry.stuckCheckX, "stuckCheckX"),
                () -> assertEquals(Integer.MIN_VALUE, entry.stuckCheckY, "stuckCheckY"),
                () -> assertEquals(0, entry.airStuckTicks, "airStuckTicks"),
                () -> assertEquals(Integer.MIN_VALUE, entry.airStuckX, "airStuckX"),
                () -> assertEquals(Integer.MIN_VALUE, entry.airStuckY, "airStuckY"),
                () -> assertEquals(Integer.MAX_VALUE, entry.moveBestDist, "moveBestDist"),
                () -> assertEquals(0L, entry.moveProgressAtMs, "moveProgressAtMs"),

                // tick debounce + cosmetic pauses
                () -> assertEquals(0, entry.aiTickAccumulatorMs, "aiTickAccumulatorMs"),
                () -> assertEquals(0L, entry.duckUntilMs, "duckUntilMs"),
                () -> assertEquals(0L, entry.reactingUntilMs, "reactingUntilMs"),
                () -> assertEquals(0L, entry.nextPlayerScanMs, "nextPlayerScanMs"));
    }

    @Test
    void promotionLeavesIdentityAndProgressionAlone() {
        Character bot = spy(newBot());
        clearInvocations(bot);
        BotMovementState entry = offscreenState(bot);

        promote(entry, bot);

        verify(bot, never()).setLevel(anyInt());
        verify(bot, never()).setExp(anyInt());
        verify(bot, never()).setName(anyString());
        verify(bot, never()).setJob(any());
        verify(bot, never()).gainExp(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(bot, never()).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(bot, never()).setParty(any());
        verify(bot, never()).setTrade(any());
        assertEquals(BOT_ID, bot.getId());
        assertEquals("DurabilityBot", bot.getName());
        assertEquals(42, bot.getLevel());
        assertEquals(1234, bot.getExp());
    }

    @Test
    void promotionLeavesTheObservationGatedPacingGatesAlone() throws Exception {
        Character chr = newBot();
        chr.setMap(mock(MapleMap.class));
        TrainingBot train = new TrainingBot(chr);

        // Unobserved: the buff gate must not arm itself offscreen. That is what keeps a transition from
        // replaying a backlog, and it is why the boundary has nothing to rebuild here (criterion 3's
        // "chatter/buff pacing" is owned by its observation-aware writers, not by this edge).
        assertEquals(0L, getField(train, "nextBuffMs"), "gate starts unarmed");
        invoke(train, "maybeSelfBuff", chr);
        assertEquals(0L, getField(train, "nextBuffMs"), "unobserved buff attempt must not arm the gate");
        invoke(train, "maybeGrindChatter", chr);
        // Chatter advances its gate on the wall clock even unobserved (TrainingBot.maybeGrindChatter:985
        // runs before the observation check), which is what stops an offscreen span from banking lines:
        // the promotion tick can only ever say nothing, or one line.
        assertTrue((Long) getField(train, "nextChatterMs") > 0L, "chatter gate advances on the wall clock");

        setField(train, "nextBuffMs", 777L);
        setField(train, "nextChatterMs", 888L);

        promote(offscreenState(chr), chr);

        assertEquals(777L, getField(train, "nextBuffMs"));
        assertEquals(888L, getField(train, "nextChatterMs"));
    }

    @Test
    void theSweptTouchAnchorReadsTheLandedPositionNotTheCoarsePlan() {
        Character chr = newBot();
        BotMovementState entry = offscreenState(chr);

        promote(entry, chr);

        // A promotion that snaps the bot to a different pixel than the coarse plan's interpolation must
        // move the anchor with it: the anchor is seeded from the bot after the teleport, and it is a copy,
        // so live position updates cannot rewrite the trace that was already swept.
        Point snapped = new Point(1000, 1100);
        chr.setPosition(snapped);
        BotTransitionBoundary.reseedObservedTrace(entry, chr);

        assertAll("swept-touch anchor",
                () -> assertEquals(snapped, entry.lastMobTouchCheckPos, "follows the bot"),
                () -> assertNotSame(chr.getPosition(), entry.lastMobTouchCheckPos, "is a copy"),
                () -> assertEquals(chr.getMapId(), entry.lastMobTouchMapId, "map id"));
    }

    @Test
    void promotionDoesNotTouchTheTrainingBotsDurableProgression() throws Exception {
        Character chr = newBot();
        chr.setMap(mock(MapleMap.class));
        TrainingBot train = new TrainingBot(chr);
        setField(train, "currentTrainMapId", TRAIN_MAP_ID);
        setField(train, "currentMobLevel", 8);
        setField(train, "grindUntilMs", 555_000L);
        setField(train, "lastExpAccrualMs", 444_000L);
        setField(train, "lastKnownLevel", 41);
        Object phase = getField(train, "phase");

        promote(offscreenState(chr), chr);

        assertEquals(TRAIN_MAP_ID, getField(train, "currentTrainMapId"));
        assertEquals(8, getField(train, "currentMobLevel"));
        assertEquals(555_000L, getField(train, "grindUntilMs"));
        assertEquals(444_000L, getField(train, "lastExpAccrualMs"));
        assertEquals(41, getField(train, "lastKnownLevel"));
        assertSame(phase, getField(train, "phase"));
    }

    @Test
    void anOpenTradeSurvivesPromotionAndStillSettlesExactlyOnce() {
        Character bot = newBot();
        Trade.startTrade(bot);
        Trade trade = bot.getTrade();
        assertNotNull(trade);
        assertTrue(trade.addItem(new Item(POTION, (short) 1, (short) 5, -1)));
        assertTrue(BotHoldings.grantMeso(bot, 1000));
        trade.setMeso(400);

        promote(offscreenState(bot), bot);

        assertSame(trade, bot.getTrade());
        assertEquals(600, bot.getMeso());
        // The offer stays window value: the bot never held these potions, so promotion must not start
        // holding them (a credit here is exactly the laundering regression fixed in ticket 01).
        assertEquals(1, trade.getItems().size());
        assertEquals(POTION, trade.getItems().get(0).getItemId());
        assertEquals(0, heldPotions(bot));

        Trade.declineTrade(bot);

        assertNull(bot.getTrade());
        assertEquals(1000, bot.getMeso());
        assertEquals(0, heldPotions(bot)); // a declined window offer must not come home as a real item
        assertEquals(1000, settledMesos(bot)); // ...and the ledger followed the wallet exactly once
    }

    @Test
    void pendingRecruitStateSurvivesPromotion() {
        BotRecruitManager.clearHandoffs(BOT_ID);
        Character bot = newBot();
        BotRecruitManager.setPendingLeader(BOT_ID, 4242);
        BotRecruitManager.markStationHere(BOT_ID);
        // The invite window the doc names (BotTransitionBoundary.java comments BotRecruitManager.ARMED as
        // durable): arm it through the real decision so the assertion cannot drift from the producer.
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(RECRUITER_ID);
        when(player.getName()).thenReturn("Recruiter");
        assertEquals(RecruitAnswer.ACCEPTED, BotRecruitManager.rollPartyAsk(bot, player, 1.0, false));
        assertTrue(BotRecruitManager.isArmed(BOT_ID), "fixture must be armed before promotion");

        promote(offscreenState(bot), bot);

        // An armed window is a wall-clock deadline the bot still owns, on the same map: promotion may
        // neither disarm it nor hand it to another player.
        assertTrue(BotRecruitManager.isArmed(BOT_ID), "invite window survives promotion");
        assertEquals(RECRUITER_ID, BotRecruitManager.armedInviterId(BOT_ID), "still armed for the same player");
        assertEquals(4242, BotRecruitManager.consumePendingLeader(BOT_ID));
        assertTrue(BotRecruitManager.consumeStationHere(BOT_ID));
    }

    @Test
    void promotionKeepsTheBotsMapMembership() {
        Character bot = newBot();
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);

        promote(offscreenState(bot), bot);

        assertSame(map, bot.getMap());
        verify(map, never()).addPlayer(any());
        verify(map, never()).removePlayer(any());
    }

    /*
     * Ticket 04 criterion 2 names seller stock / sold / credited mesos. The other commitment assertions are
     * identity + "no calls on the mock"; this case plants real business values on a real PlayerShop (the shape
     * a bot stall actually has - ArtificialFreeMarket.BotPlayerStorePermit builds one for its owner) and reads
     * them back after the flip, because a mock with no stock can only ever prove "untouched the nothing".
     */
    @Test
    void aCompletedSaleStaysOnTheSellersBooksAcrossPromotion() {
        Character seller = newBot();
        PlayerShop shop = new PlayerShop(seller, "DurabilityBot's store", POTION);
        PlayerShopItem stock = new PlayerShopItem(new Item(POTION, (short) 1, (short) 5, -1), (short) 3, 250);
        assertTrue(shop.addItem(stock));
        seller.setPlayerShop(shop);
        BotHoldings.grantMeso(seller, 1000);

        Character buyer = newBot(BUYER_ID, "BuyerBot");
        BotHoldings.grantMeso(buyer, 5000);
        assertTrue(shop.botBuy(buyer, stock, 0, (short) 1));

        int credited = 1000 + 250 - Trade.getFee(250);
        assertEquals(credited, seller.getMeso(), "the seller was credited for the sale");
        assertEquals(1, shop.getSold().size(), "the sale is on the books");
        assertEquals((short) 2, stock.getBundles(), "the unsold bundles are still on offer");

        promote(offscreenState(seller), seller);

        // Business state is a commitment: the boundary may not refund it, re-list it, or wipe the ledger.
        assertEquals(credited, seller.getMeso(), "credited mesos survive promotion");
        assertEquals(1, shop.getSold().size(), "the sale stays on the books");
        assertSame(stock, shop.getItems().get(0), "the offer is the same shop item");
        assertEquals((short) 2, stock.getBundles(), "unsold bundles survive promotion");
        assertSame(shop, seller.getPlayerShop(), "the stall is still owned");
    }

    @Test
    void promotionLeavesPartySellerAndSpotCommitmentsAlone() {
        Character bot = newBot();
        bot.setMap(mock(MapleMap.class));
        Party party = mock(Party.class);
        bot.setParty(party);
        PlayerShop playerShop = mock(PlayerShop.class);
        bot.setPlayerShop(playerShop);
        HiredMerchant merchant = mock(HiredMerchant.class);
        bot.setHiredMerchant(merchant);
        int slot = BotSpotClaims.claim(SPOT_MAP_ID, SPOT_ID, 2, BOT_ID);

        promote(offscreenState(bot), bot);

        assertSame(party, bot.getParty());
        assertSame(playerShop, bot.getPlayerShop());
        assertSame(merchant, bot.getHiredMerchant());
        // A spot claim is a lifecycle handoff, not movement state: the same bot still holds the same slot.
        assertEquals(slot, BotSpotClaims.claim(SPOT_MAP_ID, SPOT_ID, 2, BOT_ID));
        assertEquals(1, BotSpotClaims.holders(SPOT_MAP_ID, SPOT_ID));
        // Neither a stall nor a merchant may be closed, refunded or saved by a tier flip.
        verifyNoInteractions(party, playerShop, merchant);
        BotSpotClaims.release(SPOT_MAP_ID, SPOT_ID, BOT_ID);
    }
}
