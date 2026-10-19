package soloMapling.ArtificialPlayer;

import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The population director (bot-persistence ticket 08): roster bots log in and out on a daily curve, the way
 * a real server's players do. Every tick it compares the bots online with the curve's target and logs some in or
 * out; besides that, a bot whose session has run its length logs out and someone else takes its place, so the
 * faces change even while the count holds. Logins prefer bots whose usual play window covers now, so the same
 * regulars show up at similar hours. A bot holding a player-facing commitment is never logged out.
 *
 * <p>Replaces boot waves 8 and 9 once the roster holds members; until then those waves seed it.
 */
public final class BotPopulationDirector {

    private static final Logger log = LoggerFactory.getLogger(BotPopulationDirector.class);

    /** A logged-out bot waits this long before it can log in again, so a logout is not undone a tick later. */
    static final long RELOG_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(20);
    /** Off-window players exist, but rarely: their login weight against an in-window player's 1.0. */
    static final double OFF_WINDOW_WEIGHT = 0.08;
    /** Hysteresis around the target, as a fraction of it, so the count does not flap by one bot. */
    static final double SLACK = 0.02;

    /** One roster member as the planner sees it. */
    record Candidate(int botId, boolean online, boolean active, long loginAt, long logoutAt, int windowStart,
                     int windowLen, int sessionMinutes, boolean mayLogOut, boolean endedOnPurpose) {

        Candidate(int botId, boolean online, boolean active, long loginAt, long logoutAt, int windowStart,
                  int windowLen, int sessionMinutes, boolean mayLogOut) {
            this(botId, online, active, loginAt, logoutAt, windowStart, windowLen, sessionMinutes, mayLogOut, true);
        }

        boolean inWindow(int minuteOfDay) {
            return windowLen > 0 && ((minuteOfDay - windowStart) % 1440 + 1440) % 1440 < windowLen;
        }

        /** Whether this session has run its length; each session's length varies around the bot's habit. */
        boolean sessionDone(long now) {
            if (sessionMinutes <= 0 || loginAt <= 0) {
                return false;
            }
            double jitter = 0.75 + new Random(botId * 31L + loginAt).nextDouble() * 0.6;
            return now - loginAt >= TimeUnit.MINUTES.toMillis(Math.round(sessionMinutes * jitter));
        }
    }

    /** What one tick does. */
    record Plan(List<Integer> logins, List<Integer> logouts, int target, int online) {
    }

    /**
     * The pure decision of one tick.
     *
     * @param maxLogins how many may log in this tick (the boot fill passes the whole target)
     */
    static Plan plan(List<Candidate> roster, int target, int minuteOfDay, long now, int maxLogins, int maxLogouts,
                     Random rng) {
        List<Candidate> online = new ArrayList<>();
        List<Candidate> offline = new ArrayList<>();
        for (Candidate c : roster) {
            (c.online() ? online : offline).add(c);
        }
        List<Integer> logouts = new ArrayList<>();
        // 1. Sessions that ran their length end, longest overstay first.
        online.stream()
                .filter(c -> c.mayLogOut() && c.sessionDone(now))
                .sorted(Comparator.comparingLong(Candidate::loginAt))
                .limit(maxLogouts)
                .forEach(c -> logouts.add(c.botId()));
        int projected = online.size() - logouts.size();
        int slack = (int) Math.ceil(target * SLACK);
        // 2. Above the curve: players outside their window leave first, then the longest sessions.
        if (projected > target + slack && logouts.size() < maxLogouts) {
            int excess = Math.min(projected - target, maxLogouts - logouts.size());
            online.stream()
                    .filter(c -> c.mayLogOut() && !logouts.contains(c.botId()))
                    .sorted(Comparator.comparing((Candidate c) -> c.inWindow(minuteOfDay))
                            .thenComparingLong(Candidate::loginAt))
                    .limit(excess)
                    .forEach(c -> logouts.add(c.botId()));
            projected = online.size() - logouts.size();
        }
        // 3. Below the curve: log players in, weighted toward those whose usual window is now.
        List<Integer> logins = new ArrayList<>();
        if (projected < target - slack || (projected < target && !logouts.isEmpty())) {
            int needed = Math.min(target - projected, maxLogins);
            List<double[]> keyed = new ArrayList<>(); // {key, botId}: weighted sampling without replacement
            for (Candidate c : offline) {
                // The cooldown is for sessions the director ended: a server restart logs everyone out at once,
                // and those players must be able to come straight back.
                if (!c.active() || (c.endedOnPurpose() && c.logoutAt() > 0 && now - c.logoutAt() < RELOG_COOLDOWN_MS)) {
                    continue;
                }
                double weight = c.inWindow(minuteOfDay) ? 1.0 : OFF_WINDOW_WEIGHT;
                keyed.add(new double[]{Math.pow(rng.nextDouble(), 1.0 / weight), c.botId()});
            }
            keyed.sort((a, b) -> Double.compare(b[0], a[0]));
            for (int i = 0; i < needed && i < keyed.size(); i++) {
                logins.add((int) keyed.get(i)[1]);
            }
        }
        return new Plan(logins, logouts, target, online.size());
    }

    // ── live wiring ──────────────────────────────────────────────────────────────────────────────

    /**
     * Logins in flight at once. Each one loads a bot from the database, and the pool is small (52 connections on
     * the default config): an unbounded boot fill of a thousand logins starved it and every login timed out.
     */
    private static final java.util.concurrent.Semaphore LOGIN_SLOTS = new java.util.concurrent.Semaphore(8);

    private static volatile ScheduledFuture<?> ticker;
    private static volatile boolean paused;
    private static volatile Plan lastPlan;
    private static volatile long lastTickAt;

    private BotPopulationDirector() {
    }

    public static boolean enabled() {
        ServerConfig server = YamlConfig.config.server;
        return server != null && server.BOT_DIRECTOR_ENABLED;
    }

    /** Whether the roster is big enough to run on, rather than be seeded by the boot waves first. */
    public static boolean rosterReady() {
        return BotRoster.active().size() >= Math.max(1, YamlConfig.config.server.BOT_ROSTER_SEED_MIN);
    }

    /** Fills the world to the current target at once (a server coming back up has people online), then ticks. */
    public static synchronized void start() {
        if (ticker != null) {
            return;
        }
        ServerConfig server = YamlConfig.config.server;
        tick(Integer.MAX_VALUE);
        long period = Math.max(5, server.BOT_DIRECTOR_TICK_SECONDS);
        ticker = ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(() -> tick(server.BOT_DIRECTOR_MAX_LOGINS_PER_TICK), period, period, TimeUnit.SECONDS);
        BotShutdown.onQuiesce(BotPopulationDirector::stop);
        log.info("Population director started: peak {}, curve {}, tick {}s", server.BOT_ROSTER_PEAK_ONLINE,
                server.BOT_ROSTER_CURVE, period);
    }

    /** The proof harness (ticket 11): fill to the current target now, without the per-tick login cap. */
    static void fillNow() {
        tick(Integer.MAX_VALUE);
    }

    public static synchronized void stop() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
    }

    /** GM: hold the population where it is (no logins or logouts) or let the director run again. */
    public static void setPaused(boolean value) {
        paused = value;
    }

    public static boolean isRunning() {
        return ticker != null && !paused;
    }

    public static BotPopulationCurve curve() {
        ServerConfig server = YamlConfig.config.server;
        return BotPopulationCurve.parse(server.BOT_ROSTER_CURVE, server.BOT_ROSTER_WEEKEND_LIFT);
    }

    public static ZonedDateTime localNow() {
        String zone = YamlConfig.config.server.BOT_ROSTER_TIMEZONE;
        return ZonedDateTime.now(zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone));
    }

    public static int targetNow() {
        return curve().target(YamlConfig.config.server.BOT_ROSTER_PEAK_ONLINE, localNow());
    }

    private static void tick(int maxLogins) {
        if (paused || BotShutdown.isQuiesced()) {
            return;
        }
        try {
            ServerConfig server = YamlConfig.config.server;
            ZonedDateTime local = localNow();
            long now = System.currentTimeMillis();
            List<Candidate> roster = candidates();
            int target = curve().target(server.BOT_ROSTER_PEAK_ONLINE, local);
            Plan plan = plan(roster, target, local.getHour() * 60 + local.getMinute(), now, maxLogins,
                    server.BOT_DIRECTOR_MAX_LOGOUTS_PER_TICK, ThreadLocalRandom.current());
            lastPlan = plan;
            lastTickAt = now;
            if (maxLogins == Integer.MAX_VALUE) {
                System.out.println("[Director] boot fill: target " + target + ", roster " + roster.size() + " ("
                        + plan.online() + " online), logging in " + plan.logins().size());
            }
            // Ticket 09: a logout is a request - a free bot says bye and goes, a committed one waits (up to a cap).
            for (int botId : plan.logouts()) {
                BotLogout.request(BotStore.BOT_ID_BASE + botId, now);
            }
            for (Candidate c : roster) {
                int charId = BotStore.BOT_ID_BASE + c.botId();
                if (c.online() && !c.mayLogOut() && c.sessionDone(now) && !BotLogout.isLeaving(charId)) {
                    BotLogout.request(charId, now);
                }
            }
            BotLogout.process(now);
            java.util.Set<Integer> onlineIds = new java.util.HashSet<>();
            for (Candidate c : roster) {
                if (c.online()) {
                    onlineIds.add(c.botId());
                }
            }
            BotRosterLifecycle.maybeStep(now, onlineIds);
            BotGossip.maybeStep(now);
            // Logins trickle in across the tick instead of landing together (the boot fill lands at once).
            long spreadMs = maxLogins == Integer.MAX_VALUE ? 0 : TimeUnit.SECONDS.toMillis(server.BOT_DIRECTOR_TICK_SECONDS);
            for (int botId : plan.logins()) {
                long delay = spreadMs == 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadMs);
                // A login loads the bot from the database: run it on a virtual thread, not the shared scheduler.
                ExecutorServiceManager.getScheduledExecutorService()
                        .schedule(() -> ExecutorServiceManager.runAsync(() -> boundedLogIn(botId)), delay, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("Population director tick failed", e);
            System.out.println("[Director] tick failed: " + e);
        }
    }

    private static List<Candidate> candidates() {
        List<Candidate> roster = new ArrayList<>();
        for (BotRoster.Member m : BotRoster.members()) {
            BotSM live = CharacterStorage.getBotById(m.charId());
            BotPersonality.Traits traits = m.traits();
            int windowStart = traits != null ? traits.playWindowStart() : m.playWindowStart();
            int windowLen = traits != null ? traits.playWindowLen() : m.playWindowLen();
            int session = traits != null ? traits.sessionMinutes() : m.sessionMinutes();
            BotRoster.Activity last = m.lastActivity();
            boolean ended = last != null && last.intState(BotRoster.ENDED_STATE, 0) == 1;
            roster.add(new Candidate(m.botId(), live != null, m.status() == BotRoster.Status.ACTIVE, m.lastLoginAt(),
                    m.lastLogoutAt(), windowStart, windowLen, session, live != null && mayLogOut(live), ended));
        }
        return roster;
    }

    /**
     * Whether a bot can leave now without breaking a promise to a player: no open trade or armed invite, no
     * conversation in progress, no party with a real player, not following anyone.
     */
    static boolean mayLogOut(BotSM bot) {
        if (BotCommitments.inFlight(bot) || bot instanceof FollowerBot) {
            return false;
        }
        if (bot instanceof SocialBot social && social.hasActiveRespondant()) {
            return false;
        }
        Character chr = bot.getChr();
        Party party = chr == null ? null : chr.getParty();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                if (member != null && !BotHelpers.isArtificial(member.getId())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void boundedLogIn(int botId) {
        try {
            LOGIN_SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long started = System.nanoTime();
        try {
            if (logIn(botId)) {
                soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor.recordRosterLogin(System.nanoTime() - started);
            }
        } catch (Exception e) {
            log.warn("Login of roster bot {} failed", botId, e);
        } finally {
            LOGIN_SLOTS.release();
        }
    }

    /** Logs one roster member in, where it logged out, as the activity its session calls for. */
    public static boolean logIn(int botId) {
        BotRoster.Member member = BotRoster.member(botId);
        if (member == null || CharacterStorage.botLoggedIn(member.charId()) || BotShutdown.isQuiesced()) {
            return false;
        }
        MapleMap home = homeMap(member);
        if (home == null || home.getPortal(0) == null) {
            log.warn("Roster bot {} has no map to log in to", botId);
            return false;
        }
        Point spawn = home.getPortal(0).getPosition();
        int charId = BotGeneration.createBot(botId, spawn, home, 0, 0, 0, 0);
        if (charId <= 0) {
            System.out.println("[Director] login of roster bot " + botId + " failed: createBot returned " + charId);
            return false;
        }
        BotPersonality.Traits traits = BotRoster.member(botId).traits();
        BotPersonality.Activity fresh = traits == null ? BotPersonality.Activity.GRIND
                : BotPersonality.choose(traits, ThreadLocalRandom.current());
        // A resumed session keeps its activity (setAndStartBots prefers it); a fresh one is drawn from the personality.
        BotTypeManager.setAndStartBots(List.of(charId), fresh.type);
        return true;
    }

    /**
     * Asks one roster member to log out, ending its session (the next login starts a fresh one). A bot holding a
     * commitment leaves once it ends, or at the cap (ticket 09). Returns false when the bot is not online.
     */
    public static boolean logOut(int botId) {
        int charId = BotStore.BOT_ID_BASE + botId;
        BotSM bot = CharacterStorage.getBotById(charId);
        if (bot == null || bot.getChr() == null) {
            return false;
        }
        BotLogout.request(charId, System.currentTimeMillis());
        return true;
    }

    private static MapleMap homeMap(BotRoster.Member member) {
        int id = member.homeMapId() > 0 ? member.homeMapId() : constants.id.MapId.HENESYS;
        MapleMap map = soloMapling.server.SoloMaplingUtilities.getMapleMapById(id);
        return map != null ? map : soloMapling.server.SoloMaplingUtilities.getMapleMapById(constants.id.MapId.HENESYS);
    }

    /** For the GM read-out: the last tick's numbers. */
    public static String describe() {
        Plan plan = lastPlan;
        long online = BotRoster.members().stream().filter(m -> CharacterStorage.botLoggedIn(m.charId())).count();
        return String.format("director %s: %d roster bots online, target now %d (peak %d), %d waiting to leave;"
                        + " last tick %s: +%d / -%d",
                ticker == null ? "off" : paused ? "paused" : "on", online, targetNow(),
                YamlConfig.config.server.BOT_ROSTER_PEAK_ONLINE, BotLogout.waiting(),
                lastTickAt == 0 ? "never" : ((System.currentTimeMillis() - lastTickAt) / 1000) + "s ago",
                plan == null ? 0 : plan.logins().size(), plan == null ? 0 : plan.logouts().size());
    }

    /** For the GM preview: the target at each hour today. */
    public static Map<Integer, Integer> todaysTargets() {
        Map<Integer, Integer> targets = new java.util.LinkedHashMap<>();
        ZonedDateTime today = localNow().withMinute(0);
        for (int h = 0; h < 24; h++) {
            targets.put(h, curve().target(YamlConfig.config.server.BOT_ROSTER_PEAK_ONLINE, today.withHour(h)));
        }
        return targets;
    }
}
