package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NavEdgeLearningTest {

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir);
    }

    @AfterEach
    void real() {
        NavEdgeLearning.useDirForTest(null);
    }

    private static BotNavigationGraph.Edge jump(int from, int to, int cost) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.JUMP, new Point(from * 100, 50),
                new Point(to * 100, 50), from * 100, from * 100, 0, 0, 0, 0, 0, cost);
    }

    /** Three rope regions side by side: 0 -> 2 directly (cheapest), or 0 -> 1 -> 2. */
    private static BotNavigationGraph threeRopes(int version, BotNavigationGraph.Edge direct,
                                                 BotNavigationGraph.Edge first, BotNavigationGraph.Edge second) {
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(0, 0, 0, 100, false),
                new BotNavigationGraph.Region(1, 100, 0, 100, false), new BotNavigationGraph.Region(2, 200, 0, 100, false));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        Map<Integer, List<BotNavigationGraph.Edge>> out = new HashMap<>();
        out.put(0, List.of(direct, first));
        out.put(1, List.of(second));
        out.put(2, List.of());
        return new BotNavigationGraph(990000000, version, BotMovementProfile.base(), regions, byId, Map.of(), out, Set.of());
    }

    @Test
    void anEdgeThatKeepsFailingCostsMoreAndOneThatAlmostAlwaysFailsCostsMuchMore() {
        assertEquals(1.0, NavEdgeLearning.factorFor(0, 10));
        assertEquals(1.0, NavEdgeLearning.factorFor(0.2, 0), "a fraction of one old failure is nothing");
        double mixed = NavEdgeLearning.factorFor(2, 2);
        assertTrue(mixed > 1.5 && mixed < 3.0, "half failing: " + mixed);
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factorFor(6, 1));
        assertTrue(NavEdgeLearning.factorFor(1, 0) < NavEdgeLearning.factorFor(3, 0), "confidence grows with evidence");
    }

    @Test
    void failuresFadeWithTime() {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph graph = threeRopes(61, direct, jump(0, 1, 60), jump(1, 2, 60));
        long longAgo = System.currentTimeMillis() - 4 * NavEdgeLearning.HALF_LIFE_MS;
        for (int i = 0; i < 6; i++) {
            NavEdgeLearning.record(graph, direct, true, longAgo);
        }
        // Six failures, four half-lives ago, are worth less than one today.
        assertTrue(NavEdgeLearning.factor(graph, direct) < 1.5, "faded: " + NavEdgeLearning.factor(graph, direct));
    }

    @Test
    void thePlannerRoutesAroundAJumpThatKeepsFailing() {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph.Edge first = jump(0, 1, 60);
        BotNavigationGraph.Edge second = jump(1, 2, 60);
        BotNavigationGraph graph = threeRopes(61, direct, first, second);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(990000000);

        List<BotNavigationGraph.Edge> before = BotNavigationManager.runSearch(graph, map, new Point(0, 50), 0, 2,
                new Point(200, 50), null, true, false, 0L).path();
        assertEquals(List.of(direct), before, "the direct jump is cheapest at first");

        for (int i = 0; i < 6; i++) {
            NavEdgeLearning.failure(graph, direct);
        }
        List<BotNavigationGraph.Edge> after = BotNavigationManager.runSearch(graph, map, new Point(0, 50), 0, 2,
                new Point(200, 50), null, true, false, 0L).path();
        assertEquals(List.of(first, second), after, "after six misses the bots take the long way");
    }

    @Test
    void whatWasLearnedSurvivesARestartButNotANewGraphVersion() {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph v61 = threeRopes(61, direct, jump(0, 1, 60), jump(1, 2, 60));
        for (int i = 0; i < 3; i++) {
            NavEdgeLearning.failure(v61, direct);
        }
        double learned = NavEdgeLearning.factor(v61, direct);
        assertEquals(1, NavEdgeLearning.flush());

        NavEdgeLearning.useDirForTest(dir); // a restart: nothing in memory, same files
        BotNavigationGraph.Edge sameEdge = jump(0, 2, 100);
        BotNavigationGraph reloaded = threeRopes(61, sameEdge, jump(0, 1, 60), jump(1, 2, 60));
        assertEquals(learned, NavEdgeLearning.factor(reloaded, sameEdge), 0.05);

        NavEdgeLearning.useDirForTest(dir);
        BotNavigationGraph v62 = threeRopes(62, sameEdge, jump(0, 1, 60), jump(1, 2, 60));
        assertEquals(1.0, NavEdgeLearning.factor(v62, sameEdge), "a rebuilt graph starts fresh");
    }
}
