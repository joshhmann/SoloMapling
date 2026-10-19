package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotPopulationDirectorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void theCurveInterpolatesWrapsPastMidnightAndLiftsWeekends() {
        BotPopulationCurve curve = BotPopulationCurve.parse("6:0.2,20:1.0", 1.2);
        assertEquals(0.2, curve.fractionAt(6), 1e-9);
        assertEquals(1.0, curve.fractionAt(20), 1e-9);
        assertEquals(0.6, curve.fractionAt(13), 1e-9);
        // 20h -> 6h next day is 10 hours; 1am is half way.
        assertEquals(0.6, curve.fractionAt(1), 1e-9);
        ZonedDateTime friday = ZonedDateTime.of(2026, 10, 16, 13, 0, 0, 0, UTC);
        ZonedDateTime saturday = friday.plusDays(1);
        assertEquals(600, curve.target(1000, friday));
        assertEquals(720, curve.target(1000, saturday));
        assertEquals(1000, curve.target(1000, saturday.withHour(20)), "the lift never passes the peak");
        assertEquals(1000, BotPopulationCurve.parse("flat", 1.5).target(1000, saturday));
        assertThrows(IllegalArgumentException.class, () -> BotPopulationCurve.parse("25:1", 1));
    }

    /** A roster that lives through simulated days on 30 s ticks, driven only by {@link BotPopulationDirector#plan}. */
    private static final class World {
        final List<BotPersonality.Traits> traits = new ArrayList<>();
        final boolean[] online;
        final long[] loginAt;
        final long[] logoutAt;
        final Set<Integer> pinned = new HashSet<>();
        int maxLoginsSeen;

        World(int size) {
            for (int i = 0; i < size; i++) {
                traits.add(BotPersonality.generate(i, null));
            }
            online = new boolean[size];
            loginAt = new long[size];
            logoutAt = new long[size];
        }

        List<BotPopulationDirector.Candidate> candidates() {
            List<BotPopulationDirector.Candidate> list = new ArrayList<>();
            for (int i = 0; i < traits.size(); i++) {
                BotPersonality.Traits t = traits.get(i);
                list.add(new BotPopulationDirector.Candidate(i, online[i], true, loginAt[i], logoutAt[i],
                        t.playWindowStart(), t.playWindowLen(), t.sessionMinutes(), !pinned.contains(i)));
            }
            return list;
        }

        int onlineCount() {
            int n = 0;
            for (boolean b : online) {
                n += b ? 1 : 0;
            }
            return n;
        }

        BotPopulationDirector.Plan tick(ZonedDateTime when, BotPopulationCurve curve, int peak, int maxLogins,
                                        Random rng) {
            long now = when.toInstant().toEpochMilli();
            BotPopulationDirector.Plan plan = BotPopulationDirector.plan(candidates(), curve.target(peak, when),
                    when.getHour() * 60 + when.getMinute(), now, maxLogins, 25, rng);
            for (int id : plan.logouts()) {
                assertFalse(pinned.contains(id), "a bot holding a commitment was logged out");
                online[id] = false;
                logoutAt[id] = now;
            }
            for (int id : plan.logins()) {
                online[id] = true;
                loginAt[id] = now;
            }
            maxLoginsSeen = Math.max(maxLoginsSeen, plan.logins().size());
            return plan;
        }
    }

    @Test
    void theOnlineCountTracksTheCurveAcrossSimulatedDays() {
        World world = new World(1500);
        BotPopulationCurve curve = BotPopulationCurve.parse(BotPopulationCurve.DEFAULT, 1.0);
        int peak = 600;
        Random rng = new Random(11);
        ZonedDateTime when = ZonedDateTime.of(2026, 10, 14, 0, 0, 0, 0, UTC);
        world.tick(when, curve, peak, Integer.MAX_VALUE, rng); // boot fill
        int samples = 0;
        double worstMiss = 0;
        for (int t = 1; t < 2 * 24 * 120; t++) {
            when = when.plusSeconds(30);
            world.tick(when, curve, peak, 25, rng);
            if (when.getMinute() == 0 && when.getSecond() == 0 && t > 120) {
                int target = curve.target(peak, when);
                double miss = Math.abs(world.onlineCount() - target) / (double) peak;
                worstMiss = Math.max(worstMiss, miss);
                samples++;
            }
        }
        assertTrue(samples >= 40);
        assertTrue(worstMiss < 0.06, "online count strayed " + Math.round(worstMiss * 100) + "% of peak from the curve");
        assertTrue(world.maxLoginsSeen <= 25 || world.maxLoginsSeen == curve.target(peak,
                ZonedDateTime.of(2026, 10, 14, 0, 0, 0, 0, UTC)), "logins burst past the per-tick cap");
    }

    @Test
    void theSameRegularsShowUpAtTheSameHourDayAfterDay() {
        World world = new World(1500);
        BotPopulationCurve curve = BotPopulationCurve.parse(BotPopulationCurve.DEFAULT, 1.0);
        int peak = 500;
        Random rng = new Random(5);
        ZonedDateTime when = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, UTC);
        world.tick(when, curve, peak, Integer.MAX_VALUE, rng);
        Map<Integer, Set<Integer>> atEightPm = new HashMap<>();
        for (int t = 1; t < 3 * 24 * 120; t++) {
            when = when.plusSeconds(30);
            world.tick(when, curve, peak, 25, rng);
            if (when.getHour() == 20 && when.getMinute() == 0 && when.getSecond() == 0) {
                Set<Integer> seen = new HashSet<>();
                for (int i = 0; i < world.online.length; i++) {
                    if (world.online[i]) {
                        seen.add(i);
                    }
                }
                atEightPm.put(when.getDayOfMonth(), seen);
            }
        }
        Set<Integer> day2 = atEightPm.get(15);
        Set<Integer> day3 = atEightPm.get(16);
        Set<Integer> both = new HashSet<>(day2);
        both.retainAll(day3);
        double overlap = both.size() / (double) day2.size();
        double byChance = day3.size() / 1500.0;
        // ~0.51 vs ~0.33: more bots usually play at 8pm than there are slots, so regulars recur often, not always.
        assertTrue(overlap > byChance * 1.3, "regulars: " + overlap + " vs " + byChance + " by chance");
    }

    @Test
    void aBotHoldingACommitmentIsNeverLoggedOutAndARecentLogoutWaitsBeforeReturning() {
        World world = new World(300);
        for (int i = 0; i < 300; i += 3) {
            world.pinned.add(i);
        }
        BotPopulationCurve high = BotPopulationCurve.parse("flat", 1.0);
        BotPopulationCurve low = BotPopulationCurve.parse("0:0.1", 1.0);
        Random rng = new Random(9);
        ZonedDateTime when = ZonedDateTime.of(2026, 10, 14, 19, 0, 0, 0, UTC);
        world.tick(when, high, 300, Integer.MAX_VALUE, rng);
        for (int t = 0; t < 200; t++) {
            when = when.plusSeconds(30);
            world.tick(when, low, 300, 25, rng); // pinned bots must stay put while the rest drain
        }
        for (int id : world.pinned) {
            assertTrue(world.online[id], "pinned bot " + id + " left");
        }
        long now = when.toInstant().toEpochMilli();
        BotPopulationDirector.Plan refill = world.tick(when.plusSeconds(30), high, 300, 1000, rng);
        for (int id : refill.logins()) {
            assertTrue(world.logoutAt[id] == 0 || now + 30_000 - world.logoutAt[id] >= BotPopulationDirector.RELOG_COOLDOWN_MS,
                    "bot " + id + " logged back in during its cooldown");
        }
    }

    @Test
    void playersLoggedOutByARestartComeStraightBack() {
        long now = TimeUnit.DAYS.toMillis(30);
        long justNow = now - TimeUnit.MINUTES.toMillis(3);
        List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // Logged out three minutes ago: half by the director (cooldown), half by the server going down.
            roster.add(new BotPopulationDirector.Candidate(i, false, true, justNow - 1000, justNow, 0, 1440, 60, true,
                    i < 10));
        }
        BotPopulationDirector.Plan plan = BotPopulationDirector.plan(roster, 20, 600, now, Integer.MAX_VALUE, 25,
                new Random(1));
        assertEquals(10, plan.logins().size());
        assertTrue(plan.logins().stream().allMatch(id -> id >= 10), "only the restarted players are back already");
    }

    @Test
    void aFinishedSessionIsReplacedSoFacesChangeWhileTheCountHolds() {
        List<BotPopulationDirector.Candidate> roster = new ArrayList<>();
        long now = TimeUnit.DAYS.toMillis(30);
        long longAgo = now - TimeUnit.HOURS.toMillis(10);
        for (int i = 0; i < 10; i++) {
            roster.add(new BotPopulationDirector.Candidate(i, true, true, longAgo, 0, 0, 1440, 60, true));
        }
        for (int i = 10; i < 30; i++) {
            roster.add(new BotPopulationDirector.Candidate(i, false, true, 0, 0, 0, 1440, 60, true));
        }
        BotPopulationDirector.Plan plan = BotPopulationDirector.plan(roster, 10, 600, now, 25, 25, new Random(1));
        assertEquals(10, plan.logouts().size(), "every overstayed session ends");
        assertEquals(10, plan.logins().size(), "and is replaced by someone else");
        assertTrue(plan.logins().stream().allMatch(id -> id >= 10));
    }
}
