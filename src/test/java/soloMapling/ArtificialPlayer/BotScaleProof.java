package soloMapling.ArtificialPlayer;

import config.YamlConfig;
import net.server.Server;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.server.BotPerfStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * bot-persistence ticket 11: does the world hold at today's ~3,300-bot peak and at 1.25x that, with the director
 * logging bots in and out? A test-scope main against a real server (MySQL + wz/), like {@code BotPopulationProof}:
 * it boots, grows the roster through the lifecycle's own new-veteran path until it can cover the targets, fills the
 * world to each target with the director, and measures a window at each - per-category bot cost
 * ({@code BotPerformanceMonitor}), scheduler lag ({@code BotPerfStats}), heap - then forces a churn of logouts and
 * logins and measures that too. Writes {@code /tmp/botscale/results.txt}. Not a unit test.
 */
public final class BotScaleProof {

    private static final Path OUT = Path.of("/tmp/botscale/results.txt");
    private static final int[] TARGETS = {3300, 4100};
    private static final int WINDOW_SECONDS = 30;
    private static final int CHURN = 200;
    private static final List<String> results = new ArrayList<>();

    private BotScaleProof() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT.getParent());
        say("scale proof pid=" + ProcessHandle.current().pid());
        Server.main(new String[0]);
        settle();
        measure("boot (director curve as configured)");

        for (int target : TARGETS) {
            int staff = online() - rosterOnline();
            int rosterNeeded = Math.max(0, target - staff);
            grow(rosterNeeded + 100);
            YamlConfig.config.server.BOT_ROSTER_CURVE = "flat";
            YamlConfig.config.server.BOT_ROSTER_PEAK_ONLINE = rosterNeeded;
            BotPopulationDirector.fillNow();
            settle();
            measure("target " + target + " (" + staff + " staff + " + rosterNeeded + " roster)");
        }

        measureChurn();

        Files.write(OUT, results);
        say("SCALE-PROOF COMPLETE - results in " + OUT);
        System.out.flush();
        Thread.sleep(200);
        System.exit(0);
    }

    /** New veterans through {@link BotRosterLifecycle#create}, 8 at a time, until the active roster is big enough. */
    private static void grow(int activeNeeded) throws InterruptedException {
        int missing = activeNeeded - BotRoster.active().size();
        if (missing <= 0) {
            return;
        }
        say("growing the roster by " + missing + " veterans");
        long started = System.currentTimeMillis();
        AtomicInteger made = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < missing; i++) {
            pool.execute(() -> {
                EnvironmentManager.TrainingCohort cohort = pickCohort();
                if (BotRosterLifecycle.create(cohort.townMapId(), cohort.loLevel(), cohort.hiLevel(),
                        System.currentTimeMillis())) {
                    made.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.MINUTES);
        say("made " + made.get() + " of " + missing + " veterans in " + (System.currentTimeMillis() - started) + " ms");
        results.add("grow: made " + made.get() + "/" + missing + " veterans in "
                + (System.currentTimeMillis() - started) + " ms (create = login of a brand-new character)");
    }

    private static EnvironmentManager.TrainingCohort pickCohort() {
        List<EnvironmentManager.TrainingCohort> cohorts = EnvironmentManager.TRAINING_COHORTS;
        int total = cohorts.stream().mapToInt(EnvironmentManager.TrainingCohort::count).sum();
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (EnvironmentManager.TrainingCohort c : cohorts) {
            roll -= c.count();
            if (roll < 0) {
                return c;
            }
        }
        return cohorts.get(0);
    }

    /** Logs CHURN random free roster bots out through the director's own path, then fills back up. */
    private static void churn() throws InterruptedException {
        List<Integer> online = new ArrayList<>();
        for (BotRoster.Member m : BotRoster.members()) {
            if (CharacterStorage.botLoggedIn(m.charId())) {
                online.add(m.botId());
            }
        }
        Collections.shuffle(online);
        int asked = 0;
        for (int botId : online.subList(0, Math.min(CHURN, online.size()))) {
            if (BotPopulationDirector.logOut(botId)) {
                asked++;
            }
        }
        say("asked " + asked + " bots to log out");
        Thread.sleep(10_000); // goodbyes have a 1.5-3 s beat; removal follows
        // The relog cooldown keeps the same bots out, so others fill their places.
        BotPopulationDirector.fillNow();
        settle();
    }

    /** The churn inside one window, so its logins and logouts land in the report's roster-login/logout lines. */
    private static void measureChurn() throws Exception {
        BotPerformanceMonitor.report();
        long started = System.currentTimeMillis();
        churn();
        List<String> cost = BotPerformanceMonitor.report();
        results.add("=== churn of " + CHURN + " logouts + their replacements (" + (System.currentTimeMillis() - started)
                + " ms window), online " + online());
        for (String line : cost) {
            if (line.contains("roster-") || line.contains("TRANSITION total")) {
                results.add("  " + line.trim());
            }
        }
        say(results.get(results.size() - 1));
        Files.write(OUT, results);
    }

    private static void measure(String label) throws Exception {
        BotPerformanceMonitor.report(); // start a fresh window
        long lagMs0 = BotPerfStats.WHEEL_LAG_MS.sum();
        long dispatches0 = BotPerfStats.WHEEL_DISPATCHES.sum();
        Thread.sleep(WINDOW_SECONDS * 1000L);
        List<String> cost = BotPerformanceMonitor.report();
        long dispatches = BotPerfStats.WHEEL_DISPATCHES.sum() - dispatches0;
        double avgLag = dispatches == 0 ? 0 : (BotPerfStats.WHEEL_LAG_MS.sum() - lagMs0) / (double) dispatches;
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        results.add("=== " + label);
        results.add(String.format("online %d (roster %d), heap used %d MB of %d MB max, scheduler: %d dispatches, "
                        + "avg lag %.1f ms, throttle factor %.2f", online(), rosterOnline(), heapMb,
                rt.maxMemory() / (1024 * 1024), dispatches, avgLag, soloMapling.server.BotTickService.throttleFactor()));
        for (String line : cost) {
            if (line.contains("total") || line.contains("macro-tick") || line.contains("movement-tick")
                    || line.contains("roster-") || line.contains("promotion") || line.contains("demotion")) {
                results.add("  " + line.trim());
            }
        }
        say(label + ": " + results.get(results.indexOf("=== " + label) + 1));
        Files.write(OUT, results);
    }

    /** Waits until the online count stops changing for 5 s (or 4 minutes pass). */
    private static void settle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        int last = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int now = online();
            stable = now == last ? stable + 1 : 0;
            if (stable >= 10 && now > 100) {
                say("settled at " + now + " online (" + rosterOnline() + " roster)");
                return;
            }
            last = now;
            Thread.sleep(500);
        }
        say("WARNING: did not settle (last " + last + ")");
    }

    private static int online() {
        return CharacterStorage.getAllBots().size();
    }

    private static int rosterOnline() {
        int n = 0;
        for (BotRoster.Member m : BotRoster.members()) {
            if (CharacterStorage.botLoggedIn(m.charId())) {
                n++;
            }
        }
        return n;
    }

    private static void say(String line) {
        System.out.println("[SCALE] " + line);
    }
}
