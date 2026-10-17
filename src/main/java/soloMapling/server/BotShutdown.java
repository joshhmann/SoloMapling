package soloMapling.server;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ticket 09: the one-way gate that stops bot-owned background activity before the shutdown
 * settlement sweep runs and before the worlds (and therefore the maps) are disposed.
 *
 * <p>Why it exists: bot activity is not part of the player disconnect path. The macro wheel, the
 * shared combat ticker and the per-subsystem scanners keep firing on the shared scheduled pool
 * while {@code Server.shutdownInternal} settles the bot store and then tears the world down, so a
 * tick could still travel, decide or accrue against a map whose footholds are already null. Nothing
 * in the teardown sequence stopped them: the wheel's driver future was fire-and-forget and no bot
 * subsystem owned a stop path.</p>
 *
 * <p>Shape: one volatile read on the hot path ({@link #isQuiesced()}) plus a registry of cancel
 * hooks that {@link #begin()} runs once. Every recurring bot body checks the gate at its head and
 * returns; every subsystem that holds a future registers a hook that cancels it, so a quiesced
 * server has no bot wakeups at all rather than a fleet of no-op ones.
 * {@code BotTickService.stop()} is what bounds work already in flight (the wheel drains its ticking
 * entries).</p>
 *
 * <p>The gate is one-way for the process run: shutdown is the only production wire and it ends in
 * {@code System.exit}. The unreachable {@code restart=true} branch of
 * {@code Server.shutdownInternal} would need {@code Server.init()} to clear it, and
 * {@link #resetForTest()} exists for tests that must reopen it in one JVM.</p>
 */
public final class BotShutdown {

    private static final AtomicBoolean QUIESCED = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    private BotShutdown() {
    }

    /**
     * Register the hook that cancels this subsystem's bot futures. A registration that lands after
     * {@link #begin()} runs immediately instead of being dropped, so a subsystem that starts during
     * shutdown cannot register a task the gate has already closed over.
     */
    public static void onQuiesce(Runnable cancelHook) {
        if (cancelHook == null) {
            return;
        }
        if (QUIESCED.get()) {
            run(cancelHook);
            return;
        }
        HOOKS.addIfAbsent(cancelHook);
        if (QUIESCED.get() && HOOKS.remove(cancelHook)) {
            run(cancelHook); // the gate closed between the check and the add
        }
    }

    /**
     * Close the gate: no new bot work is accepted and every registered scheduler is cancelled.
     * Idempotent - only the first call cancels, logs, and reports how many schedulers it stopped.
     */
    public static void begin() {
        if (!QUIESCED.compareAndSet(false, true)) {
            return;
        }
        int hooks = 0;
        for (Runnable hook : HOOKS) {
            run(hook);
            hooks++;
        }
        HOOKS.clear();
        System.out.println("[BotShutdown] bot activity quiesced (" + hooks + " schedulers cancelled)");
    }

    /** Single volatile read - the hot path at the head of every recurring bot body. */
    public static boolean isQuiesced() {
        return QUIESCED.get();
    }

    /* Test seam: reopen the gate and drop the hooks. The gate is one-way in production, but the test
     * JVM is shared, so a test that closes it must put it back for the classes that run after it. */
    static void resetForTest() {
        HOOKS.clear();
        QUIESCED.set(false);
    }

    private static void run(Runnable hook) {
        try {
            hook.run();
        } catch (Throwable t) {
            // a failing cancel hook must not leave the remaining schedulers running
            System.out.println("[BotShutdown] cancel hook failed: " + t);
        }
    }
}
