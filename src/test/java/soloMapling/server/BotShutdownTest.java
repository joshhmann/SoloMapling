package soloMapling.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 09: the gate that stops bot-owned background work before the shutdown settlement sweep.
// The tests run against the real 100ms wheel driver, so timing assertions use generous margins -
// they check orderings and bounds, never exact instants. The gate is one-way for the JVM, so every
// test must reopen it afterwards or the classes that run next would silently stop registering.
class BotShutdownTest {

    private static final int BOT_ID = 9_901_000;

    @AfterEach
    void reopenTheGate() {
        BotTickService.unregister(BOT_ID);
        BotShutdown.resetForTest();
    }

    @Test
    void theGateClosesOnceAndRunsEveryHookExactlyOnce() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        Runnable firstHook = first::incrementAndGet;
        BotShutdown.onQuiesce(firstHook);
        BotShutdown.onQuiesce(second::incrementAndGet);
        BotShutdown.onQuiesce(firstHook); // the same hook instance is absorbed, not run twice

        assertFalse(BotShutdown.isQuiesced());

        BotShutdown.begin();
        BotShutdown.begin(); // idempotent

        assertTrue(BotShutdown.isQuiesced());
        assertEquals(1, first.get(), "the hook ran once per distinct registration");
        assertEquals(1, second.get());
    }

    @Test
    void aHookRegisteredAfterTheGateClosedRunsImmediately() {
        BotShutdown.begin();

        AtomicInteger late = new AtomicInteger();
        BotShutdown.onQuiesce(late::incrementAndGet);

        // A subsystem that starts during shutdown must not register a task the gate has closed over:
        // its cancel hook has to run now, not at the next begin() that will never come.
        assertEquals(1, late.get());
    }

    @Test
    void aFailingHookDoesNotStopTheRemainingHooks() {
        BotShutdown.onQuiesce(() -> {
            throw new IllegalStateException("cancel hook blew up");
        });
        AtomicInteger after = new AtomicInteger();
        BotShutdown.onQuiesce(after::incrementAndGet);

        BotShutdown.begin();

        assertEquals(1, after.get());
    }

    @Test
    void aQuiescedWheelAcceptsNoNewWork() {
        AtomicInteger ticks = new AtomicInteger();
        BotShutdown.begin();

        BotTickService.register(BOT_ID, ticks::incrementAndGet, 0, 50);
        BotTickService.nudge(BOT_ID, 0, 50);
        BotTickService.reschedule(BOT_ID, 50);
        sleep(400);

        assertFalse(BotTickService.isRegistered(BOT_ID), "a stopped wheel must not take new work");
        assertEquals(0, ticks.get(), "no tick may fire after the gate closed");
    }

    @Test
    void stopWaitsForATickThatIsAlreadyRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        BotTickService.register(BOT_ID, () -> {
            started.countDown();
            sleep(200);
            finished.countDown();
        }, 0, 10_000);

        assertTrue(started.await(5, TimeUnit.SECONDS), "the wheel never dispatched the tick");

        long at = System.currentTimeMillis();
        BotTickService.stop();
        long elapsed = System.currentTimeMillis() - at;

        // stop() is the bounded drain the sweep depends on: an in-flight tick finishes before the
        // entries are dropped, so the store records a population nothing is still mutating.
        assertTrue(finished.await(2, TimeUnit.SECONDS), "stop() dropped a tick that was in flight");
        assertTrue(elapsed >= 100, "stop() returned before the in-flight tick finished (" + elapsed + "ms)");
        assertEquals(0, BotTickService.size(), "stop() left entries on the wheel");
    }

    @Test
    void aStoppedWheelStartsAgainWhenTheGateIsReopened() {
        // The one-way gate keeps the wheel stopped in production; a test JVM reopens it, and the
        // wheel's own started flag must not have latched the driver off.
        AtomicInteger ticks = new AtomicInteger();
        BotTickService.stop();
        BotShutdown.resetForTest();

        BotTickService.register(BOT_ID, ticks::incrementAndGet, 0, 50);
        sleep(500);

        assertTrue(ticks.get() >= 1, "the wheel did not restart after stop()");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
