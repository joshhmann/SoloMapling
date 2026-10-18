package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A brain backed by any server that speaks OpenAI's {@code POST /chat/completions}: OpenRouter, Ollama,
 * llama.cpp server, LM Studio, vLLM. The server is chosen entirely by config (base URL, key, model, extra body).
 *
 * <p>Every call runs on its own virtual thread and is bounded three ways, so a bot never waits on the model:
 * a global cap on calls in flight (match the server's parallel slots), a minimum interval per player, and one
 * deadline that covers both the wait for a free slot and the request itself. Whatever goes wrong, the
 * future completes with empty and the caller uses its scripted line.
 */
public final class OpenAICompatibleBrain implements BotBrain {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleBrain.class);

    /** Everything the brain is configured with; built from {@code config.yaml} by {@link BotBrains}. */
    public record Settings(String baseUrl, String apiKey, String model, int maxTokens, double temperature,
                           int timeoutMs, int maxConcurrent, int playerMinIntervalMs,
                           Map<String, String> extraBody, Map<String, String> extraHeaders) {
    }

    private final Settings settings;
    private final URI endpoint;
    private final HttpClient http;
    private final Semaphore slots;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Integer, Long> lastCallByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Json.Raw> extraBody = new LinkedHashMap<>();

    private final AtomicLong answered = new AtomicLong();
    private final AtomicLong fellBack = new AtomicLong();
    private final AtomicLong totalAnswerMs = new AtomicLong();
    private volatile String lastFailure = "none";

    public OpenAICompatibleBrain(Settings settings) {
        this.settings = settings;
        String base = settings.baseUrl().endsWith("/") ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1)
                : settings.baseUrl();
        this.endpoint = URI.create(base + "/chat/completions");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMs())).build();
        this.slots = new Semaphore(Math.max(1, settings.maxConcurrent()), true);
        for (Map.Entry<String, String> entry : settings.extraBody().entrySet()) {
            if (Json.isValid(entry.getValue())) {
                extraBody.put(entry.getKey(), new Json.Raw(entry.getValue()));
            } else {
                log.warn("BOT_LLM_EXTRA_BODY.{} is not valid JSON and is ignored: {}", entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
        long now = System.currentTimeMillis();
        Long last = lastCallByPlayer.get(playerId);
        if (last != null && now - last < settings.playerMinIntervalMs()) {
            return fallback("rate limited");
        }
        lastCallByPlayer.put(playerId, now);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.timeoutMs());
        CompletableFuture<Optional<String>> result = new CompletableFuture<>();
        calls.execute(() -> result.complete(call(conversation, deadline)));
        return result;
    }

    private Optional<String> call(List<Turn> conversation, long deadline) {
        boolean acquired = false;
        long started = System.nanoTime();
        try {
            acquired = slots.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!acquired) {
                return fail("no free slot before the deadline");
            }
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMs <= 0) {
                return fail("deadline passed while queued");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(leftMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(conversation)));
            String key = apiKey();
            if (!key.isEmpty()) {
                request.header("Authorization", "Bearer " + key);
            }
            settings.extraHeaders().forEach(request::header);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return fail("HTTP " + response.statusCode());
            }
            Optional<String> line = ReplyCleaner.clean(replyText(response.body()));
            if (line.isEmpty()) {
                return fail("empty after cleaning");
            }
            answered.incrementAndGet();
            totalAnswerMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("interrupted");
        } catch (Exception e) {
            return fail(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }

    String requestBody(List<Turn> conversation) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!settings.model().isEmpty()) {
            body.put("model", settings.model());
        }
        List<Object> messages = new ArrayList<>();
        for (Turn turn : conversation) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", turn.role().wire);
            message.put("content", turn.content());
            messages.add(message);
        }
        body.put("messages", messages);
        body.put("max_tokens", settings.maxTokens());
        body.put("temperature", settings.temperature());
        body.put("stream", false);
        body.putAll(extraBody);
        return Json.write(body);
    }

    /** {@code choices[0].message.content}, or null when the response has no such text. */
    static String replyText(String responseBody) {
        if (!(Json.parse(responseBody) instanceof Map<?, ?> root)
                || !(root.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)) {
            return null;
        }
        return content;
    }

    private String apiKey() {
        if (!settings.apiKey().isEmpty()) {
            return settings.apiKey();
        }
        String env = System.getenv("SOLOMAPLING_LLM_API_KEY");
        return env == null ? "" : env.trim();
    }

    private CompletableFuture<Optional<String>> fallback(String why) {
        return CompletableFuture.completedFuture(fail(why));
    }

    private Optional<String> fail(String why) {
        fellBack.incrementAndGet();
        lastFailure = why;
        log.debug("Bot LLM fell back to scripted dialogue: {}", why);
        return Optional.empty();
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public String describe() {
        long ok = answered.get();
        return String.format("%s model=%s slots=%d/%d answered=%d (avg %dms) fell back=%d (last: %s)",
                endpoint, settings.model().isEmpty() ? "(server default)" : settings.model(),
                slots.availablePermits(), Math.max(1, settings.maxConcurrent()), ok,
                ok == 0 ? 0 : totalAnswerMs.get() / ok, fellBack.get(), lastFailure);
    }
}
