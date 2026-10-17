package soloMapling.ArtificialPlayer;

import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Ticket 07/5 (recruit acceptance): a follower slot is reserved atomically at accept time, handed back
 * when the accept or the conversion never lands, and the ask-time cap check must see the accepts still
 * in flight. Same file covers the party-side half of the rule - the 6-man cap is taken under the party
 * lock, so a party can never reach 7 members no matter how many accepts race.
 *
 * Live followers are faked into the global bot registry (a mocked BotSM whose type string is what
 * BotRecruitManager's scan reads), which is how the whole rule is exercised without a server, a
 * channel, a wz file or a database. Everything registered here is undone in tearDown - the registry is
 * static state shared with the rest of the test JVM.
 */
class BotRecruitCapacityTest {

    private static final int BOT_ID = 880_100;   // > 20000, in a range no other test or bot plan uses
    private static final int OTHER_BOT_ID = 880_101;
    private static final int NEVER_RESERVED_ID = 880_999;
    private static final int FAKE_FOLLOWER_ID_BASE = 880_200;

    private final List<Integer> registered = new ArrayList<>();
    private final List<Integer> reserved = new ArrayList<>();
    private int nextFollowerId = FAKE_FOLLOWER_ID_BASE;

    @AfterEach
    void tearDown() {
        reserved.forEach(BotRecruitManager::releaseFollowerReservation);
        reserved.clear();
        registered.forEach(CharacterStorage::removeActiveBot);
        registered.clear();
    }

    // ── helpers ──

    private int registerFollower() {
        BotSM bot = mock(BotSM.class);
        when(bot.getBotType()).thenReturn("FollowerBot");
        int id = nextFollowerId++;
        CharacterStorage.addActiveBot(id, bot);
        registered.add(id);
        return id;
    }

    // Follower registrations that already exist for whatever reason are accounted for, so the tests
    // assert against the cap itself rather than against a hopefully-empty global registry.
    private void seedToLeaveOneFreeSlot() {
        assertEquals(0, BotRecruitManager.reservedFollowerCount(), "no reservation may leak between tests");
        int missing = BotRecruitManager.FOLLOWER_CAP - 1 - BotRecruitManager.activeFollowerCount();
        assertTrue(missing >= 0, "test fixture assumes the world starts below the follower cap");
        for (int i = 0; i < missing; i++) {
            registerFollower();
        }
        assertEquals(BotRecruitManager.FOLLOWER_CAP - 1, BotRecruitManager.activeFollowerCount());
    }

    private boolean reserve(int botId) {
        boolean taken = BotRecruitManager.tryReserveFollower(botId);
        if (taken && !reserved.contains(botId)) {
            reserved.add(botId);
        }
        return taken;
    }

    private int dropFollower() {
        int id = registered.remove(0);
        CharacterStorage.removeActiveBot(id);
        return id;
    }

    // ── follower capacity reservations ──

    @Test
    void capAccountingCountsLiveFollowersPlusAcceptsInFlight() {
        // The world is filled to CAP-1 with real followers first: only then does "the next accept is
        // refused" mean something about the cap instead of about how small this test's registry is.
        seedToLeaveOneFreeSlot();
        assertEquals(BotRecruitManager.FOLLOWER_CAP - 1, BotRecruitManager.activeFollowerCount(),
                "followers that already exist count towards the cap");

        assertTrue(reserve(BOT_ID));
        assertEquals(1, BotRecruitManager.reservedFollowerCount());
        assertEquals(BotRecruitManager.FOLLOWER_CAP, BotRecruitManager.activeFollowerCount(),
                "an accept that has not converted yet still occupies its slot");

        assertFalse(BotRecruitManager.tryReserveFollower(NEVER_RESERVED_ID),
                "the last slot is spoken for, so the next accept must be refused");
        assertEquals(1, BotRecruitManager.reservedFollowerCount(),
                "a refused accept must not leave a claim behind");

        BotRecruitManager.releaseFollowerReservation(BOT_ID);
        reserved.remove((Integer) BOT_ID);
        assertEquals(BotRecruitManager.FOLLOWER_CAP - 1, BotRecruitManager.activeFollowerCount(),
                "a released slot is free again");
    }

    @Test
    void askTimeCheckSeesTheAcceptThatIsStillInFlight() {
        seedToLeaveOneFreeSlot();
        assertTrue(BotRecruitManager.activeFollowerCount() < BotRecruitManager.FOLLOWER_CAP,
                "the last slot is still offered while nobody holds it");

        assertTrue(reserve(BOT_ID), "the ask-time check passed, so the accept must get the slot");

        assertEquals(BotRecruitManager.FOLLOWER_CAP, BotRecruitManager.activeFollowerCount(),
                "while the bot is still a SocialBot the slot it holds must close the cap for everyone else");
    }

    @Test
    void theLastFreeSlotIsReservedExactlyOnceWhenTwoAcceptsRace() throws Exception {
        seedToLeaveOneFreeSlot();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> first = pool.submit(() -> {
                    start.await();
                    return BotRecruitManager.tryReserveFollower(BOT_ID);
                });
                Future<Boolean> second = pool.submit(() -> {
                    start.await();
                    return BotRecruitManager.tryReserveFollower(OTHER_BOT_ID);
                });
                start.countDown();

                boolean firstWon = first.get(10, TimeUnit.SECONDS);
                boolean secondWon = second.get(10, TimeUnit.SECONDS);
                assertTrue(firstWon ^ secondWon,
                        "two accepts racing for one free slot: exactly one may take it (round " + round + ")");
                assertEquals(1, BotRecruitManager.reservedFollowerCount(),
                        "round " + round + ": the loser must not leave a claim behind");
                assertEquals(BotRecruitManager.FOLLOWER_CAP, BotRecruitManager.activeFollowerCount(),
                        "round " + round + ": the cap is reached by live followers plus the one claim");

                // hand the slot back so the next round starts from the same state; releasing a bot that
                // holds nothing (the loser) must be harmless
                BotRecruitManager.releaseFollowerReservation(BOT_ID);
                BotRecruitManager.releaseFollowerReservation(OTHER_BOT_ID);
                assertEquals(0, BotRecruitManager.reservedFollowerCount(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRefusedAcceptLeavesNoSlotBehindAndFreesItWhenAFollowerDeparts() {
        seedToLeaveOneFreeSlot();
        assertTrue(reserve(BOT_ID));

        assertFalse(BotRecruitManager.tryReserveFollower(OTHER_BOT_ID),
                "a full world must refuse the second accept, not queue it");
        assertEquals(1, BotRecruitManager.reservedFollowerCount(),
                "the refused accept must not leave a claim behind");
        assertFalse(BotRecruitManager.tryReserveFollower(NEVER_RESERVED_ID));

        dropFollower();
        assertTrue(reserve(OTHER_BOT_ID), "the slot a departing follower freed is immediately available");
        assertEquals(BotRecruitManager.FOLLOWER_CAP, BotRecruitManager.activeFollowerCount());
    }

    @Test
    void reservingTwiceForTheSameBotHoldsOneSlot() {
        registerFollower();

        assertTrue(reserve(BOT_ID));
        assertTrue(BotRecruitManager.tryReserveFollower(BOT_ID),
                "a bot that already holds its slot is not refused");
        assertEquals(1, BotRecruitManager.reservedFollowerCount(),
                "re-reserving for the same bot must not consume a second slot");

        BotRecruitManager.releaseFollowerReservation(BOT_ID);
        assertEquals(0, BotRecruitManager.reservedFollowerCount(),
                "one release must free that one slot, not two");
    }

    @Test
    void releaseIsSafeWhenNothingWasHeld() {
        int liveBefore = BotRecruitManager.activeFollowerCount();
        registerFollower();
        registerFollower();

        BotRecruitManager.releaseFollowerReservation(NEVER_RESERVED_ID);
        assertEquals(0, BotRecruitManager.reservedFollowerCount(), "releasing a bot that holds nothing is a no-op");

        assertTrue(reserve(BOT_ID));
        BotRecruitManager.releaseFollowerReservation(BOT_ID);
        BotRecruitManager.releaseFollowerReservation(BOT_ID);
        assertEquals(0, BotRecruitManager.reservedFollowerCount(), "a double release must not go negative");
        assertEquals(liveBefore + 2, BotRecruitManager.activeFollowerCount(), "releases never touch the live scan");
    }

    // ── party size cap (the same accept also has to land in a party that stays <= 6) ──

    @Test
    void partyTakesTheSixthMemberAndRefusesTheSeventh() {
        PartyCharacter leader = new PartyCharacter();
        Party party = new Party(4242, leader);

        assertTrue(party.addMember(leader), "the leader takes the first slot");
        for (int i = 1; i < Party.MAX_MEMBERS - 1; i++) {
            assertTrue(party.addMember(new PartyCharacter()), "member " + (i + 1) + " still fits");
        }
        assertEquals(5, party.getMembers().size());
        assertTrue(party.addMember(new PartyCharacter()), "the sixth member is still accepted");
        assertEquals(Party.MAX_MEMBERS, party.getMembers().size());

        assertFalse(party.addMember(new PartyCharacter()), "the seventh member must be refused");
        assertEquals(Party.MAX_MEMBERS, party.getMembers().size(), "a refused join adds nobody");
        assertEquals(6, Party.MAX_MEMBERS, "callers (invite handler, joinParty) hardcode the same six");
    }

    @Test
    void partyCapHoldsWhenMembersJoinAtOnce() throws Exception {
        PartyCharacter leader = new PartyCharacter();
        Party party = new Party(4243, leader);
        assertTrue(party.addMember(leader));
        for (int i = 0; i < Party.MAX_MEMBERS - 2; i++) {
            assertTrue(party.addMember(new PartyCharacter()));
        }
        assertEquals(5, party.getMembers().size(), "one slot left for the race");

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> joins = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                joins.add(pool.submit(() -> {
                    start.await();
                    return party.addMember(new PartyCharacter());
                }));
            }
            start.countDown();

            int admitted = 0;
            for (Future<Boolean> join : joins) {
                if (join.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(1, admitted, "what the earlier size check let through must be settled by the lock");
            assertEquals(Party.MAX_MEMBERS, party.getMembers().size(), "a party can never reach 7 members");
        } finally {
            pool.shutdownNow();
        }
    }
}
