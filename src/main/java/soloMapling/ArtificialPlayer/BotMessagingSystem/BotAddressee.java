package soloMapling.ArtificialPlayer.BotMessagingSystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who a chat line is aimed at, by name (bot-persistence ticket 19, layers 1-2): players shorten, misspell and
 * lowercase names ("hey resme" for Ressurectme, "yo sky" for xXSkyBladeXx). This scores every word of the line
 * against the names of the bots nearby - exact, the name's core without {@code xX..Xx} and digits, a prefix, the
 * word's letters in order inside the name, a typo or two - and says how sure it is. Pure and cheap: it runs on
 * every player line.
 */
public final class BotAddressee {

    /** At or above this a match starts a conversation; between this and {@link #ASK} the bot asks first. */
    public static final double SURE = 0.85;
    public static final double ASK = 0.68;
    /** The best match must beat the runner-up by this much, or the line is ambiguous. */
    static final double MARGIN = 0.08;

    /** Chat words that are never a nickname, however well they fit the start of a name. */
    private static final Set<String> COMMON = Set.of("the", "you", "your", "and", "hey", "hello", "lol", "lmao", "haha",
            "can", "pls", "plz", "please", "gtg", "cya", "any", "anyone", "what", "whats", "where", "how", "who", "why",
            "this", "that", "with", "for", "are", "not", "yes", "yeah", "yea", "nope", "nice", "thx", "thanks", "sell",
            "selling", "buy", "buying", "party", "need", "want", "wanna", "come", "here", "there", "good", "bad", "omg",
            "brb", "idk", "ok", "okay", "sure", "just", "like", "help", "guys", "all", "some", "one", "get", "got");

    /** A bot nearby; its job lets layer 3 find "the cleric" or "u with the bow". The name matcher ignores it. */
    public record Candidate(int id, String name, int jobId) {

        public Candidate(int id, String name) {
            this(id, name, -1);
        }
    }

    public record Match(int id, String name, double score) {
    }

    /** The outcome: the best match (or null), and whether it is sure, worth asking about, or ambiguous. */
    public record Result(Match best, Match runnerUp) {

        public boolean sure() {
            return best != null && best.score() >= SURE && !ambiguous();
        }

        public boolean worthAsking() {
            return best != null && best.score() >= ASK && !sure();
        }

        public boolean ambiguous() {
            return best != null && runnerUp != null && best.score() - runnerUp.score() < MARGIN;
        }
    }

    private BotAddressee() {
    }

    public static Result resolve(String line, Collection<Candidate> nearby) {
        List<String> words = words(line);
        Match best = null;
        Match second = null;
        for (Candidate candidate : nearby) {
            double score = 0;
            for (String word : words) {
                score = Math.max(score, score(word, candidate.name()));
            }
            if (score <= 0) {
                continue;
            }
            Match match = new Match(candidate.id(), candidate.name(), score);
            if (best == null || score > best.score()) {
                second = best;
                best = match;
            } else if (second == null || score > second.score()) {
                second = match;
            }
        }
        return new Result(best, second);
    }

    /** How well one typed word stands for a name, 0 (not at all) to 1 (exactly). */
    static double score(String word, String name) {
        String full = normalize(name);
        String core = core(full);
        if (word.isEmpty() || full.isEmpty()) {
            return 0;
        }
        if (word.equals(full)) {
            return 1.0;
        }
        if (COMMON.contains(word) || word.length() < 3) {
            return 0;
        }
        if (!core.isEmpty() && word.equals(core)) {
            return 0.95;
        }
        // A prefix has to be a real chunk of the name, not just its first syllable.
        int minPrefix = Math.max(3, Math.min(5, (core.isEmpty() ? full : core).length() / 2));
        boolean prefix = full.startsWith(word) || (!core.isEmpty() && core.startsWith(word));
        if (prefix && word.length() >= minPrefix) {
            return 0.88;
        }
        int distance = Math.min(editDistance(word, full), core.isEmpty() ? Integer.MAX_VALUE : editDistance(word, core));
        if (distance == 1 && word.length() >= 4) {
            return 0.86;
        }
        if (word.length() >= 4 && word.charAt(0) == full.charAt(0) && isSubsequence(word, full)) {
            // "resme" in "ressurectme": every letter in order, starting where the name starts.
            return 0.8;
        }
        if (prefix) {
            // A three-letter nickname ("sky" for xXSkyBladeXx) is natural but a guess: worth asking about.
            return 0.76;
        }
        if (distance == 2 && word.length() >= 6) {
            return 0.72;
        }
        return 0;
    }

    static List<String> words(String line) {
        List<String> words = new ArrayList<>();
        for (String raw : line.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!raw.isEmpty()) {
                words.add(raw);
            }
        }
        return words;
    }

    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The name without {@code xx..xx} wrapping and trailing digits: "xxskybladexx" -> "skyblade", "jun1567" -> "jun". */
    static String core(String normalized) {
        String core = normalized;
        if (core.startsWith("xx") && core.endsWith("xx") && core.length() > 6) {
            core = core.substring(2, core.length() - 2);
        }
        core = core.replaceAll("[0-9]+$", "");
        return core.length() >= 3 ? core : "";
    }

    static boolean isSubsequence(String word, String name) {
        int at = 0;
        for (int i = 0; i < name.length() && at < word.length(); i++) {
            if (name.charAt(i) == word.charAt(at)) {
                at++;
            }
        }
        return at == word.length();
    }

    static int editDistance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) {
            return Integer.MAX_VALUE;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
