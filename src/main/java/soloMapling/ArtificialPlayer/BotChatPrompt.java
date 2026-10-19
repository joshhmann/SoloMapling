package soloMapling.ArtificialPlayer;

import client.Character;
import constants.game.GameConstants;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;

import java.util.ArrayList;
import java.util.List;

/**
 * The conversation a bot sends to its brain (bot-persistence ticket 13): who it is (persona), where it is and
 * what it is doing (live state), who it is talking to, a few hard facts about the 2006 game world so it does not
 * invent towns and routes, and the conversation so far.
 */
public final class BotChatPrompt {

    /** Facts the model is unreliable about; kept short, because every line costs prompt time on a 2-slot server. */
    static final String WORLD_FACTS = String.join(" ",
            "World facts (GMS v83, 2006):",
            "Victoria Island towns: Henesys (bowmen), Ellinia (magicians), Perion (warriors), Kerning City (thieves),",
            "Nautilus (pirates), Lith Harbor (port), Sleepywood (dungeon town below the island, Ant Tunnel).",
            "Any Victoria Island town is a short ride on the regular cab from another (no ship needed).",
            "The ship to Orbis leaves from Ellinia station. From Orbis: El Nath down Orbis Tower, Ludibrium by",
            "train, Leafre by ship.",
            "Job advances: 1st at lv10 (magician lv8) and 2nd at lv30 from your class's instructor in your class's",
            "home town (bowmen Henesys, magicians Ellinia, warriors Perion, thieves Kerning City, pirates Nautilus);",
            "3rd at lv70 in El Nath; 4th at lv120 in Leafre.",
            "Party quests: Kerning PQ lv21-30, Ludi PQ lv35-50, Orbis PQ lv51-70.",
            "The Free Market is reached from the FM entrance in towns.");

    private BotChatPrompt() {
    }

    /** Everything above the conversation itself. */
    public static String system(Character bot, Character player, BotPersona.Persona persona, double chattiness,
                                String activity, List<String> memories) {
        StringBuilder s = new StringBuilder();
        s.append("You are ").append(bot.getName()).append(", a real person playing MapleStory in 2006. ");
        s.append("You are a level ").append(bot.getLevel()).append(' ').append(jobName(bot.getJob().getId()));
        String map = mapName(bot);
        if (map != null) {
            s.append(", standing in ").append(map);
        }
        if (activity != null && !activity.isEmpty()) {
            s.append(", currently ").append(activity);
        }
        s.append(". ");
        s.append(BotPersona.describe(persona, chattiness)).append(' ');
        s.append("You are talking to ").append(player.getName()).append(", a level ").append(player.getLevel())
                .append(' ').append(jobName(player.getJob().getId())).append(". ");
        if (memories != null && !memories.isEmpty()) {
            s.append("What you remember about ").append(player.getName()).append(": ")
                    .append(String.join("; ", memories)).append(". ");
        }
        s.append(WORLD_FACTS).append(' ');
        s.append("Rules: stay in character as a player; never say you are an AI, a bot or a program, and if someone ")
                .append("asks, laugh it off like a player would. Talk about the game or casual life only. ")
                .append("If you do not know something, say so instead of making it up. ")
                .append("Answer with one chat line under 60 characters, no quotes, no name prefix.");
        return s.toString();
    }

    /** The full message list: the system prompt, then the conversation so far, ending with the player's line. */
    public static List<BotBrain.Turn> conversation(String system, List<BotBrain.Turn> history) {
        List<BotBrain.Turn> turns = new ArrayList<>(history.size() + 1);
        turns.add(BotBrain.Turn.system(system));
        turns.addAll(history);
        return turns;
    }

    /** How a bot describes what it is doing, from its recorded activity name. */
    public static String activityPhrase(String recorded) {
        if (recorded == null) {
            return null;
        }
        return switch (recorded) {
            case "grind" -> "grinding";
            case "social" -> "hanging out in town";
            case "townwanderer" -> "wandering around town";
            default -> null;
        };
    }

    private static String jobName(int jobId) {
        String name = GameConstants.getJobName(jobId);
        return name == null || name.isBlank() ? "beginner" : name.toLowerCase();
    }

    private static String mapName(Character bot) {
        try {
            return bot.getMap() == null ? null : bot.getMap().getMapName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
