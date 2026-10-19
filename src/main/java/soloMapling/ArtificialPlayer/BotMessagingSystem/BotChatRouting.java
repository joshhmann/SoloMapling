package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The state around addressing a bot (bot-persistence ticket 19): which bot spoke last on each map, the bots that
 * asked "u talking to me?" and are waiting for a yes, and the decision model that picks the addressee of a line
 * with no usable name ("ill come" after a bot asked for a party, "can i get a bless" near a cleric).
 */
public final class BotChatRouting {

    private static final Logger log = LoggerFactory.getLogger(BotChatRouting.class);

    /** A bot's line stays "the last thing said" on its map for this long. */
    static final long LAST_SPEAKER_MS = TimeUnit.SECONDS.toMillis(45);
    /** A "u talking to me?" waits this long for its answer. */
    static final long CONFIRM_MS = TimeUnit.SECONDS.toMillis(20);

    private static final List<String> ASK_LINES = List.of("u talking to me?", "me?", "who me?", "hm? u talkin to me?",
            "wait me?");
    private static final Set<String> YES = Set.of("ya", "yah", "yes", "yeah", "yea", "yep", "yup", "ye", "mhm", "u",
            "you", "ofc", "sure", "ok", "okay", "yes u", "ya u");

    public record LastSpeaker(int botId, String name, String line, long at) {
    }

    /** A bot waiting to hear whether it was meant. */
    public record Pending(int botId, ChatMessage original, long until) {
    }

    private static final Map<Integer, LastSpeaker> lastSpeakerByMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Pending> pendingBySender = new ConcurrentHashMap<>();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();

    private BotChatRouting() {
    }

    /** Every bot line passes here (from {@code SocialCommands.BotSpeak}). */
    public static void noteBotSpoke(Character bot, String line) {
        if (bot != null && bot.getMap() != null) {
            lastSpeakerByMap.put(bot.getMapId(), new LastSpeaker(bot.getId(), bot.getName(), line,
                    System.currentTimeMillis()));
        }
    }

    public static LastSpeaker lastSpeaker(int mapId, long now) {
        LastSpeaker last = lastSpeakerByMap.get(mapId);
        return last != null && now - last.at() <= LAST_SPEAKER_MS ? last : null;
    }

    /** The bot asks whether it was meant, and waits for the answer. */
    public static String askFirst(int botId, ChatMessage original, long now) {
        pendingBySender.put(original.getSender().getId(), new Pending(botId, original, now + CONFIRM_MS));
        return ASK_LINES.get(ThreadLocalRandom.current().nextInt(ASK_LINES.size()));
    }

    /**
     * If this sender owes a bot an answer: the bot it confirms (a yes, or the bot's name), or empty when the line
     * declines or is about something else. A pending question is used once, and expires.
     */
    public static Optional<Pending> confirmation(ChatMessage message, String botName, long now) {
        Pending pending = pendingBySender.remove(message.getSender().getId());
        if (pending == null || now > pending.until()) {
            return Optional.empty();
        }
        String text = message.getContent().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim();
        boolean yes = YES.contains(text) || text.startsWith("yes") || text.startsWith("yea") || text.startsWith("ya ")
                || (botName != null && BotAddressee.score(text.split(" ")[0], botName) >= BotAddressee.ASK);
        return yes ? Optional.of(pending) : Optional.empty();
    }

    /** The bot a pending question is from, without answering it. */
    public static Pending pendingFor(int senderId) {
        return pendingBySender.get(senderId);
    }

    // ── layer 3: who a nameless line is for ─────────────────────────────────────────────────────

    /*
     * Probed against GLiNER2.5-Decide (2026-10-18): asked to pick one of four nearby players plus "none", it chose
     * the same option for every line (0/8). Split into small questions it does better, but it still took "lol" and
     * "brb mom says dinner" for answers to a party call. So the reply case is a rule, and the model answers only
     * the question it is good at - which kind of player a request is for (bless -> cleric, "the thief").
     */

    private static final Pattern ACCEPT = Pattern.compile(
            "^(ill come|i'?ll come|me|me too|count me in|im in|i'?m in|sure|ok|okay|k|yes|ya|yea|yeah|yep|inv|inv me"
                    + "|invite me|inv pls|lets go|let'?s go|omw|on my way|i can|i wanna|i want in)\\b.*");
    private static final Pattern NOT_A_REPLY = Pattern.compile(".*\\b(brb|afk|gtg|lol|lmao|haha|selling|buying|wts|wtb)\\b.*");
    private static final Pattern ASKS_SOMETHING = Pattern.compile(".*(\\?|\\b(anyone|wanna|who|need|party|pt|lf|looking for|come)\\b).*");
    private static final Pattern TRADE_TALK = Pattern.compile(".*\\b(selling|buying|wts|wtb|s>|b>|price|pc)\\b.*");
    private static final Pattern REQUEST = Pattern.compile(".*(\\?|\\b(u|you|ur|can i|could i|pls|plz|please|anyone|which|who)\\b).*");

    /**
     * The bot that just spoke, when this line answers it: the bot asked something or was recruiting, and the line is
     * a short acceptance ("ill come", "sure", "inv me") rather than chatter.
     */
    public static Optional<Integer> replyToLastSpeaker(String line, LastSpeaker last) {
        if (last == null) {
            return Optional.empty();
        }
        String text = line.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ?]", " ").replaceAll("\\s+", " ").trim();
        String said = last.line() == null ? "" : last.line().toLowerCase(Locale.ROOT);
        if (text.split(" ").length > 6 || NOT_A_REPLY.matcher(text).matches() || !ACCEPT.matcher(text).matches()
                || !ASKS_SOMETHING.matcher(said).matches()) {
            return Optional.empty();
        }
        return Optional.of(last.botId());
    }

    /** The classes a request can point at, by job id ({@code jobId / 100}, with clerics split out of magicians). */
    public enum JobFamily {
        CLERIC("a cleric, priest or bishop - for heal, bless or holy symbol"),
        MAGICIAN("a magician or mage"),
        BOWMAN("a bowman, hunter or crossbowman - someone with a bow"),
        THIEF("a thief, assassin or bandit"),
        WARRIOR("a warrior, fighter, page or spearman"),
        PIRATE("a pirate");

        final String description;

        JobFamily(String description) {
            this.description = description;
        }

        public static JobFamily of(int jobId) {
            if (jobId < 100) {
                return null;
            }
            return switch (jobId / 100) {
                case 1 -> WARRIOR;
                case 2 -> jobId / 10 == 23 ? CLERIC : MAGICIAN;
                case 3 -> BOWMAN;
                case 4 -> THIEF;
                case 5 -> PIRATE;
                default -> null;
            };
        }
    }

    public record Choice(int botId, double confidence) {
    }

    public static boolean decideConfigured() {
        ServerConfig server = YamlConfig.config.server;
        return server != null && server.BOT_DECIDE_URL != null && !server.BOT_DECIDE_URL.isBlank();
    }

    /**
     * For a request with no usable name ("which one of u is the thief", "u with the bow"): asks the decision model
     * which kind of player it wants, and returns the one nearby bot of that kind. Buff requests ("bless pls") never
     * get here - {@code BotBuffRequestHandler} answers them. Blocking with a short
     * timeout - call it off the dispatcher. Empty when the line is not a request, the kind is unclear, no bot or
     * several bots of it are near, on any failure, and when no endpoint is configured.
     */
    public static Optional<Choice> decideByJob(String line, List<BotAddressee.Candidate> nearby) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (nearby.isEmpty() || TRADE_TALK.matcher(lower).matches() || !REQUEST.matcher(lower).matches()) {
            return Optional.empty();
        }
        if (!decideConfigured()) {
            return Optional.empty();
        }
        ServerConfig server = YamlConfig.config.server;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(server.BOT_DECIDE_URL) + "/v1/systemone"))
                    .timeout(Duration.ofMillis(server.BOT_DECIDE_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(jobRequest(line))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.debug("Decision model answered HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }
            return parseJob(response.body()).flatMap(pick -> onlyOne(nearby, pick.family(), pick.confidence()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Decision model unavailable: {}", e.toString());
            return Optional.empty();
        }
    }

    record JobPick(JobFamily family, double confidence) {
    }

    /** The bot of that kind, when exactly one is nearby (with several, nobody can tell which was meant). */
    private static Optional<Choice> onlyOne(List<BotAddressee.Candidate> nearby, JobFamily family, double confidence) {
        List<BotAddressee.Candidate> ofKind = nearby.stream().filter(c -> JobFamily.of(c.jobId()) == family).toList();
        return ofKind.size() == 1 ? Optional.of(new Choice(ofKind.get(0).id(), confidence)) : Optional.empty();
    }

    public static Map<String, Object> jobRequest(String line) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (JobFamily family : JobFamily.values()) {
            criteria.put(family.name().toLowerCase(Locale.ROOT), family.description);
        }
        criteria.put("any", "no particular kind of player");
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", "Which kind of player does this line ask for or point at?");
        question.put("criteria", criteria);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "decide");
        body.put("state", "The player typed: " + clean(line));
        body.put("questions", Map.of("need", question));
        return body;
    }

    static Optional<JobPick> parseJob(String responseBody) {
        if (!(Json.parse(responseBody) instanceof Map<?, ?> root)
                || !(root.get("answers") instanceof Map<?, ?> answers)
                || !(answers.get("need") instanceof Map<?, ?> need)
                || !(need.get("choice") instanceof String choice)
                || !(need.get("confidence") instanceof Number confidence)) {
            return Optional.empty();
        }
        for (JobFamily family : JobFamily.values()) {
            if (family.name().equalsIgnoreCase(choice)) {
                return Optional.of(new JobPick(family, confidence.doubleValue()));
            }
        }
        return Optional.empty();
    }

    /** The decision server rejects some characters (a ")" in a criterion is a 400); names and chat are user text. */
    static String clean(String text) {
        return text == null ? "" : text.replaceAll("[^A-Za-z0-9 ,.'!?%-]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
