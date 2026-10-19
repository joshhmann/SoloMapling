package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerHeatTest {

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        PlayerHeat.useDirForTest(dir);
    }

    @AfterEach
    void real() {
        PlayerHeat.useDirForTest(null);
    }

    @Test
    void whereAPlayerStandsMostIsTheWarmestAndUntouchedGroundIsCold() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            PlayerHeat.add(100000000, 1250, 270, 1, now);   // the spot by the shop
        }
        PlayerHeat.add(100000000, 300, 270, 1, now);        // walked past once
        assertEquals(1.0, PlayerHeat.warmth(100000000, new Point(1210, 290)), 1e-9);
        assertEquals(0.1, PlayerHeat.warmth(100000000, new Point(330, 250)), 1e-6);
        assertEquals(0.0, PlayerHeat.warmth(100000000, new Point(2000, 270)));
        assertEquals(0.0, PlayerHeat.warmth(104000000, new Point(1250, 270)), "heat belongs to its map");
    }

    @Test
    void oldVisitsFadeAgainstRecentOnes() {
        long now = System.currentTimeMillis();
        long longAgo = now - 3 * PlayerHeat.HALF_LIFE_MS;
        for (int i = 0; i < 4; i++) {
            PlayerHeat.add(100000000, 100, 0, 1, longAgo);  // four visits, three half-lives ago: worth 0.5
        }
        PlayerHeat.add(100000000, 900, 0, 1, now);          // one visit today
        assertEquals(1.0, PlayerHeat.warmth(100000000, new Point(900, 0)), 1e-6);
        assertEquals(0.5, PlayerHeat.warmth(100000000, new Point(100, 0)), 1e-3);
    }

    @Test
    void movementIsSampledOnTheGroundOnlyAndSurvivesARestart() {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1001);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        player.setMap(map);
        PlayerHeat.onPlayerMove(player, 500, 0, 0);    // airborne: not counted
        assertEquals(0.0, PlayerHeat.warmth(100000000, new Point(500, 0)));
        PlayerHeat.onPlayerMove(player, 500, 0, 12);   // on the ground: counted
        PlayerHeat.onPlayerMove(player, 900, 0, 12);   // within the sampling interval: not counted
        assertEquals(1.0, PlayerHeat.warmth(100000000, new Point(500, 0)));
        assertEquals(0.0, PlayerHeat.warmth(100000000, new Point(900, 0)));

        assertEquals(1, PlayerHeat.flush());
        PlayerHeat.useDirForTest(dir);                 // a restart: same files, nothing in memory
        assertTrue(PlayerHeat.warmth(100000000, new Point(500, 0)) > 0.99);
    }
}
