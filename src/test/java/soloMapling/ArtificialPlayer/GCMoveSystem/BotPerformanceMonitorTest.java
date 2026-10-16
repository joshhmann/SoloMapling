package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Cost attribution: the report must split a running population's cost into ACTIVE (crowded,
 * interactive), BACKGROUND (sparse, observer-side) and TRANSITION (tier changes), break ACTIVE down
 * per phase, and read as a rate over the window since the previous report so two readings taken
 * seconds apart distinguish a sparse run from a crowded one.
 */
class BotPerformanceMonitorTest {

    /* One parsed report line: "<BUCKET> <phase|total>: <rate> ms/s (<pct>% of window) nanos=N samples=N [units=N] [nested=1]". */
    private record Line(String bucket, String phase, double msPerSec, double percent, long nanos, long samples,
                        long units, boolean nested) {
    }

    private static final Pattern WINDOW = Pattern.compile("window: ([0-9.]+)s");
    private static final Pattern MS_PER_SEC = Pattern.compile("([0-9.]+) ms/s");
    private static final Pattern PERCENT = Pattern.compile("([0-9.]+)% of window");
    private static final Pattern NANOS = Pattern.compile("nanos=(-?[0-9]+)");
    private static final Pattern SAMPLES = Pattern.compile("samples=(-?[0-9]+)");
    private static final Pattern UNITS = Pattern.compile("units=(-?[0-9]+)");
    private static final Pattern NESTED = Pattern.compile("nested=1");

    @Test
    void bucketTotalsAndActivePhaseSplit() {
        // Pre-change this was a no-op stub (no report(), empty record/recordPathfind); the assertions below
        // fail there, and the hooks the rest of this test exercises did not exist.

        // Start from a clean window: sets the delta baseline for every phase.
        BotPerformanceMonitor.report();

        // ACTIVE: one ground-move tick with a physics step inside it, a movement broadcast plus its fan-out,
        // and a combat tick's scan / damage / loot.
        BotPerformanceMonitor.record("move-ground", 1_000_000L);
        BotPerformanceMonitor.recordPhysicsStep(500_000L);
        BotPerformanceMonitor.recordPhysicsStep(500_000L);
        BotPerformanceMonitor.record("broadcast-move", 300_000L);
        BotPerformanceMonitor.recordBroadcastFanout(7);
        BotPerformanceMonitor.recordBroadcastRaw(200_000L);
        BotPerformanceMonitor.recordCombatScan(2_000_000L);
        BotPerformanceMonitor.recordCombatDamage(3_000_000L);
        BotPerformanceMonitor.recordCombatLoot(4_000_000L);

        // BACKGROUND: the per-bot state tick (carrying the idle refresh, the tick-wheel nudge and the offscreen
        // advance), the movement tick (carrying nav-resolve and pathfind), and the observer poll.
        BotPerformanceMonitor.recordMacroTick(12_000_000L);
        BotPerformanceMonitor.recordMovementTick(9_000_000L);
        BotPerformanceMonitor.recordIdleBroadcast(400_000L);
        BotPerformanceMonitor.recordNudge(100_000L);
        BotPerformanceMonitor.record("nav-resolve", 7_000_000L);
        BotPerformanceMonitor.recordPathfind(1_000_000L);
        BotPerformanceMonitor.recordObserverPoll(6_000_000L);
        BotPerformanceMonitor.recordOffscreenAccrual(5_000_000L);

        // TRANSITION: a LOD promotion, two demotions, a tier flip both ways and an entry nudge storm.
        BotPerformanceMonitor.recordPromotionReconstruct(8_000_000L);
        BotPerformanceMonitor.recordCoarseDemotion();
        BotPerformanceMonitor.recordCoarseDemotion();
        BotPerformanceMonitor.recordTierFlips(1, 1);
        BotPerformanceMonitor.recordEntryNudges(5);

        Map<String, Line> lines = parse(BotPerformanceMonitor.report());

        // Bucket order in the report: ACTIVE, then BACKGROUND, then TRANSITION.
        List<String> keys = new ArrayList<>(lines.keySet());
        assertEquals("ACTIVE total", keys.get(0));
        assertTrue(keys.indexOf("BACKGROUND total") > keys.indexOf("ACTIVE total"));
        assertTrue(keys.indexOf("TRANSITION total") > keys.indexOf("BACKGROUND total"));

        // ACTIVE total = every phase in the bucket except the ones nested inside another (physics stepping sits
        // inside the move-* phase). Broadcasts are not nested: they also happen with no move-* phase open, so
        // their time counts even though the part emitted inside a move-* phase is then counted in both.
        Line active = lines.get("ACTIVE total");
        assertEquals(1_000_000L + 300_000L + 200_000L + 2_000_000L + 3_000_000L + 4_000_000L, active.nanos());
        assertEquals(9L, active.samples()); // move-ground + 2 physics + 2 broadcasts + fan-out + 3 combat

        // The per-phase split is present, and the dominant ACTIVE term (loot, 4ms) is visible as the largest.
        assertEquals(1_000_000L, lines.get("ACTIVE move-ground").nanos());
        assertEquals(1_000_000L, lines.get("ACTIVE physics-step").nanos()); // two 0.5ms integrations
        assertEquals(2L, lines.get("ACTIVE physics-step").samples());
        assertTrue(lines.get("ACTIVE physics-step").nested());
        assertEquals(300_000L, lines.get("ACTIVE broadcast-move").nanos());
        assertFalse(lines.get("ACTIVE broadcast-move").nested());
        assertEquals(200_000L, lines.get("ACTIVE broadcast-raw").nanos());
        Line fanout = lines.get("ACTIVE broadcast-fanout");
        assertEquals(0L, fanout.nanos());
        assertEquals(1L, fanout.samples());
        assertFalse(fanout.nested(), "a fan-out outside a move-* phase must still reach the active total");
        assertEquals(7L, fanout.units()); // recipients of the movement packet
        assertEquals(2_000_000L, lines.get("ACTIVE combat-scan").nanos());
        assertFalse(lines.get("ACTIVE combat-scan").nested());
        assertEquals(3_000_000L, lines.get("ACTIVE combat-damage").nanos());
        assertEquals(4_000_000L, lines.get("ACTIVE combat-loot").nanos());
        assertEquals(0L, lines.get("ACTIVE move-climb").nanos());
        assertEquals(0L, lines.get("ACTIVE move-air").nanos());
        assertEquals(0L, lines.get("ACTIVE move-swim").nanos());

        long dominant = 0;
        String dominantPhase = null;
        for (Line line : lines.values()) {
            if (!line.bucket().equals("ACTIVE") || line.phase().equals("total") || line.nested()) {
                continue;
            }
            if (line.nanos() > dominant) {
                dominant = line.nanos();
                dominantPhase = line.phase();
            }
        }
        assertEquals("combat-loot", dominantPhase);
        assertEquals(active.nanos(), dominant + 1_000_000L + 2_000_000L + 3_000_000L + 500_000L);

        // BACKGROUND total = the outermost sparse work: macro ticks + movement ticks + the observer poll. Every
        // phase those carry is listed with its own time but excluded from the total (nested=1), so no
        // nanosecond is counted twice.
        Line background = lines.get("BACKGROUND total");
        assertEquals(12_000_000L + 9_000_000L + 6_000_000L, background.nanos());
        assertEquals(12_000_000L, lines.get("BACKGROUND macro-tick").nanos());
        assertEquals(1L, lines.get("BACKGROUND macro-tick").samples());
        assertFalse(lines.get("BACKGROUND macro-tick").nested());
        assertEquals(9_000_000L, lines.get("BACKGROUND movement-tick").nanos());
        assertFalse(lines.get("BACKGROUND movement-tick").nested());
        assertEquals(6_000_000L, lines.get("BACKGROUND observer-poll").nanos());
        assertFalse(lines.get("BACKGROUND observer-poll").nested());
        assertEquals(7_000_000L, lines.get("BACKGROUND nav-resolve").nanos());
        assertTrue(lines.get("BACKGROUND nav-resolve").nested());
        assertEquals(1_000_000L, lines.get("BACKGROUND pathfind").nanos());
        assertTrue(lines.get("BACKGROUND pathfind").nested());
        assertEquals(400_000L, lines.get("BACKGROUND idle-broadcast").nanos());
        assertTrue(lines.get("BACKGROUND idle-broadcast").nested());
        assertEquals(100_000L, lines.get("BACKGROUND nudge").nanos());
        assertTrue(lines.get("BACKGROUND nudge").nested());
        assertEquals(5_000_000L, lines.get("BACKGROUND offscreen-accrual").nanos());
        assertTrue(lines.get("BACKGROUND offscreen-accrual").nested());
        // The total's samples still count the nested work, so a sparse reading shows what ticked even though
        // its time is attributed to the tick that carried it.
        assertEquals(8L, background.samples());

        // TRANSITION total = the promotion reconstruct; flips and nudges are counts, not time.
        Line transition = lines.get("TRANSITION total");
        assertEquals(8_000_000L, transition.nanos());
        assertEquals(5L, transition.samples()); // 1 promotion + 2 demotions + 1 tier-flip sweep + 1 nudge storm
        assertEquals(2L, lines.get("TRANSITION coarse-demotion").samples());
        assertEquals(1L, lines.get("TRANSITION tier-flip").samples());
        assertEquals(2L, lines.get("TRANSITION tier-flip").units()); // one map promoted, one demoted
        assertEquals(5L, lines.get("TRANSITION entry-nudge").units());
    }

    @Test
    void secondReadingRatesCoverOnlyTheSecondWindow() {
        BotPerformanceMonitor.report(); // baseline

        // Sparse window: the bots' own background ticks only, nothing interactive happening.
        BotPerformanceMonitor.recordMacroTick(1_000_000L);
        BotPerformanceMonitor.recordMovementTick(2_000_000L);
        BotPerformanceMonitor.recordObserverPoll(1_000_000L);
        Map<String, Line> sparse = parse(BotPerformanceMonitor.report());
        assertEquals(0L, sparse.get("ACTIVE total").nanos());
        assertTrue(sparse.get("BACKGROUND total").nanos() > 0L);

        // Crowded window: many bots grinding. Only new samples land in this window.
        for (int i = 0; i < 50; i++) {
            BotPerformanceMonitor.record("move-ground", 200_000L);
            BotPerformanceMonitor.recordCombatScan(100_000L);
            BotPerformanceMonitor.recordCombatDamage(150_000L);
            BotPerformanceMonitor.recordCombatLoot(50_000L);
            BotPerformanceMonitor.recordBroadcastFanout(3);
        }
        List<String> crowdedReport = BotPerformanceMonitor.report();
        Map<String, Line> crowded = parse(crowdedReport);

        assertEquals(50L * 200_000L, crowded.get("ACTIVE move-ground").nanos());
        assertEquals(50L * 100_000L, crowded.get("ACTIVE combat-scan").nanos());
        assertEquals(50L * 150_000L, crowded.get("ACTIVE combat-damage").nanos());
        assertEquals(50L * 50_000L, crowded.get("ACTIVE combat-loot").nanos());
        assertEquals(50L * 500_000L, crowded.get("ACTIVE total").nanos());
        assertEquals(150L, crowded.get("ACTIVE broadcast-fanout").units());

        // The sparse window's poll belongs to the first reading only: the second reading is window-local.
        assertEquals(0L, crowded.get("BACKGROUND total").nanos());
        assertEquals(0L, crowded.get("BACKGROUND observer-poll").nanos());
        assertEquals(0L, crowded.get("TRANSITION total").nanos());

        // And the two readings are distinguishable: a crowded window rates far above a sparse one, while the
        // crowded reading's own background rate is zero because nothing background happened inside it.
        assertEquals(0.0, sparse.get("ACTIVE total").msPerSec());
        assertTrue(sparse.get("BACKGROUND total").msPerSec() > 0.0);
        assertTrue(crowded.get("ACTIVE total").msPerSec() > sparse.get("ACTIVE total").msPerSec());
        assertEquals(0.0, crowded.get("BACKGROUND total").msPerSec());

        // The rates are the window's nanos-per-second: ms/s and the % of window both come off that window.
        double window = windowSeconds(crowdedReport);
        assertTrue(window > 0.0, "report must state the window it measured");
        assertEquals(crowded.get("ACTIVE total").nanos() / 1e6 / window, crowded.get("ACTIVE total").msPerSec(), 0.01);
        assertEquals(crowded.get("ACTIVE move-ground").nanos() / 1e6 / window,
                crowded.get("ACTIVE move-ground").msPerSec(), 0.01);
        assertEquals(crowded.get("ACTIVE total").nanos() * 100.0 / 1e9 / window, crowded.get("ACTIVE total").percent(), 0.01);
    }

    /* Index every "<BUCKET> <phase>: ..." line by bucket + phase, failing loudly on a malformed line. */
    private static Map<String, Line> parse(List<String> report) {
        assertTrue(report.size() > 3, "report should carry a line per bucket and per phase");
        Map<String, Line> byKey = new LinkedHashMap<>();
        for (String line : report) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String[] head = line.substring(0, colon).trim().split("\\s+");
            if (head.length != 2 || !isBucket(head[0])) {
                continue; // the "=== Bot cost attribution (window: Ns) ===" header
            }
            String key = head[0] + " " + head[1];
            assertFalse(byKey.containsKey(key), "duplicate report line: " + key);
            byKey.put(key, new Line(head[0], head[1], doubleField(MS_PER_SEC, line), doubleField(PERCENT, line),
                    longField(NANOS, line), longField(SAMPLES, line), longFieldIfAny(UNITS, line),
                    NESTED.matcher(line).find()));
        }
        for (String phase : new String[]{"move-ground", "physics-step", "broadcast-move", "broadcast-raw",
                "broadcast-fanout", "combat-scan", "combat-damage", "combat-loot"}) {
            assertTrue(byKey.containsKey("ACTIVE " + phase), "missing per-phase ACTIVE split: " + phase);
        }
        return byKey;
    }

    private static boolean isBucket(String token) {
        for (BotPerformanceMonitor.Bucket bucket : BotPerformanceMonitor.Bucket.values()) {
            if (bucket.name().equals(token)) {
                return true;
            }
        }
        return false;
    }

    /* The window the report measured, in seconds (what every ms/s and % in it is divided by). */
    private static double windowSeconds(List<String> report) {
        for (String line : report) {
            Matcher matcher = WINDOW.matcher(line);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        throw new AssertionError("no window in report: " + report);
    }

    private static double doubleField(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        assertTrue(matcher.find(), "missing " + pattern.pattern() + " in: " + line);
        return Double.parseDouble(matcher.group(1));
    }

    private static long longField(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        assertTrue(matcher.find(), "missing " + pattern.pattern() + " in: " + line);
        return Long.parseLong(matcher.group(1));
    }

    private static long longFieldIfAny(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }
}
