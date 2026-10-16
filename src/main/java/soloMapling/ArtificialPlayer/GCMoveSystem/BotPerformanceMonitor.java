package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/*
 * Cost attribution for the bot population (ticket 03). Hot paths bump per-phase LongAdders (cheap,
 * contention-free); report() turns them into per-second rates measured since the previous call, so
 * two readings taken seconds apart describe only the window between them - a sparse run and a
 * crowded run read differently, and the dominant term is visible.
 *
 * Nothing here changes movement, combat or LOD behavior: the hooks only time work that already
 * happened, and this class stays free of bot-package imports (BotPerfStats keeps its own count-only
 * adders for the older !env perf lines). !env perf appends report().
 *
 * Every phase belongs to exactly one bucket:
 *   ACTIVE      crowded-interactive work - physics stepping (move-*), movement broadcast fan-out,
 *               combat scan / damage / loot.
 *   BACKGROUND  sparse-observer work - macro ticks (the per-bot state tick, which carries idle
 *               broadcasts, tick-wheel nudges and offscreen accrual), movement ticks (which carry
 *               nav-resolve / pathfind), the observer poll.
 *   TRANSITION  tier changes - promotion reconstruct, coarse demotion, tier flips, entry nudge storms.
 *
 * A nested phase runs inside another phase (physics stepping inside a move-*, pathfind inside
 * nav-resolve inside the movement tick, offscreen accrual inside the macro tick); nested phases are
 * still listed - they are the split of the term that contains them - but they are excluded from their
 * bucket total so each bucket figure stays the cost of its outermost work.
 *
 * Broadcast time is deliberately NOT nested: a move-* phase usually emits inside itself, but a bot also
 * broadcasts from combat, chat and teleport with no move-* phase open, and hiding that work would make
 * the active total look cheaper than it is. So the broadcast phases count towards their bucket total
 * whether or not a move-* phase is open, and the part emitted inside move-* is counted in both - a
 * known overlap bounded by the broadcast figure the report prints next to it.
 */
// Ported from GreenCatMS. Credit: NutNNut.
public final class BotPerformanceMonitor {

    public enum Bucket { ACTIVE, BACKGROUND, TRANSITION }

    private static final class Phase {
        private final String name;
        private final Bucket bucket;
        private final boolean nested;
        private final LongAdder nanos = new LongAdder();
        private final LongAdder calls = new LongAdder();
        private final LongAdder units = new LongAdder(); // extra count: broadcast recipients, bots nudged
        private long prevNanos;
        private long prevCalls;
        private long prevUnits;

        Phase(String name, Bucket bucket, boolean nested) {
            this.name = name;
            this.bucket = bucket;
            this.nested = nested;
        }

        void add(long elapsedNanos, long unitCount) {
            nanos.add(elapsedNanos);
            calls.increment();
            units.add(unitCount);
        }

        void count(long unitCount) {
            calls.increment();
            units.add(unitCount);
        }

        String line(double secs) {
            long dNanos = nanos.sum() - prevNanos;
            long dCalls = calls.sum() - prevCalls;
            long dUnits = units.sum() - prevUnits;
            String extra = dUnits == 0 ? "" : String.format(" units=%d", dUnits);
            String inner = nested ? " nested=1" : "";
            return String.format("%s %s: %.2f ms/s (%.2f%% of window) nanos=%d samples=%d%s%s",
                    bucket, name, dNanos / 1e6 / secs, 100.0 * dNanos / 1e9 / secs, dNanos, dCalls, extra, inner);
        }

        void advance() {
            prevNanos = nanos.sum();
            prevCalls = calls.sum();
            prevUnits = units.sum();
        }
    }

    private static final Map<String, Phase> PHASES = new LinkedHashMap<>();

    // registered in bucket order so the report lists ACTIVE, then BACKGROUND, then TRANSITION
    private static final Phase MOVE_CLIMB = phase("move-climb", Bucket.ACTIVE, false);
    private static final Phase MOVE_AIR = phase("move-air", Bucket.ACTIVE, false);
    private static final Phase MOVE_SWIM = phase("move-swim", Bucket.ACTIVE, false);
    private static final Phase MOVE_GROUND = phase("move-ground", Bucket.ACTIVE, false);
    private static final Phase PHYSICS_STEP = phase("physics-step", Bucket.ACTIVE, true);
    private static final Phase BROADCAST_MOVE = phase("broadcast-move", Bucket.ACTIVE, false);
    private static final Phase BROADCAST_RAW = phase("broadcast-raw", Bucket.ACTIVE, false);
    private static final Phase BROADCAST_FANOUT = phase("broadcast-fanout", Bucket.ACTIVE, false);
    private static final Phase COMBAT_SCAN = phase("combat-scan", Bucket.ACTIVE, false);
    private static final Phase COMBAT_DAMAGE = phase("combat-damage", Bucket.ACTIVE, false);
    private static final Phase COMBAT_LOOT = phase("combat-loot", Bucket.ACTIVE, false);

    private static final Phase MACRO_TICK = phase("macro-tick", Bucket.BACKGROUND, false);
    private static final Phase MOVEMENT_TICK = phase("movement-tick", Bucket.BACKGROUND, false);
    private static final Phase NAV_RESOLVE = phase("nav-resolve", Bucket.BACKGROUND, true);
    private static final Phase PATHFIND = phase("pathfind", Bucket.BACKGROUND, true);
    private static final Phase IDLE_BROADCAST = phase("idle-broadcast", Bucket.BACKGROUND, true);
    private static final Phase NUDGE = phase("nudge", Bucket.BACKGROUND, true);
    private static final Phase OBSERVER_POLL = phase("observer-poll", Bucket.BACKGROUND, false);
    private static final Phase OFFSCREEN_ACCRUAL = phase("offscreen-accrual", Bucket.BACKGROUND, true);

    private static final Phase PROMOTION_RECONSTRUCT = phase("promotion-reconstruct", Bucket.TRANSITION, false);
    private static final Phase COARSE_DEMOTION = phase("coarse-demotion", Bucket.TRANSITION, false);
    private static final Phase TIER_FLIP = phase("tier-flip", Bucket.TRANSITION, false);
    private static final Phase ENTRY_NUDGE = phase("entry-nudge", Bucket.TRANSITION, false);

    private static long prevAtMs = System.currentTimeMillis();

    private BotPerformanceMonitor() {
    }

    private static Phase phase(String name, Bucket bucket, boolean nested) {
        Phase p = new Phase(name, bucket, nested);
        PHASES.put(name, p);
        return p;
    }

    /* Phase hook for the ported call sites that pass the phase by name. */
    public static void record(String name, long nanos) {
        Phase p = PHASES.get(name);
        if (p == null) {
            throw new IllegalArgumentException("unregistered phase: " + name);
        }
        p.add(nanos, 0);
    }

    /* Nav-graph pathfind, which runs inside a bot's nav-resolve. */
    public static void recordPathfind(long nanos) {
        PATHFIND.add(nanos, 0);
    }

    /* Physics integration step (ground/air/swim/climb/rope), nested inside the move-* phase. */
    public static void recordPhysicsStep(long nanos) {
        PHYSICS_STEP.add(nanos, 0);
    }

    /* One movement-packet fan-out; recipients is how many map objects the packet reached. */
    public static void recordBroadcastFanout(int recipients) {
        BROADCAST_FANOUT.count(recipients);
    }

    /* Teleport/falling-skill fragment send; counted even when no move-* phase is open. */
    public static void recordBroadcastRaw(long nanos) {
        BROADCAST_RAW.add(nanos, 0);
    }

    /* Target scan / acquisition / strategy decision for one bot tick. */
    public static void recordCombatScan(long nanos) {
        COMBAT_SCAN.add(nanos, 0);
    }

    /* Damage application for one bot tick, loot drop roll included. */
    public static void recordCombatDamage(long nanos) {
        COMBAT_DAMAGE.add(nanos, 0);
    }

    /* Loot collection for one bot tick. */
    public static void recordCombatLoot(long nanos) {
        COMBAT_LOOT.add(nanos, 0);
    }

    /* One macro (state) tick body for one bot - the umbrella over idle broadcasts, nudges and accrual. */
    public static void recordMacroTick(long nanos) {
        MACRO_TICK.add(nanos, 0);
    }

    /* One full movement tick body for one bot, which carries nav-resolve, pathfind and the move-* work. */
    public static void recordMovementTick(long nanos) {
        MOVEMENT_TICK.add(nanos, 0);
    }

    /* One idle standing refresh, nested inside the macro tick that asked for it. */
    public static void recordIdleBroadcast(long nanos) {
        IDLE_BROADCAST.add(nanos, 0);
    }

    /* One tick-wheel nudge (schedule pull-forward), nested inside the macro tick that asked for it. */
    public static void recordNudge(long nanos) {
        NUDGE.add(nanos, 0);
    }

    /* One observer poll sweep (map/tier sets recomputed). */
    public static void recordObserverPoll(long nanos) {
        OBSERVER_POLL.add(nanos, 0);
    }

    /* Coarse (offscreen) movement advance for one bot. */
    public static void recordOffscreenAccrual(long nanos) {
        OFFSCREEN_ACCRUAL.add(nanos, 0);
    }

    /* Promotion back into full physics for one bot (coarse state reconstructed). */
    public static void recordPromotionReconstruct(long nanos) {
        PROMOTION_RECONSTRUCT.add(nanos, 0);
    }

    /* Demotion to coarse for one bot (full state dropped). */
    public static void recordCoarseDemotion() {
        COARSE_DEMOTION.count(0);
    }

    /* Maps entering / leaving the active LOD set (promotion and demotion tier flips). */
    public static void recordTierFlips(int promoted, int demoted) {
        TIER_FLIP.count(promoted + demoted);
    }

    /* Bots woken because a real player entered their map - the entry nudge storm. */
    public static void recordEntryNudges(int nudged) {
        ENTRY_NUDGE.count(nudged);
    }

    /*
     * Ready-to-print cost attribution: one ACTIVE / BACKGROUND / TRANSITION total line each (ms/s and
     * % of the window), then that bucket's phases - so the ACTIVE split names the dominant term and a
     * sparse reading vs a crowded one differ at a glance. Nested phases are listed after their
     * enclosing phase but excluded from the bucket total (they are already inside it); the line is
     * still marked `nested=1` so the arithmetic stays auditable.
     * Reads are deltas against the previous call and advance the baseline here, so the recording path
     * stays lock-free. Call twice a few seconds apart for a live window.
     */
    public static synchronized List<String> report() {
        long now = System.currentTimeMillis();
        double secs = Math.max(0.001, (now - prevAtMs) / 1000.0);

        List<String> out = new ArrayList<>();
        out.add(String.format("=== Bot cost attribution (window: %.3fs) ===", secs));
        for (Bucket bucket : Bucket.values()) {
            long nanos = 0;
            long samples = 0;
            for (Phase p : PHASES.values()) {
                if (p.bucket != bucket) {
                    continue;
                }
                samples += p.calls.sum() - p.prevCalls;
                if (!p.nested) {
                    nanos += p.nanos.sum() - p.prevNanos;
                }
            }
            out.add(String.format("%s total: %.2f ms/s (%.2f%% of window) nanos=%d samples=%d",
                    bucket, nanos / 1e6 / secs, 100.0 * nanos / 1e9 / secs, nanos, samples));
            for (Phase p : PHASES.values()) {
                if (p.bucket == bucket) {
                    out.add(p.line(secs));
                }
            }
        }

        for (Phase p : PHASES.values()) {
            p.advance();
        }
        prevAtMs = now;
        return out;
    }
}
