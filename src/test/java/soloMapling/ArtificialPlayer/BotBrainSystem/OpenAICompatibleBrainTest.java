package soloMapling.ArtificialPlayer.BotBrainSystem;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAICompatibleBrainTest {

    private static final List<BotBrain.Turn> HAT = List.of(
            BotBrain.Turn.system("You are a MapleStory player."), BotBrain.Turn.user("yo nice hat, whered u get it"));

    private HttpServer server;
    private volatile int status = 200;
    private volatile String content = "got it at the quest shop in henesys lol";
    private volatile long delayMs;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", this::answer);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth == null ? "" : auth);
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            byte[] reply = Json.write(Map.of("choices", List.of(Map.of("index", 0,
                    "message", Map.of("role", "assistant", "content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    private OpenAICompatibleBrain brain(int timeoutMs, int maxConcurrent, int playerIntervalMs, String apiKey) {
        return new OpenAICompatibleBrain(new OpenAICompatibleBrain.Settings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", apiKey, "qwen-test", 40, 1.0,
                timeoutMs, maxConcurrent, playerIntervalMs,
                Map.of("chat_template_kwargs", "{\"enable_thinking\": false}", "top_k", "20", "broken", "{nope"),
                Map.of("X-Title", "SoloMapling")));
    }

    private static Optional<String> await(CompletableFuture<Optional<String>> reply) throws Exception {
        return reply.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aReplyComesBackCleanAndTheRequestCarriesTheConfiguredBody() throws Exception {
        Optional<String> reply = await(brain(3000, 2, 0, "sk-test").complete(1, HAT));

        assertEquals(Optional.of("got it at the quest shop in henesys lol"), reply);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) Json.parse(bodies.get(0));
        assertEquals("qwen-test", body.get("model"));
        assertEquals(Map.of("enable_thinking", false), body.get("chat_template_kwargs"));
        assertEquals(20L, body.get("top_k"));
        assertFalse(body.containsKey("broken"), "an invalid JSON fragment is dropped, not sent");
        assertEquals(2, ((List<?>) body.get("messages")).size());
        assertEquals("Bearer sk-test", authHeaders.get(0));
    }

    @Test
    void noKeyMeansNoAuthorizationHeader() throws Exception {
        await(brain(3000, 2, 0, "").complete(1, HAT));
        // Unless the environment supplies one, a local server gets no Authorization header at all.
        if (System.getenv("SOLOMAPLING_LLM_API_KEY") == null) {
            assertEquals("", authHeaders.get(0));
        }
    }

    @Test
    void leakedReasoningNeverReachesChat() throws Exception {
        content = "<think>the player likes the hat, answer casually</think>\nlol thanks bro, picked it up from the shop";
        assertEquals(Optional.of("lol thanks bro, picked it up from the shop"), await(brain(3000, 2, 0, "").complete(1, HAT)));

        content = "Thinking Process:\n1. The user compliments a hat.\n2. Reply casually.";
        assertEquals(Optional.empty(), await(brain(3000, 2, 0, "").complete(2, HAT)));
    }

    @Test
    void aSlowServerFallsBackWithinTheTimeout() throws Exception {
        delayMs = 2000;
        long started = System.nanoTime();
        Optional<String> reply = await(brain(300, 2, 0, "").complete(1, HAT));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(Optional.empty(), reply);
        assertTrue(tookMs < 1500, "fell back after " + tookMs + "ms");
    }

    @Test
    void aServerErrorFallsBack() throws Exception {
        status = 500;
        assertEquals(Optional.empty(), await(brain(3000, 2, 0, "").complete(1, HAT)));
    }

    @Test
    void anUnreachableServerFallsBack() throws Exception {
        OpenAICompatibleBrain dead = new OpenAICompatibleBrain(new OpenAICompatibleBrain.Settings(
                "http://127.0.0.1:1/v1", "", "", 40, 1.0, 1000, 2, 0, Map.of(), Map.of()));
        assertEquals(Optional.empty(), await(dead.complete(1, HAT)));
    }

    @Test
    void callsInFlightNeverExceedTheCap() throws Exception {
        delayMs = 200;
        OpenAICompatibleBrain brain = brain(5000, 1, 0, "");
        List<CompletableFuture<Optional<String>>> replies = new ArrayList<>();
        for (int player = 0; player < 4; player++) {
            replies.add(brain.complete(player, HAT));
        }
        for (CompletableFuture<Optional<String>> reply : replies) {
            assertTrue(await(reply).isPresent());
        }
        assertEquals(1, maxInFlight.get());
    }

    @Test
    void queuedCallsPastTheDeadlineFallBackInsteadOfWaiting() throws Exception {
        delayMs = 400;
        OpenAICompatibleBrain brain = brain(600, 1, 0, "");
        CompletableFuture<Optional<String>> first = brain.complete(1, HAT);
        CompletableFuture<Optional<String>> second = brain.complete(2, HAT);
        CompletableFuture<Optional<String>> third = brain.complete(3, HAT);

        assertTrue(await(first).isPresent());
        // One slot, 400 ms per answer, a 600 ms budget: the third caller cannot be served in time.
        assertEquals(Optional.empty(), await(third));
        await(second);
    }

    @Test
    void oneChattyPlayerIsRateLimited() throws Exception {
        OpenAICompatibleBrain brain = brain(3000, 2, 5000, "");
        assertTrue(await(brain.complete(7, HAT)).isPresent());
        assertEquals(Optional.empty(), await(brain.complete(7, HAT)));
        assertTrue(await(brain.complete(8, HAT)).isPresent(), "another player is not affected");
    }

    @Test
    void noBaseUrlMeansScriptedDialogue() {
        ServerConfig config = new ServerConfig();
        assertSame(BotBrain.SCRIPTED, BotBrains.fromConfig(config));
        config.BOT_LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        assertTrue(BotBrains.fromConfig(config).isLive());
    }
}
