package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Movement that learns from its own failures (bot-persistence ticket 16). Every time a bot gives up on a planned
 * move - parked against a jump or drop window it never gets into, or stuck until the unstuck hop - that edge of the
 * map's navigation graph gets a failure; every time a bot completes a move it gets a success. The planner multiplies
 * an edge's cost by {@link #factor}: an edge that keeps failing becomes up to 3x as expensive, one that nearly
 * always fails 8x, so routes bend around a bad jump without any edge ever being forbidden (a map never becomes
 * unreachable because of what was learned). Counts fade with a 12-hour half-life, so a fixed map or a bot with
 * better stats gets another chance.
 *
 * <p>Learned counts are kept per navigation graph (map, movement profile, graph version) under
 * {@code cache/bot-nav/learned/} and dropped when the graph version changes, like the graph cache itself.
 */
public final class NavEdgeLearning {

    private static final Logger log = LoggerFactory.getLogger(NavEdgeLearning.class);

    static final Path DIR = Path.of("cache", "bot-nav", "learned");
    static final long HALF_LIFE_MS = TimeUnit.HOURS.toMillis(12);
    static final double MAX_FACTOR = 3.0;
    static final double BAD_FACTOR = 8.0;

    /** One edge's fading record. */
    static final class Stats {
        double failures;
        double successes;
        long at;

        void fade(long now) {
            if (at > 0 && now > at) {
                double keep = Math.pow(0.5, (now - at) / (double) HALF_LIFE_MS);
                failures *= keep;
                successes *= keep;
            }
            at = now;
        }
    }

    /** Everything learned on one graph. */
    static final class Book {
        final int version;
        final Map<String, Stats> edges = new ConcurrentHashMap<>();
        /** The planner asks per edge object on every relaxation: resolve each edge's key once. */
        final Map<BotNavigationGraph.Edge, Stats> byEdge = new ConcurrentHashMap<>();
        volatile boolean dirty;

        Book(int version) {
            this.version = version;
        }
    }

    private static final Map<String, Book> books = new ConcurrentHashMap<>();
    /** Graph instance -> its book, so the hot path builds no key strings (graphs compare by identity). */
    private static final Map<BotNavigationGraph, Book> byGraph =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Marks an edge that has no record, so the planner does not look it up again. */
    private static final Stats NONE = new Stats();
    private static volatile Path dir = DIR;

    private NavEdgeLearning() {
    }

    /** A bot gave up on this edge. */
    static void failure(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        record(graph, edge, true, System.currentTimeMillis());
    }

    /** A bot completed this edge. */
    static void success(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        record(graph, edge, false, System.currentTimeMillis());
    }

    private static volatile boolean flusherStarted;

    static void record(BotNavigationGraph graph, BotNavigationGraph.Edge edge, boolean failed, long now) {
        if (graph == null || edge == null || edge.type == BotNavigationGraph.EdgeType.PORTAL) {
            return; // a portal does not fail by technique
        }
        startFlusher();
        Book book = bookOf(graph);
        Stats stats = book.edges.computeIfAbsent(edgeKey(edge), k -> new Stats());
        book.byEdge.put(edge, stats);
        synchronized (stats) {
            stats.fade(now);
            if (failed) {
                stats.failures += 1;
            } else {
                stats.successes += 1;
            }
        }
        book.dirty = true;
    }

    /** How much more this edge costs in the planner than it was built to: 1.0 for anything never seen failing. */
    static double factor(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        if (graph == null || edge == null) {
            return 1.0;
        }
        Book book = bookOf(graph);
        if (book.edges.isEmpty()) {
            return 1.0; // nearly every graph: nothing learned, nothing to look up
        }
        Book resolved = book;
        Stats stats = book.byEdge.computeIfAbsent(edge, e -> resolved.edges.getOrDefault(edgeKey(e), NONE));
        if (stats == NONE) {
            return 1.0;
        }
        double f;
        double s;
        synchronized (stats) {
            stats.fade(System.currentTimeMillis());
            f = stats.failures;
            s = stats.successes;
        }
        return factorFor(f, s);
    }

    static double factorFor(double failures, double successes) {
        double seen = failures + successes;
        if (failures < 0.5 || seen <= 0) {
            return 1.0;
        }
        double failRate = failures / seen;
        if (failures >= 5 && failRate >= 0.8) {
            return BAD_FACTOR;
        }
        double confidence = Math.min(1.0, seen / 4.0);
        return 1.0 + (MAX_FACTOR - 1.0) * failRate * confidence;
    }

    /** The planner's cost for an edge: its built cost, scaled by what was learned. */
    static int scale(BotNavigationGraph graph, BotNavigationGraph.Edge edge, int cost) {
        if (cost <= 0) {
            return cost;
        }
        double factor = factor(graph, edge);
        return factor == 1.0 ? cost : (int) Math.min(Integer.MAX_VALUE / 4, Math.round(cost * factor));
    }

    /** An edge's identity inside its graph: the regions it joins, its type and its launch window. */
    static String edgeKey(BotNavigationGraph.Edge edge) {
        return edge.fromRegionId + ">" + edge.toRegionId + ":" + edge.type + ":" + edge.launchMinX + ":" + edge.launchMaxX
                + ":" + edge.startPoint.x + "," + edge.startPoint.y;
    }

    static String bookKey(BotNavigationGraph graph) {
        BotMovementProfile p = graph.movementProfile;
        return graph.mapId + "-s" + p.totalSpeedStat() + "-j" + p.totalJumpStat() + (p.snowShoes() ? "-ss" : "");
    }

    private static Book bookOf(BotNavigationGraph graph) {
        Book book = byGraph.get(graph);
        if (book == null) {
            book = book(graph);
            byGraph.put(graph, book);
        }
        return book;
    }

    private static Book book(BotNavigationGraph graph) {
        return books.compute(bookKey(graph), (key, existing) -> {
            if (existing != null && existing.version == graph.version) {
                return existing;
            }
            Book loaded = load(key, graph.version);
            return loaded != null ? loaded : new Book(graph.version);
        });
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    /** Writes what was learned every five minutes, and once more when bot activity stops at shutdown. */
    private static void startFlusher() {
        if (flusherStarted) {
            return;
        }
        synchronized (NavEdgeLearning.class) {
            if (flusherStarted) {
                return;
            }
            flusherStarted = true;
            var future = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                    .scheduleAtFixedRate(NavEdgeLearning::flush, 5, 5, TimeUnit.MINUTES);
            soloMapling.server.BotShutdown.onQuiesce(() -> {
                future.cancel(false);
                flush();
            });
        }
    }

    /** Writes every book that changed since the last flush. Called periodically and at shutdown. */
    public static int flush() {
        int written = 0;
        for (Map.Entry<String, Book> entry : books.entrySet()) {
            Book book = entry.getValue();
            if (!book.dirty) {
                continue;
            }
            book.dirty = false;
            Map<String, Object> edges = new LinkedHashMap<>();
            long now = System.currentTimeMillis();
            book.edges.forEach((key, stats) -> {
                synchronized (stats) {
                    stats.fade(now);
                    if (stats.failures + stats.successes >= 0.05) {
                        edges.put(key, List.of(round(stats.failures), round(stats.successes), stats.at));
                    }
                }
            });
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("version", book.version);
            json.put("edges", edges);
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(entry.getKey() + ".json"), Json.write(json), StandardCharsets.UTF_8);
                written++;
            } catch (IOException e) {
                book.dirty = true;
                log.debug("Could not write learned nav data {}", entry.getKey(), e);
            }
        }
        return written;
    }

    private static Book load(String key, int version) {
        Path file = dir.resolve(key + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)
                    || !(root.get("version") instanceof Number v) || v.intValue() != version
                    || !(root.get("edges") instanceof Map<?, ?> edges)) {
                return null; // another graph version: what it learned does not describe this graph
            }
            Book book = new Book(version);
            for (Map.Entry<?, ?> e : edges.entrySet()) {
                if (e.getValue() instanceof List<?> l && l.size() == 3) {
                    Stats stats = new Stats();
                    stats.failures = ((Number) l.get(0)).doubleValue();
                    stats.successes = ((Number) l.get(1)).doubleValue();
                    stats.at = ((Number) l.get(2)).longValue();
                    book.edges.put(String.valueOf(e.getKey()), stats);
                }
            }
            return book;
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable learned nav data {}", file, e);
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** For the GM read-out: maps with learned data, and the edges on them that are currently penalised. */
    public static String describe() {
        int penalised = 0;
        for (Book book : books.values()) {
            for (Stats stats : book.edges.values()) {
                if (factorFor(stats.failures, stats.successes) > 1.0) {
                    penalised++;
                }
            }
        }
        return "nav learning: " + books.size() + " graphs, " + penalised + " edges penalised";
    }

    /** Test seam: a scratch directory (null restores the cache dir) and a clean slate. */
    static void useDirForTest(Path testDir) {
        dir = testDir == null ? DIR : testDir;
        books.clear();
        byGraph.clear();
    }

    static Set<String> loadedBooks() {
        return books.keySet();
    }
}
