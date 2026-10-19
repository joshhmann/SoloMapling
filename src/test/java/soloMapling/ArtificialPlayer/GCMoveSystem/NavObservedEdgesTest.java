package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** bot-persistence ticket 17. The physics test needs the real wz/ and is skipped without it. */
class NavObservedEdgesTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void scratch() {
        NavObservedEdges.useForTest(dir, null);
    }

    @AfterEach
    void real() {
        NavObservedEdges.useForTest(null, null);
    }

    @Test
    void aJumpIsGroundThenAirThenOtherGroundAndWalkingIsNot() {
        List<NavObservedEdges.Transition> seen = new ArrayList<>();
        NavObservedEdges.sink = seen::add;
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1001);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        player.setMap(map);

        NavObservedEdges.onPlayerMove(player, 100, 200, 5);   // standing on foothold 5
        NavObservedEdges.onPlayerMove(player, 120, 200, 6);   // walked onto foothold 6: not a jump
        NavObservedEdges.onPlayerMove(player, 130, 170, 0);   // took off
        NavObservedEdges.onPlayerMove(player, 160, 120, 0);   // still in the air
        NavObservedEdges.onPlayerMove(player, 190, 90, 9);    // landed on foothold 9: a jump
        NavObservedEdges.onPlayerMove(player, 200, 90, 0);    // hopped ...
        NavObservedEdges.onPlayerMove(player, 205, 90, 9);    // ... and came down on the same foothold: not a jump

        assertEquals(List.of(new NavObservedEdges.Transition(100000000, 6, new Point(120, 200), 9, new Point(190, 90))), seen);
    }

    /** Henesys' baked graph minus one of its own jumps: the jump a player then makes is what the bots learn. */
    @Test
    void aPlayersJumpTheGraphLacksIsProvenByTheBotsPhysicsAndUsedByThePlanner() {
        Assumptions.assumeTrue(Files.isDirectory(Path.of(System.getProperty("wz-path"), "Map.wz")), "needs wz/");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000000);
        BotNavigationGraph full = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertNotNull(full);

        BotNavigationGraph.Edge removed = null;
        for (BotNavigationGraph.Region region : full.regions) {
            for (BotNavigationGraph.Edge edge : full.getOutgoing(region.id)) {
                long sameTarget = full.getOutgoing(region.id).stream().filter(e -> e.toRegionId == edge.toRegionId).count();
                if (edge.type == BotNavigationGraph.EdgeType.JUMP && sameTarget == 1 && edge.launchStepX != 0) {
                    removed = edge;
                    break;
                }
            }
            if (removed != null) {
                break;
            }
        }
        assertNotNull(removed, "Henesys has a directional jump that is the only link between two regions");
        BotNavigationGraph.Edge gone = removed;
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
        for (BotNavigationGraph.Region region : full.regions) {
            List<BotNavigationGraph.Edge> kept = new ArrayList<>(full.getOutgoing(region.id));
            kept.remove(gone);
            outgoing.put(region.id, kept);
        }
        BotNavigationGraph lacking = new BotNavigationGraph(full.mapId, full.version, full.movementProfile, full.regions,
                full.regionsById, full.regionIdByFootholdId, outgoing, full.collidableWallIds, full.collidableFromBelowIds);

        int fromFh = footholdOf(full, gone.fromRegionId);
        int toFh = footholdOf(full, gone.toRegionId);
        Point takeoff = new Point(gone.launchMinX, gone.startPoint.y);
        NavObservedEdges.Transition jump = new NavObservedEdges.Transition(map.getId(), fromFh, takeoff, toFh,
                new Point(gone.endPoint));

        assertFalse(NavObservedEdges.consider(jump, map, lacking), "one sighting is not enough");
        assertTrue(NavObservedEdges.consider(jump, map, lacking), "the second is, once the physics repeats it");
        assertTrue(NavObservedEdges.outgoing(lacking, gone.fromRegionId).stream()
                .anyMatch(e -> e.toRegionId == gone.toRegionId), "the planner now sees the learned jump");

        // A 'jump' the physics cannot make - landing in a region the takeoff cannot reach - is never adopted.
        int farRegion = -1;
        for (BotNavigationGraph.Region region : full.regions) {
            if (region.id != gone.fromRegionId && region.id != gone.toRegionId && !region.segments.isEmpty()
                    && Math.abs(region.segments.get(0).minX - takeoff.x) > 1500) {
                farRegion = region.id;
                break;
            }
        }
        Assumptions.assumeTrue(farRegion >= 0);
        NavObservedEdges.Transition impossible = new NavObservedEdges.Transition(map.getId(), fromFh, takeoff,
                footholdOf(full, farRegion), new Point(takeoff.x + 1600, takeoff.y));
        NavObservedEdges.consider(impossible, map, lacking);
        assertFalse(NavObservedEdges.consider(impossible, map, lacking), "lag or a knockback is not a jump");
    }

    private static int footholdOf(BotNavigationGraph graph, int regionId) {
        for (Map.Entry<Integer, Integer> e : graph.regionIdByFootholdId.entrySet()) {
            if (e.getValue() == regionId) {
                return e.getKey();
            }
        }
        throw new AssertionError("region " + regionId + " has no foothold");
    }
}
