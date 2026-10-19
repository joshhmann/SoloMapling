package soloMapling.ArtificialPlayer;

import config.ServerConfig;
import config.YamlConfig;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.server.SoloMaplingUtilities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The roster's life over weeks (bot-persistence ticket 10): new players make characters, some players quit and
 * go dormant, and now and then a dormant one comes back. New characters are mostly fresh level-1 beginners; while
 * the roster is far below its size, veterans across today's level bands are made too, so the world does not
 * spend months as a server of newbies. A dormant bot keeps everything - name, level, items, memories.
 */
public final class BotRosterLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotRosterLifecycle.class);

    /** Where a new beginner shows up, having just left Maple Island. */
    static final int[] BEGINNER_TOWNS = {MapId.HENESYS, MapId.KERNING_CITY, MapId.PERION, MapId.ELLINIA, MapId.LITH_HARBOUR};
    static final int BEGINNER_MAX_LEVEL = 3;

    /** The rates, per day. */
    record Rates(int targetSize, int newPerDay, double veteranFillBelow, int veteransPerDay, double quitPerDay,
                 double returnPerDay) {

        static Rates fromConfig(ServerConfig server) {
            return new Rates((int) Math.round(server.BOT_ROSTER_PEAK_ONLINE * server.BOT_ROSTER_SIZE_MULTIPLE),
                    server.BOT_ROSTER_NEW_PER_DAY, server.BOT_ROSTER_VETERAN_FILL_BELOW, server.BOT_ROSTER_VETERANS_PER_DAY,
                    server.BOT_ROSTER_QUIT_PER_DAY, server.BOT_ROSTER_RETURN_PER_DAY);
        }
    }

    /** One step's decisions. */
    record Step(int newPlayers, int veterans, List<Integer> quit, List<Integer> returned) {
    }

    /**
     * The pure decision for a span of {@code hours}. Only offline players quit (nobody vanishes mid-session); new
     * characters stop once the active roster reaches its size.
     */
    static Step plan(Collection<BotRoster.Member> members, Set<Integer> online, Rates rates, double hours, Random rng) {
        double days = hours / 24.0;
        int active = 0;
        List<Integer> quit = new ArrayList<>();
        List<Integer> returned = new ArrayList<>();
        double quitChance = 1 - Math.pow(1 - rates.quitPerDay(), days);
        double returnChance = 1 - Math.pow(1 - rates.returnPerDay(), days);
        for (BotRoster.Member m : members) {
            if (m.status() == BotRoster.Status.ACTIVE) {
                active++;
                if (!online.contains(m.botId()) && rng.nextDouble() < quitChance) {
                    quit.add(m.botId());
                }
            } else if (rng.nextDouble() < returnChance) {
                returned.add(m.botId());
            }
        }
        int projected = active - quit.size() + returned.size();
        int room = Math.max(0, rates.targetSize() - projected);
        int newPlayers = Math.min(room, draws(rates.newPerDay() * days, rng));
        int veterans = 0;
        if (projected < rates.targetSize() * rates.veteranFillBelow()) {
            veterans = Math.min(room - newPlayers, draws(rates.veteransPerDay() * days, rng));
        }
        return new Step(newPlayers, Math.max(0, veterans), quit, returned);
    }

    /** A whole number of events for an expected count: the fraction becomes a coin flip. */
    static int draws(double expected, Random rng) {
        int whole = (int) Math.floor(expected);
        return whole + (rng.nextDouble() < expected - whole ? 1 : 0);
    }

    // ── live wiring, driven by the director ─────────────────────────────────────────────────────────

    private static volatile long lastStepAt;

    private BotRosterLifecycle() {
    }

    /** Runs at most once an hour, from the director's tick. */
    static void maybeStep(long now, Set<Integer> online) {
        if (lastStepAt == 0) {
            lastStepAt = now; // the first hour after boot is the first span
            return;
        }
        long elapsed = now - lastStepAt;
        if (elapsed < TimeUnit.HOURS.toMillis(1)) {
            return;
        }
        lastStepAt = now;
        Step step = plan(BotRoster.members(), online, Rates.fromConfig(YamlConfig.config.server),
                elapsed / (double) TimeUnit.HOURS.toMillis(1), ThreadLocalRandom.current());
        apply(step, now);
    }

    static void apply(Step step, long now) {
        for (int botId : step.quit()) {
            setStatus(botId, BotRoster.Status.DORMANT);
        }
        for (int botId : step.returned()) {
            setStatus(botId, BotRoster.Status.ACTIVE);
        }
        for (int i = 0; i < step.newPlayers(); i++) {
            int town = BEGINNER_TOWNS[ThreadLocalRandom.current().nextInt(BEGINNER_TOWNS.length)];
            create(town, 1, BEGINNER_MAX_LEVEL, now);
        }
        for (int i = 0; i < step.veterans(); i++) {
            EnvironmentManager.TrainingCohort cohort = pickCohort();
            create(cohort.townMapId(), cohort.loLevel(), cohort.hiLevel(), now);
        }
        if (step.newPlayers() + step.veterans() + step.quit().size() + step.returned().size() > 0) {
            log.info("Roster: {} new players, {} veterans, {} quit, {} came back", step.newPlayers(), step.veterans(),
                    step.quit().size(), step.returned().size());
        }
    }

    private static void setStatus(int botId, BotRoster.Status status) {
        BotRoster.Member member = BotRoster.member(botId);
        if (member == null) {
            return;
        }
        try {
            BotRoster.save(member.withStatus(status));
        } catch (Exception e) {
            log.warn("Could not mark roster bot {} {}", botId, status, e);
        }
    }

    /** A cohort drawn by its size, so veterans land where today's population lives. */
    private static EnvironmentManager.TrainingCohort pickCohort() {
        int total = 0;
        for (EnvironmentManager.TrainingCohort c : EnvironmentManager.TRAINING_COHORTS) {
            total += c.count();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (EnvironmentManager.TrainingCohort c : EnvironmentManager.TRAINING_COHORTS) {
            roll -= c.count();
            if (roll < 0) {
                return c;
            }
        }
        return EnvironmentManager.TRAINING_COHORTS.get(0);
    }

    /** Makes a new character - a new player logging in for the first time - and enrols it. */
    static boolean create(int townMapId, int loLevel, int hiLevel, long now) {
        MapleMap town = SoloMaplingUtilities.getMapleMapById(townMapId);
        if (town == null || town.getPortal(0) == null) {
            return false;
        }
        int slot = BotRoster.nextNewSlot();
        int charId = BotGeneration.createBot(slot, town.getPortal(0).getPosition(), town, BotDecorate.rollBaseClass(),
                loLevel, hiLevel, 0);
        if (charId <= 0) {
            return false;
        }
        try {
            BotRoster.enroll(slot, now);
        } catch (Exception e) {
            log.warn("Could not enrol new roster bot {}", slot, e);
        }
        BotRoster.recordLogin(charId, now, townMapId, townMapId, town.getPortal(0).getPosition());
        BotTypeManager.setAndStartBots(List.of(charId), BotTypeManager.BotType.TRAINING_BOT);
        return true;
    }
}
