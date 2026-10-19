package soloMapling.ArtificialPlayer.BotBrainSystem;

import config.ServerConfig;
import config.YamlConfig;

import java.util.Map;

/**
 * The brain the server's bots use, built once from {@code config.yaml}: an {@link OpenAICompatibleBrain} when
 * {@code BOT_LLM_BASE_URL} is set, the scripted fallback otherwise.
 */
public final class BotBrains {

    private static volatile BotBrain brain;

    private BotBrains() {
    }

    public static BotBrain get() {
        BotBrain current = brain;
        if (current == null) {
            synchronized (BotBrains.class) {
                if (brain == null) {
                    brain = fromConfig(YamlConfig.config.server);
                }
                current = brain;
            }
        }
        return current;
    }

    static BotBrain fromConfig(ServerConfig server) {
        if (server == null || server.BOT_LLM_BASE_URL == null || server.BOT_LLM_BASE_URL.isBlank()) {
            return BotBrain.SCRIPTED;
        }
        return new OpenAICompatibleBrain(new OpenAICompatibleBrain.Settings(
                server.BOT_LLM_BASE_URL.trim(),
                orEmpty(server.BOT_LLM_API_KEY),
                orEmpty(server.BOT_LLM_MODEL),
                server.BOT_LLM_MAX_TOKENS,
                server.BOT_LLM_TEMPERATURE,
                server.BOT_LLM_TIMEOUT_MS,
                server.BOT_LLM_MAX_CONCURRENT,
                server.BOT_LLM_PLAYER_MIN_INTERVAL_MS,
                orEmpty(server.BOT_LLM_EXTRA_BODY),
                orEmpty(server.BOT_LLM_EXTRA_HEADERS),
                orEmpty(server.BOT_LLM_NOTES_MODEL)));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> orEmpty(Map<String, String> value) {
        return value == null ? Map.of() : value;
    }
}
