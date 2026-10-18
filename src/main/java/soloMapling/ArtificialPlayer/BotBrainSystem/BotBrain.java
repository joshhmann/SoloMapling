package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Where a bot's free-text replies come from (bot-persistence ticket 12). A brain answers a chat with one line,
 * or with nothing - and nothing always means "use the scripted dialogue", so a caller never waits on, or breaks
 * because of, a model being down, slow or busy. Calls never block the caller's thread.
 */
public interface BotBrain {

    /** One message of the conversation sent to the model. */
    record Turn(Role role, String content) {
        public static Turn system(String content) {
            return new Turn(Role.SYSTEM, content);
        }

        public static Turn user(String content) {
            return new Turn(Role.USER, content);
        }

        public static Turn assistant(String content) {
            return new Turn(Role.ASSISTANT, content);
        }
    }

    enum Role {
        SYSTEM("system"), USER("user"), ASSISTANT("assistant");

        final String wire;

        Role(String wire) {
            this.wire = wire;
        }
    }

    /** The scripted fallback: never answers, so every caller uses its YAML dialogue. */
    BotBrain SCRIPTED = new BotBrain() {
        @Override
        public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public boolean isLive() {
            return false;
        }

        @Override
        public String describe() {
            return "scripted (no LLM endpoint configured)";
        }
    };

    /**
     * One cleaned reply line for this conversation, or empty when the caller should fall back. The future always
     * completes normally and within the configured timeout; it never completes exceptionally.
     *
     * @param playerId the real player the reply is for, for per-player rate limiting
     */
    CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation);

    /** Whether this brain can answer at all (false for the scripted fallback). */
    boolean isLive();

    /** A one-line description for the GM read-out. */
    String describe();
}
