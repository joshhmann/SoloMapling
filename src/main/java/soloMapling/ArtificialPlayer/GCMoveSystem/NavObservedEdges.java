package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/**
 * Movement that learns from real players (bot-persistence ticket 17). The graph builder only tries jumps from
 * sampled points on each platform, so a jump launched from a spot it skipped never becomes an edge - but a player
 * finds it. Every move packet of a real player carries a foothold id (0 while airborne): ground on foothold A, a
 * stretch in the air, ground on foothold B is a jump. When the graph has no edge between the two regions and players
 * made that jump at least {@link #ADOPT_AFTER} times from about the same spot, the jump is re-simulated with the bots'
 * own physics from the player's takeoff point; if it lands in the same region, it is proven possible for a bot and
 * joins the map as an ordinary JUMP edge - kept in an overlay next to the cached graph, never written into it, so
 * planners reading the graph concurrently are untouched. Lag, knockback and anything the physics cannot reproduce
 * fail the re-simulation and are dropped.
 */
public final class NavObservedEdges {

    private static final Logger log = LoggerFactory.getLogger(NavObservedEdges.class);

    static final int ADOPT_AFTER = 2;
    /** Takeoff points within this many pixels count as the same spot. */
    static final int SPOT_PX = 20;
    static final Path DIR = Path.of("cache", "bot-nav", "observed");

    /** One player's jump, as the packets showed it. */
    record Transition(int mapId, int fromFh, Point takeoff, int toFh, Point landing) {
    }

    private static final class Track {
        int mapId = -1;
        int groundFh;
        Point groundPoint;
        boolean airborne;
    }

    /** Adopted edges of one map, for the graph version they were proven on. */
    private record Overlay(int version, List<BotNavigationGraph.Edge> edges) {
    }

    private static final Map<Integer, Track> tracks = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sightings = new ConcurrentHashMap<>();
    private static final Map<Integer, Overlay> overlays = new ConcurrentHashMap<>();
    private static volatile Path dir = DIR;
    /** Test seam: where captured jumps go instead of being considered. */
    static volatile java.util.function.Consumer<Transition> sink;
    private static volatile IntFunction<MapleMap> maps = soloMapling.server.SoloMaplingUtilities::getMapleMapById;

    private NavObservedEdges() {
    }

    // ── capture (packet thread: keep it cheap) ─────────────────────────────────────────────────

    /** One movement element of a real player. {@code fh} is 0 while airborne. */
    public static void onPlayerMove(Character player, int x, int y, int fh) {
        if (player == null || player.getMap() == null) {
            return;
        }
        Track track = tracks.computeIfAbsent(player.getId(), k -> new Track());
        int mapId = player.getMapId();
        Transition jumped = null;
        synchronized (track) {
            if (track.mapId != mapId) {
                track.mapId = mapId;
                track.groundFh = 0;
                track.airborne = false;
            }
            if (fh == 0) {
                track.airborne = track.groundFh != 0 || track.airborne;
                return;
            }
            if (track.airborne && track.groundFh != 0 && fh != track.groundFh) {
                jumped = new Transition(mapId, track.groundFh, track.groundPoint, fh, new Point(x, y));
            }
            track.groundFh = fh;
            track.groundPoint = new Point(x, y);
            track.airborne = false;
        }
        if (jumped != null) {
            Transition t = jumped;
            java.util.function.Consumer<Transition> testSink = sink;
            if (testSink != null) {
                testSink.accept(t);
            } else {
                ExecutorServiceManager.runAsync(() -> consider(t));
            }
        }
    }

    /** The player left the server: forget their track. */
    public static void forget(int playerId) {
        tracks.remove(playerId);
    }

    // ── learning (off the packet thread) ──────────────────────────────────────────────────────

    /** Counts a jump, and adopts it once it was seen often enough and the physics can repeat it. */
    static boolean consider(Transition t) {
        MapleMap map = maps.apply(t.mapId());
        if (map == null) {
            return false;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
        return graph != null && consider(t, map, graph);
    }

    static boolean consider(Transition t, MapleMap map, BotNavigationGraph graph) {
        int from = graph.regionIdByFootholdId.getOrDefault(t.fromFh(), -1);
        int to = graph.regionIdByFootholdId.getOrDefault(t.toFh(), -1);
        if (from < 0 || to < 0 || from == to || joined(graph, from, to)) {
            return false;
        }
        int dir = Integer.signum(t.landing().x - t.takeoff().x);
        String key = t.mapId() + ":" + from + ">" + to + ":" + dir + ":" + Math.floorDiv(t.takeoff().x, SPOT_PX);
        if (sightings.merge(key, 1, Integer::sum) < ADOPT_AFTER) {
            return false;
        }
        BotNavigationGraph.Edge proven = prove(map, graph, from, to, t.takeoff(), dir);
        if (proven == null) {
            return false;
        }
        adopt(graph, proven);
        sightings.remove(key);
        log.info("Bots learned a jump on map {} from players: region {} -> {} at x={}", t.mapId(), from, to,
                t.takeoff().x);
        return true;
    }

    /** Re-simulates the jump with the bots' own physics; the edge when it lands in the region the player reached. */
    static BotNavigationGraph.Edge prove(MapleMap map, BotNavigationGraph graph, int from, int to, Point takeoff, int dir) {
        int step = BotPhysicsEngine.walkStep(map, graph.movementProfile);
        for (int stepX : dir == 0 ? new int[]{0} : new int[]{dir * step, 0}) {
            BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, takeoff, stepX,
                    graph.movementProfile);
            if (landing == null || landing.foothold() == null) {
                continue;
            }
            int landed = graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
            if (landed == to) {
                return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.JUMP, takeoff,
                        landing.point(), takeoff.x, takeoff.x, stepX, 0, 0, 0, 0, Math.max(1, landing.timeMs()));
            }
        }
        return null;
    }

    private static boolean joined(BotNavigationGraph graph, int from, int to) {
        for (BotNavigationGraph.Edge edge : graph.getOutgoing(from)) {
            if (edge.toRegionId == to) {
                return true;
            }
        }
        for (BotNavigationGraph.Edge edge : overlay(graph)) {
            if (edge.fromRegionId == from && edge.toRegionId == to) {
                return true;
            }
        }
        return false;
    }

    private static void adopt(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        Overlay overlay = overlays.compute(graph.mapId, (id, old) ->
                old != null && old.version() == graph.version ? old : new Overlay(graph.version, new CopyOnWriteArrayList<>()));
        overlay.edges().add(edge);
        save(graph.mapId, overlay);
    }

    // ── the planner's view ────────────────────────────────────────────────────────────────────

    /**
     * A region's outgoing edges: the graph's own, plus jumps learned from players. No allocation when nothing was
     * learned on the map (nearly always).
     */
    static List<BotNavigationGraph.Edge> outgoing(BotNavigationGraph graph, int regionId) {
        List<BotNavigationGraph.Edge> own = graph.getOutgoing(regionId);
        List<BotNavigationGraph.Edge> learned = overlay(graph);
        if (learned.isEmpty() || !appliesTo(graph)) {
            return own;
        }
        List<BotNavigationGraph.Edge> all = null;
        for (BotNavigationGraph.Edge edge : learned) {
            if (edge.fromRegionId == regionId) {
                if (all == null) {
                    all = new ArrayList<>(own);
                }
                all.add(edge);
            }
        }
        return all == null ? own : all;
    }

    /** Learned jumps were proven with the base profile: a bot at least that fast and springy can make them. */
    private static boolean appliesTo(BotNavigationGraph graph) {
        BotMovementProfile base = BotMovementProfile.base();
        return graph.movementProfile.totalSpeedStat() >= base.totalSpeedStat()
                && graph.movementProfile.totalJumpStat() >= base.totalJumpStat();
    }

    private static List<BotNavigationGraph.Edge> overlay(BotNavigationGraph graph) {
        Overlay overlay = overlays.computeIfAbsent(graph.mapId, id -> load(id, graph.version));
        return overlay.version() == graph.version ? overlay.edges() : List.of();
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    private static void save(int mapId, Overlay overlay) {
        List<Object> edges = new ArrayList<>();
        for (BotNavigationGraph.Edge e : overlay.edges()) {
            edges.add(List.of(e.fromRegionId, e.toRegionId, e.launchStepX, e.startPoint.x, e.startPoint.y,
                    e.endPoint.x, e.endPoint.y, e.cost));
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", overlay.version());
        json.put("edges", edges);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(mapId + ".json"), Json.write(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not save learned jumps of map {}", mapId, e);
        }
    }

    private static Overlay load(int mapId, int version) {
        Path file = dir.resolve(mapId + ".json");
        List<BotNavigationGraph.Edge> edges = new CopyOnWriteArrayList<>();
        if (Files.exists(file)) {
            try {
                if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                        && root.get("version") instanceof Number v && v.intValue() == version
                        && root.get("edges") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof List<?> f && f.size() == 8) {
                            int[] n = f.stream().mapToInt(x -> ((Number) x).intValue()).toArray();
                            edges.add(new BotNavigationGraph.Edge(n[0], n[1], BotNavigationGraph.EdgeType.JUMP,
                                    new Point(n[3], n[4]), new Point(n[5], n[6]), n[3], n[3], n[2], 0, 0, 0, 0, n[7]));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Ignoring unreadable learned jumps {}", file, e);
            }
        }
        return new Overlay(version, edges);
    }

    /** For the GM read-out. */
    public static String describe() {
        int edges = 0;
        for (Overlay overlay : overlays.values()) {
            edges += overlay.edges().size();
        }
        return "learned from players: " + edges + " jumps on " + overlays.values().stream()
                .filter(o -> !o.edges().isEmpty()).count() + " maps, " + sightings.size() + " jumps seen once";
    }

    /** Test seam: a scratch directory and map lookup (nulls restore the real ones), and a clean slate. */
    static void useForTest(Path testDir, IntFunction<MapleMap> testMaps) {
        dir = testDir == null ? DIR : testDir;
        maps = testMaps == null ? soloMapling.server.SoloMaplingUtilities::getMapleMapById : testMaps;
        tracks.clear();
        sightings.clear();
        overlays.clear();
        sink = null;
    }
}
