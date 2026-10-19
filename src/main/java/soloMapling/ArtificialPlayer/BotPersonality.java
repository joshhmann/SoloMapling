package soloMapling.ArtificialPlayer;

import soloMapling.server.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Who a roster bot is as a player (bot-persistence ticket 07): how it likes to spend a session, how chatty it
 * is, and when and for how long it usually plays. Generated once from a seed and stored with the roster row, so
 * a bot's character never re-rolls; what it does in a given session is drawn from these traits by
 * {@link #choose}.
 */
public final class BotPersonality {

    /** What a session is spent on, and the bot type that plays it. */
    public enum Activity {
        GRIND("grind", BotTypeManager.BotType.TRAINING_BOT),
        TOWN("townwanderer", BotTypeManager.BotType.TOWN_WANDERER_BOT),
        SOCIAL("social", BotTypeManager.BotType.SOCIAL_BOT);

        /** The name the roster records (the bot type's {@code activitySnapshot} name). */
        public final String recorded;
        public final BotTypeManager.BotType type;

        Activity(String recorded, BotTypeManager.BotType type) {
            this.recorded = recorded;
            this.type = type;
        }

        /** The activity a recorded name stands for, or null for an unknown or blank name. */
        public static Activity fromRecorded(String name) {
            for (Activity activity : values()) {
                if (activity.recorded.equals(name)) {
                    return activity;
                }
            }
            return null;
        }
    }

    /**
     * The stored traits. Weights are a preference mix, not probabilities that must sum to one; play window and
     * session length are minutes (window start is minutes after local midnight).
     */
    public record Traits(long seed, double grind, double town, double social, double chattiness,
                         int playWindowStart, int playWindowLen, int sessionMinutes) {

        public double weight(Activity activity) {
            return switch (activity) {
                case GRIND -> grind;
                case TOWN -> town;
                case SOCIAL -> social;
            };
        }

        public String toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("seed", seed);
            json.put("grind", round(grind));
            json.put("town", round(town));
            json.put("social", round(social));
            json.put("chattiness", round(chattiness));
            return Json.write(json);
        }

        private static double round(double v) {
            return Math.round(v * 1000) / 1000.0;
        }
    }

    /** Share of players that start in each part of the day: evening-heavy, like a real server. */
    private static final int[][] WINDOW_STARTS = {
            // {weight, first minute, last minute}
            {55, 17 * 60, 21 * 60},     // after school / work
            {20, 12 * 60, 17 * 60},     // afternoon
            {15, 21 * 60, 25 * 60},     // late night, past midnight
            {10, 7 * 60, 12 * 60},      // morning
    };

    private BotPersonality() {
    }

    /**
     * Traits for a new player. {@code habit}, when known, is what the bot has been doing so far (a migrated
     * training-cohort bot has been grinding) and tilts the mix toward it, so an existing population keeps its
     * character instead of being reshuffled.
     */
    public static Traits generate(long seed, Activity habit) {
        Random rng = new Random(seed * 0x9E3779B97F4A7C15L + 7);
        double grind = 0.35 + rng.nextDouble() * 0.5;
        double town = 0.05 + rng.nextDouble() * 0.3;
        double social = 0.05 + rng.nextDouble() * 0.35;
        if (habit == Activity.GRIND) {
            grind += 0.4;
        } else if (habit == Activity.TOWN) {
            town += 0.4;
        } else if (habit == Activity.SOCIAL) {
            social += 0.4;
        }
        double total = grind + town + social;
        double chattiness = Math.pow(rng.nextDouble(), 1.5); // most are quiet, a few never stop talking
        int start = windowStart(rng) % (24 * 60);
        int length = 90 + rng.nextInt(271);                    // 1.5 - 6 hours on most days
        int session = Math.min(length, 40 + rng.nextInt(201)); // 40 min - 4 h at a sitting
        return new Traits(seed, grind / total, town / total, social / total, chattiness, start, length, session);
    }

    private static int windowStart(Random rng) {
        int total = 0;
        for (int[] band : WINDOW_STARTS) {
            total += band[0];
        }
        int roll = rng.nextInt(total);
        for (int[] band : WINDOW_STARTS) {
            if (roll < band[0]) {
                return band[1] + rng.nextInt(band[2] - band[1]);
            }
            roll -= band[0];
        }
        return 19 * 60;
    }

    /** What a fresh session is spent on, drawn from the traits. */
    public static Activity choose(Traits traits, Random rng) {
        double total = traits.grind() + traits.town() + traits.social();
        double roll = rng.nextDouble() * total;
        for (Activity activity : Activity.values()) {
            roll -= traits.weight(activity);
            if (roll < 0) {
                return activity;
            }
        }
        return Activity.GRIND;
    }

    /** Reads stored traits back; the play-window columns live beside the JSON in the roster row. */
    public static Traits parse(String json, int playWindowStart, int playWindowLen, int sessionMinutes) {
        if (json == null || json.isBlank() || !(Json.parse(json) instanceof Map<?, ?> map)) {
            return null;
        }
        return new Traits(number(map.get("seed")).longValue(), number(map.get("grind")).doubleValue(),
                number(map.get("town")).doubleValue(), number(map.get("social")).doubleValue(),
                number(map.get("chattiness")).doubleValue(), playWindowStart, playWindowLen, sessionMinutes);
    }

    private static Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }
}
