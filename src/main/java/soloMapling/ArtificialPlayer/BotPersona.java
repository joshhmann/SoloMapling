package soloMapling.ArtificialPlayer;

import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * How a roster bot talks (bot-persistence ticket 13): its typing style, its vibe and what it cares about in the
 * game. Generated once from the bot's seed, stored in the roster's {@code Persona} column, and turned into the
 * system prompt the LLM speaks from. Chattiness comes from the personality ({@link BotPersonality.Traits}).
 */
public final class BotPersona {

    private static final String[] VIBES = {
            "friendly and easygoing", "a bit sarcastic but nice", "shy, short answers", "hyper and excitable",
            "a chill veteran who has seen it all", "a tryhard who talks about efficiency", "goofy, jokes a lot",
            "helpful, likes giving tips to newer players", "a little grumpy about the grind",
    };
    private static final String[] INTERESTS = {
            "grinding to the next job advance", "making mesos in the FM", "party quests", "funding scrolls",
            "nx fashion and looking cool", "finding a guild", "hunting rare drops", "helping newbies",
            "chatting more than playing", "job advancement quests", "zakum someday",
    };
    private static final String[] EMOTICONS = {"lol", "xD", "^^", ":)", "haha", "lmao", ":P", "T_T"};

    /** The stored persona. */
    public record Persona(boolean lowercase, boolean shortForms, String laugh, String vibe, List<String> interests) {

        public String toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("lowercase", lowercase);
            json.put("shortForms", shortForms);
            json.put("laugh", laugh);
            json.put("vibe", vibe);
            json.put("interests", interests);
            return Json.write(json);
        }
    }

    private BotPersona() {
    }

    public static Persona generate(long seed) {
        Random rng = new Random(seed * 0x2545F4914F6CDD1DL + 13);
        List<String> interests = new ArrayList<>();
        while (interests.size() < 2) {
            String pick = INTERESTS[rng.nextInt(INTERESTS.length)];
            if (!interests.contains(pick)) {
                interests.add(pick);
            }
        }
        return new Persona(rng.nextDouble() < 0.85, rng.nextDouble() < 0.7, EMOTICONS[rng.nextInt(EMOTICONS.length)],
                VIBES[rng.nextInt(VIBES.length)], interests);
    }

    public static Persona parse(String json) {
        if (json == null || json.isBlank() || !(Json.parse(json) instanceof Map<?, ?> map)) {
            return null;
        }
        List<String> interests = new ArrayList<>();
        if (map.get("interests") instanceof List<?> list) {
            list.forEach(item -> interests.add(String.valueOf(item)));
        }
        return new Persona(Boolean.TRUE.equals(map.get("lowercase")), Boolean.TRUE.equals(map.get("shortForms")),
                String.valueOf(map.get("laugh")), String.valueOf(map.get("vibe")), interests);
    }

    /** The persona's half of the system prompt: who is typing and how. */
    public static String describe(Persona persona, double chattiness) {
        StringBuilder style = new StringBuilder();
        style.append(persona.lowercase() ? "type in all lowercase" : "type with normal capitals");
        if (persona.shortForms()) {
            style.append(", use u/ur/ppl/pls/ty/idk/nvm");
        }
        style.append(", laugh with \"").append(persona.laugh()).append("\" sometimes, no period at the end");
        String length = chattiness > 0.6 ? "1-2 short sentences" : chattiness > 0.25 ? "one short sentence" : "a few words";
        return "You are " + persona.vibe() + ". You care about " + String.join(" and ", persona.interests())
                + ". Chat style: " + style + ". Reply with " + length + ".";
    }
}
