package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotRosterTest {

    @AfterEach
    void restoreRealPool() {
        BotRoster.useConnectionSourceForTest(null);
    }

    /** A pool whose roster query returns these rows, and that records every upsert's bound values. */
    private static List<Map<Integer, Object>> pool(List<Map<String, Object>> rows) throws Exception {
        List<Map<Integer, Object>> upserts = new ArrayList<>();
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        Deque<Map<String, Object>> pending = new ArrayDeque<>(rows);
        Map<String, Object>[] current = new Map[1];
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenAnswer(call -> (current[0] = pending.poll()) != null);
        when(rs.getInt(anyString())).thenAnswer(call -> ((Number) current[0].get(call.getArgument(0))).intValue());
        when(rs.getLong(anyString())).thenAnswer(call -> ((Number) current[0].get(call.getArgument(0))).longValue());
        when(rs.getString(anyString())).thenAnswer(call -> (String) current[0].get(call.getArgument(0)));
        when(select.executeQuery()).thenReturn(rs);
        when(con.prepareStatement(eq(BotRoster.SELECT_ALL))).thenReturn(select);
        when(con.prepareStatement(eq(BotRoster.UPSERT))).thenAnswer(call -> {
            Map<Integer, Object> bound = new HashMap<>();
            PreparedStatement ps = mock(PreparedStatement.class);
            doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setString(anyInt(), any());
            doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
            doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
            when(ps.executeUpdate()).thenAnswer(c -> {
                upserts.add(bound);
                return 1;
            });
            return ps;
        });
        BotRoster.useConnectionSourceForTest(() -> con);
        return upserts;
    }

    private static String any() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static Map<String, Object> row(int botId, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("BotId", botId);
        row.put("Status", status);
        row.put("Personality", "{\"grind\":0.7}");
        row.put("Persona", null);
        row.put("PlayWindowStart", 19 * 60);
        row.put("PlayWindowLen", 180);
        row.put("SessionMinutes", 90);
        row.put("HomeMapId", 100000000);
        row.put("Activity", "grind");
        row.put("ActivityState", null);
        row.put("LastLoginAt", 1_700_000_000_000L);
        row.put("LastLogoutAt", 1_700_000_500_000L);
        row.put("EnrolledAt", 1_690_000_000_000L);
        return row;
    }

    @Test
    void theMigrationEnrolsTheWavesTheRosterOwnsUsingTheStoresOwnSlotLayout() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/tables/026-botroster.sql"));
        assertTrue(sql.contains("BotId >= " + BotStore.FIRST_PLANNED_SLOT), "the migration's FIRST_PLANNED_SLOT");
        assertTrue(sql.contains("(BotId - " + BotStore.FIRST_PLANNED_SLOT + ") / " + BotStore.SLOTS_PER_WAVE),
                "the migration's SLOTS_PER_WAVE");
        assertTrue(sql.contains("IN (8, 9)"));
        assertEquals(java.util.Set.of(8, 9), BotRoster.POPULATION_WAVES);
    }

    @Test
    void rosterSlotsSitAboveTheWholeBootPlanAndAreAdoptedLikePlannedSlots() {
        int lastPlanSlot = BotStore.planSlot(64, BotStore.SLOTS_PER_WAVE / BotStore.SLOTS_PER_TASK - 1,
                BotStore.SLOTS_PER_TASK - 1);
        assertTrue(BotStore.FIRST_ROSTER_SLOT > lastPlanSlot);
        assertTrue(BotStore.isPlanned(BotStore.FIRST_ROSTER_SLOT), "createBot adopts planned slots");
        assertTrue(BotStore.isRosterSlot(BotStore.FIRST_ROSTER_SLOT));
        assertFalse(BotStore.isRosterSlot(lastPlanSlot));
        assertEquals(8, BotStore.waveOf(BotStore.planSlot(8, 3, 17)));
        assertEquals(9, BotStore.waveOf(BotStore.planSlot(9, 0, 0)));
        assertEquals(-1, BotStore.waveOf(BotStore.FIRST_ROSTER_SLOT));
        assertEquals(-1, BotStore.waveOf(BotStore.FIRST_SLOT));
    }

    @Test
    void aMemberIsFoundByItsSlotAloneWithItsStoredRow() throws Exception {
        int trainer = BotStore.planSlot(8, 2, 40);
        int joinedLater = BotStore.FIRST_ROSTER_SLOT + 6;
        pool(List.of(row(trainer, "ACTIVE"), row(joinedLater, "DORMANT")));

        BotRoster.Member member = BotRoster.member(trainer);
        assertEquals(BotRoster.Status.ACTIVE, member.status());
        assertEquals(100000000, member.homeMapId());
        assertEquals("grind", member.activity());
        assertEquals(BotStore.BOT_ID_BASE + trainer, member.charId());
        assertNull(BotRoster.member(BotStore.planSlot(1, 0, 0)), "staff are not members");
        assertEquals(2, BotRoster.members().size());
        assertEquals(List.of(trainer), BotRoster.active().stream().map(BotRoster.Member::botId).toList());
    }

    @Test
    void aNewMemberTakesTheNextSlotAboveEveryRosterSlotInUse() throws Exception {
        pool(List.of(row(BotStore.planSlot(8, 0, 0), "ACTIVE"), row(BotStore.FIRST_ROSTER_SLOT + 41, "ACTIVE")));

        assertEquals(BotStore.FIRST_ROSTER_SLOT + 42, BotRoster.nextNewSlot());
        assertEquals(BotStore.FIRST_ROSTER_SLOT + 43, BotRoster.nextNewSlot());
    }

    @Test
    void anEmptyRosterStartsNewMembersAtTheFirstRosterSlot() throws Exception {
        pool(List.of());
        assertEquals(BotStore.FIRST_ROSTER_SLOT, BotRoster.nextNewSlot());
    }

    @Test
    void enrolWritesAnActiveRowOnceAndSaveWritesEveryColumn() throws Exception {
        List<Map<Integer, Object>> upserts = pool(List.of());
        int slot = BotRoster.nextNewSlot();

        BotRoster.Member enrolled = BotRoster.enroll(slot, 1_750_000_000_000L);
        assertSame(enrolled, BotRoster.enroll(slot, 1_760_000_000_000L), "a second enrol is a no-op");
        assertEquals(1, upserts.size());
        assertEquals(slot, upserts.get(0).get(1));
        assertEquals("ACTIVE", upserts.get(0).get(2));
        assertEquals(1_750_000_000_000L, upserts.get(0).get(13));

        BotRoster.save(enrolled.withStatus(BotRoster.Status.DORMANT));
        assertEquals("DORMANT", upserts.get(1).get(2));
        assertEquals(13, upserts.get(1).size(), "every column is bound");
        assertEquals(BotRoster.Status.DORMANT, BotRoster.member(slot).status());
    }
}
