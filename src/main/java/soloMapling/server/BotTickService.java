package soloMapling.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Fable Phase 2: the central macro-brain tick wheel. Replaces the per-bot
// Executors.newScheduledThreadPool(1) in BotSM (one dedicated OS thread per bot)
// with one shared driver task that dispatches due ticks onto virtual threads.
//
// Semantics preserved from the old per-bot scheduleWithFixedDelay:
//   - never two concurrent ticks for one bot (per-entry 'ticking' CAS guard; the
//     completion's volatile writes also give tick N happens-before tick N+1)
//   - the steady-state period is measured from tick COMPLETION, so a slow tick
//     can never pile up behind itself
//   - reschedule/nudge move only this bot's next due time - no future objects
//     are cancelled or recreated
// Virtual-thread dispatch means a tick that still calls Thread.sleep (several bot
// types do) parks its own virtual thread only - it can't stall other bots.
//
// Ticket 09: the wheel is part of the shutdown sequence. BotShutdown.begin() cancels the driver
// through a hook, register/nudge/reschedule go inert, and BotTickService.stop() waits a bounded
// time for ticks that are already in flight (the per-entry 'ticking' CAS guard is the count)
// before dropping every entry - so the shutdown settlement sweep sees a frozen population.
public final class BotTickService {

    private BotTickService() {
    }

    // Driver pass cadence. Each pass is one O(entries) scan of cheap volatile reads;
    // 100ms keeps nudge latency well under the 150-700ms map-entry stagger.
    private static final long DRIVER_PERIOD_MS = 100;

    private static final class Entry {
        final Runnable tick;
        volatile long periodMs;
        volatile long nextDueMs;
        // A fire-at time requested while a tick was in flight (nudge/reschedule);
        // consumed by that tick's completion so wakeups are never lost. 0 = none.
        volatile long pendingDueMs;
        // High-priority foreground bots (FollowerBot) opt out of governor stretching -
        // their cadence IS the feature; degrading it breaks the bot, not just its polish.
        volatile boolean noThrottle;
        final AtomicBoolean ticking = new AtomicBoolean(false);

        Entry(Runnable tick, long periodMs, long nextDueMs) {
            this.tick = tick;
            this.periodMs = periodMs;
            this.nextDueMs = nextDueMs;
        }
    }

    private static final Map<Integer, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicBoolean DRIVER_STARTED = new AtomicBoolean(false);

    // Ticket 09: the driver's future, kept so shutdown can cancel it instead of leaving a
    // fire-and-forget task dispatching ticks into disposed maps. Cleared back to null by stop().
    private static volatile ScheduledFuture<?> driver;

    // How long stop() waits for in-flight ticks before it gives up and reports the residue.
    private static final long DRAIN_MS = 5000;

    // ── Governor v1 (Fable Phase 5): self-regulating degrade ────────────────
    // If the wheel falls behind (sustained avg dispatch lag over a 5s window above
    // LAG_HI), stretch every steady-state period by an escalating factor so the load
    // sheds itself; relax symmetrically once lag recovers below LAG_LO. Nudges and
    // explicit reschedules are never scaled - promotion responsiveness is sacred,
    // only the background cadence degrades. All governor fields are touched solely
    // on the single driver thread.
    private static final long GOVERNOR_WINDOW_MS = 5000;
    private static final long LAG_HI_MS = 1000;
    private static final long LAG_LO_MS = 300;
    private static final double FACTOR_MAX = 4.0;
    private static final double FACTOR_STEP = 1.5;

    private static volatile double throttleFactor = 1.0;
    private static long governorWindowStartMs = 0;
    private static long windowLagSumMs = 0;
    private static long windowDispatches = 0;

    public static double throttleFactor() {
        return throttleFactor;
    }

    // Register a bot's tick on the wheel. Keep-if-present, mirroring the old
    // startScheduledTask which left a live task alone.
    public static void register(int botId, Runnable tick, long initialDelayMs, long periodMs) {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: a quiesced wheel accepts no new work
        }
        ensureDriver();
        ENTRIES.putIfAbsent(botId, new Entry(tick, periodMs, System.currentTimeMillis() + initialDelayMs));
    }

    public static void unregister(int botId) {
        ENTRIES.remove(botId);
    }

    public static boolean isRegistered(int botId) {
        return ENTRIES.containsKey(botId);
    }

    // Exempt a bot's steady cadence from governor stretching. Call after register;
    // a conversion re-registers a fresh entry, so the new type must re-assert it.
    public static void setNoThrottle(int botId, boolean on) {
        Entry e = ENTRIES.get(botId);
        if (e != null) {
            e.noThrottle = on;
        }
    }

    public static int size() {
        return ENTRIES.size();
    }

    // Change a bot's cadence: next fire at now + periodMs, then periodMs after each
    // completion (matches the old cancel + re-scheduleWithFixedDelay semantics).
    public static void reschedule(int botId, long periodMs) {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: no cadence change can resurrect a quiesced wheel
        }
        Entry e = ENTRIES.get(botId);
        if (e == null) {
            return;
        }
        e.periodMs = periodMs;
        requestFireAt(e, System.currentTimeMillis() + periodMs);
    }

    // Pull the next fire forward to now + initialDelayMs and set the steady period.
    public static void nudge(int botId, long initialDelayMs, long periodMs) {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: a nudge must not wake a bot after quiescence
        }
        Entry e = ENTRIES.get(botId);
        if (e == null) {
            return;
        }
        e.periodMs = periodMs;
        requestFireAt(e, System.currentTimeMillis() + initialDelayMs);
    }

    // Ask for a fire at 'at'. pendingDueMs is set first so an in-flight tick's
    // completion always sees the request - a wakeup can never be lost to the race
    // between this write and the driver parking nextDueMs for a dispatch.
    private static void requestFireAt(Entry e, long at) {
        e.pendingDueMs = at;
        if (e.nextDueMs != Long.MAX_VALUE) { // not mid-tick: move the wheel entry directly
            e.nextDueMs = at;
        }
    }

    /* Ticket 09: registered as one stable instance so a stopped-then-restarted wheel cannot stack
     * duplicate cancel hooks (the gate dedupes by identity). */
    private static final Runnable CANCEL_DRIVER = BotTickService::cancelDriver;

    private static void ensureDriver() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: never start a driver into a shutdown
        }
        if (!DRIVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        driver = ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                BotTickService::safeDrive, DRIVER_PERIOD_MS, DRIVER_PERIOD_MS, TimeUnit.MILLISECONDS);
        BotShutdown.onQuiesce(CANCEL_DRIVER);
    }

    /* Ticket 09: the gate's cancel hook - stop dispatching; ticks already in flight still finish.
     * The started flag is cleared because it tracks "a live driver exists": leaving it set would latch
     * the wheel off if the driver were cancelled while the gate is open (a test reopening the gate, or
     * the unreachable restart path). The one-way gate, not this flag, is what keeps a quiesced wheel
     * stopped - register()/ensureDriver() refuse while it is closed. */
    private static void cancelDriver() {
        ScheduledFuture<?> started = driver;
        if (started != null) {
            started.cancel(false);
        }
        DRIVER_STARTED.set(false);
    }

    /**
     * Ticket 09: cancel the driver, wait up to {@link #DRAIN_MS} for ticks that are already running,
     * then drop every entry. Called right after {@link BotShutdown#begin()} on the shutdown path, so
     * the settlement sweep that follows records a population nothing is still mutating. The logged
     * line is the evidence that the wait happened; the residue is the count that outlived the budget.
     */
    public static void stop() {
        cancelDriver();
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + DRAIN_MS;
        int residue = tickingCount();
        while (residue > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            residue = tickingCount();
        }
        ENTRIES.clear();
        driver = null;
        // The started flag was cleared by cancelDriver(), so this wheel can start again if the gate is
        // ever open (tests) - the one-way gate is what keeps a shut-down wheel stopped.
        System.out.println("[BotShutdown] macro wheel drained in " + (System.currentTimeMillis() - startedAt)
                + "ms (residue " + residue + " ticks)");
    }

    /* Entries with a tick in flight right now - the wheel's own notion of outstanding work. */
    private static int tickingCount() {
        int ticking = 0;
        for (Entry e : ENTRIES.values()) {
            if (e.ticking.get()) {
                ticking++;
            }
        }
        return ticking;
    }

    private static void safeDrive() {
        try {
            drive();
        } catch (Throwable t) {
            // the driver must never die; skip the pass and catch up on the next one
        }
    }

    private static void drive() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: a pass that races the gate must not dispatch into a dying world
        }
        long now = System.currentTimeMillis();
        long lagSum = 0;
        int dispatched = 0;
        for (Entry e : ENTRIES.values()) {
            if (now < e.nextDueMs) {
                continue;
            }
            if (!e.ticking.compareAndSet(false, true)) {
                continue; // still running; its completion computes the next due time
            }
            long lag = now - e.nextDueMs;
            e.nextDueMs = Long.MAX_VALUE; // parked while ticking
            if (e.pendingDueMs > 0 && now >= e.pendingDueMs) {
                e.pendingDueMs = 0; // this dispatch satisfies the pending request
            }
            BotPerfStats.recordWheelDispatch(lag);
            lagSum += Math.max(0, lag);
            dispatched++;
            ExecutorServiceManager.runAsync(() -> runTick(e));
        }
        governorTick(now, lagSum, dispatched);
    }

    private static void governorTick(long now, long lagSumMs, int dispatches) {
        windowLagSumMs += lagSumMs;
        windowDispatches += dispatches;
        if (governorWindowStartMs == 0) {
            governorWindowStartMs = now;
            return;
        }
        if (now - governorWindowStartMs < GOVERNOR_WINDOW_MS) {
            return;
        }
        long avg = windowDispatches > 0 ? windowLagSumMs / windowDispatches : 0;
        windowLagSumMs = 0;
        windowDispatches = 0;
        governorWindowStartMs = now;
        if (avg > LAG_HI_MS && throttleFactor < FACTOR_MAX) {
            throttleFactor = Math.min(FACTOR_MAX, throttleFactor * FACTOR_STEP);
            System.out.println(String.format(
                    "[BotTickService] wheel lag avg %dms - stretching cadences x%.2f", avg, throttleFactor));
        } else if (avg < LAG_LO_MS && throttleFactor > 1.0) {
            throttleFactor = Math.max(1.0, throttleFactor / FACTOR_STEP);
            System.out.println(String.format(
                    "[BotTickService] wheel recovered (avg %dms) - cadence factor x%.2f", avg, throttleFactor));
        }
    }

    private static void runTick(Entry e) {
        try {
            e.tick.run();
        } catch (Throwable t) {
            // one bot's tick error must never kill its schedule (tickRunnable also guards)
        } finally {
            long now = System.currentTimeMillis();
            long due = now + (e.noThrottle ? e.periodMs
                    : (long) (e.periodMs * throttleFactor)); // governor stretches steady cadence only
            long pending = e.pendingDueMs;
            if (pending > 0) {
                e.pendingDueMs = 0;
                due = Math.min(due, Math.max(pending, now));
            }
            e.nextDueMs = due;       // write the due time BEFORE releasing the guard
            e.ticking.set(false);
        }
    }
}
