package soloMapling.ArtificialPlayer;

import constants.id.MapId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotResumeTest {

    private final Map<Integer, MapleMap> maps = new HashMap<>();

    @AfterEach
    void restoreRoster() {
        BotRoster.useConnectionSourceForTest(null);
    }

    private MapleMap map(int id, int forcedReturn) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        when(map.getForcedReturnId()).thenReturn(forcedReturn);
        Portal spawn = mock(Portal.class);
        when(spawn.getPosition()).thenReturn(new Point(77, 88));
        when(map.getPortal(0)).thenReturn(spawn);
        maps.put(id, map);
        return map;
    }

    @Test
    void aFieldResumesAtTheExactPointItWasLeftAt() {
        MapleMap antTunnel = map(105050100, MapId.NONE);
        BotResume.Placement placement = BotResume.placementFor(105050100, new Point(-512, 140), maps::get);
        assertSame(antTunnel, placement.map());
        assertEquals(new Point(-512, 140), placement.position());
    }

    @Test
    void aMapWithAForcedReturnSendsTheBotToThatMapsSpawnLikeAPlayer() {
        map(280030000, 211042300);
        MapleMap door = map(211042300, MapId.NONE);
        BotResume.Placement placement = BotResume.placementFor(280030000, new Point(1, 2), maps::get);
        assertSame(door, placement.map());
        assertEquals(new Point(77, 88), placement.position());
    }

    @Test
    void anUnknownOrMissingMapKeepsTheWavesPlacement() {
        assertNull(BotResume.placementFor(0, new Point(), maps::get));
        assertNull(BotResume.placementFor(MapId.NONE, new Point(), maps::get));
        assertNull(BotResume.placementFor(123456789, new Point(), maps::get));
        map(280030000, 999);
        assertNull(BotResume.placementFor(280030000, new Point(), maps::get), "forced return to a missing map");
    }

    @Test
    void activityStateRoundTripsThroughItsColumns() {
        BotRoster.Activity grinding = new BotRoster.Activity("grind", 100000000, Map.of(BotRoster.TRAIN_STATE, 104040000));
        BotRoster.Member member = BotRoster.Member.enrolled(BotStore.planSlot(8, 1, 3), 1L)
                .withSession(grinding, 5_000L);

        assertEquals("grind", member.activity());
        assertEquals(100000000, member.homeMapId());
        assertEquals(5_000L, member.lastLogoutAt());
        BotRoster.Activity back = member.lastActivity();
        assertEquals(104040000, back.intState(BotRoster.TRAIN_STATE, -1));
        assertEquals(100000000, back.homeMapId());
        assertEquals(-1, BotRoster.Activity.of("grind", 0, "{broken").intState(BotRoster.TRAIN_STATE, -1));
        // A periodic save (not a logout) keeps the previous logout time.
        assertEquals(5_000L, member.withSession(grinding, 0).lastLogoutAt());
    }

    @Test
    void loginArmsTheLastActivityOnceAndAFreshMemberResumesWhereItStands() throws Exception {
        int slot = BotStore.planSlot(8, 2, 9);
        BotRoster.useConnectionSourceForTest(() -> {
            java.sql.Connection con = mock(java.sql.Connection.class);
            java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(con.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
            when(ps.executeQuery()).thenReturn(rs);
            return con;
        });
        BotRoster.save(BotRoster.Member.enrolled(slot, 1L));
        int charId = BotStore.BOT_ID_BASE + slot;

        BotRoster.recordLogin(charId, 10L, 100000000, 104040000, new Point(3, 4));
        BotRoster.Activity armed = BotRoster.takeResume(charId);
        assertEquals(100000000, armed.homeMapId(), "home is the wave's town");
        assertEquals(104040000, armed.intState(BotRoster.TRAIN_STATE, -1), "it was training where it stands");
        assertNull(BotRoster.takeResume(charId), "consumed once");
        assertEquals(new BotRoster.LoginPlace(104040000, 3, 4, 10L), BotRoster.loginPlace(charId));
        assertEquals(10L, BotRoster.member(slot).lastLoginAt());
        assertEquals(0, BotRoster.recordSessions(List.of(), 20L, true));
    }
}
