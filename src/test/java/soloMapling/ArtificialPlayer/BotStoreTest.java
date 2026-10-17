package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import client.SkinColor;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import server.Trade;
import server.maps.MapleMap;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The durable half of bot state: which ids the store hands out, what a stored bot is restored to, and what
 * the sweeps are allowed to touch. The SQL itself and the item round-trip are proved against a real
 * database - these tests pin the decisions a mistake would be silent about.
 */
class BotStoreTest {

    private static final int BOT_ID = 21077;
    private static final int PLAYER_ID = 777;
    private static final int STORED_BOT_ID = 21042;

    private static Character newBot(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setPosition(new Point(300, 400));
        return chr;
    }

    private static BotStore.Persisted row(int botId) {
        // The four capacities are deliberately distinct: they are a mapping, and a swap between two tabs
        // would be invisible if every tab carried the same number.
        return new BotStore.Persisted(botId, "Recovered", BotTier.B.name(), 57, 4242, 910_000,
                Job.getById(420).getId(), SkinColor.getById(3).getId(), 1, 30_040, 20_010,
                100_000_002, 512, 640, 97, 98, 99, 100, 1_700_000_000_000L, 1_700_000_001_000L);
    }

    /** The value each mutable column of {@code row} must be written from / read into, by column name. */
    private static Object valueOf(String column, BotStore.Persisted row) {
        switch (column) {
            case "BotId":
                return row.botId;
            case "Name":
                return row.name;
            case "Tier":
                return row.tier;
            case "Level":
                return row.level;
            case "Exp":
                return row.exp;
            case "Meso":
                return row.meso;
            case "Job":
                return row.job;
            case "Skin":
                return row.skin;
            case "Gender":
                return row.gender;
            case "Hair":
                return row.hair;
            case "Face":
                return row.face;
            case "MapId":
                return row.mapId;
            case "PosX":
                return row.posX;
            case "PosY":
                return row.posY;
            case "EquipSlots":
                return row.equipSlots;
            case "UseSlots":
                return row.useSlots;
            case "SetupSlots":
                return row.setupSlots;
            case "EtcSlots":
                return row.etcSlots;
            case "CreatedAt":
                return row.createdAt;
            case "SavedAt":
                return row.savedAt;
            default:
                throw new IllegalArgumentException("the store has no field for column " + column);
        }
    }

    private static List<String> insertColumns() {
        String sql = BotStore.INSERT_ROW;
        return Arrays.stream(sql.substring(sql.indexOf('(') + 1, sql.indexOf(')')).split(","))
                .map(String::trim).toList();
    }

    private static List<String> updateColumns() {
        String sql = BotStore.UPDATE_ROW;
        String assignments = sql.substring(sql.indexOf("SET ") + 4, sql.indexOf(" WHERE"));
        return Arrays.stream(assignments.split(","))
                .map(assignment -> assignment.substring(0, assignment.indexOf(" =")).trim()).toList();
    }

    /** A statement that records what was written at which index. */
    private static PreparedStatement recordingInto(Map<Integer, Object> bound) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(call -> bound.put(call.getArgument(0), call.getArgument(1))).when(ps).setString(anyInt(), anyString());
        doAnswer(call -> bound.put(call.getArgument(0), call.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        doAnswer(call -> bound.put(call.getArgument(0), call.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
        return ps;
    }

    @Test
    void everyColumnIsReadIntoTheFieldItNames() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("BotId")).thenReturn(100);
        when(rs.getString("Name")).thenReturn("Recovered");
        when(rs.getString("Tier")).thenReturn("A");
        when(rs.getInt("Level")).thenReturn(57);
        when(rs.getInt("Exp")).thenReturn(4242);
        when(rs.getInt("Meso")).thenReturn(910_000);
        when(rs.getInt("Job")).thenReturn(420);
        when(rs.getInt("Skin")).thenReturn(3);
        when(rs.getInt("Gender")).thenReturn(1);
        when(rs.getInt("Hair")).thenReturn(30_040);
        when(rs.getInt("Face")).thenReturn(20_010);
        when(rs.getInt("MapId")).thenReturn(100_000_002);
        when(rs.getInt("PosX")).thenReturn(512);
        when(rs.getInt("PosY")).thenReturn(640);
        when(rs.getInt("EquipSlots")).thenReturn(97);
        when(rs.getInt("UseSlots")).thenReturn(98);
        when(rs.getInt("SetupSlots")).thenReturn(99);
        when(rs.getInt("EtcSlots")).thenReturn(100);
        when(rs.getLong("CreatedAt")).thenReturn(1_700_000_000_000L);
        when(rs.getLong("SavedAt")).thenReturn(1_700_000_001_000L);

        BotStore.Persisted read = BotStore.read(rs);

        assertAll("a stored row is one bot, so a column read into the wrong field is a corrupted bot",
                () -> assertEquals(100, read.botId),
                () -> assertEquals("Recovered", read.name),
                () -> assertEquals("A", read.tier),
                () -> assertEquals(57, read.level),
                () -> assertEquals(4242, read.exp),
                () -> assertEquals(910_000, read.meso),
                () -> assertEquals(420, read.job),
                () -> assertEquals(3, read.skin),
                () -> assertEquals(1, read.gender),
                () -> assertEquals(30_040, read.hair),
                () -> assertEquals(20_010, read.face),
                () -> assertEquals(100_000_002, read.mapId),
                () -> assertEquals(512, read.posX),
                () -> assertEquals(640, read.posY),
                () -> assertEquals(97, read.equipSlots),
                () -> assertEquals(98, read.useSlots),
                () -> assertEquals(99, read.setupSlots),
                () -> assertEquals(100, read.etcSlots),
                () -> assertEquals(1_700_000_000_000L, read.createdAt),
                () -> assertEquals(1_700_000_001_000L, read.savedAt));
    }

    @Test
    void theStatementsWriteEachColumnFromTheFieldItNames() throws SQLException {
        BotStore.Persisted row = new BotStore.Persisted(100, "Bound", "A", 57, 4242, 910_000,
                Job.getById(420).getId(), 3, 1, 30_040, 20_010, 100_000_002, 512, 640, 97, 98, 99, 100,
                1_700_000_000_000L, 1_700_000_001_000L);
        List<String> insert = insertColumns();
        List<String> update = updateColumns();

        Map<Integer, Object> bound = new TreeMap<>();
        BotStore.bindMutable(recordingInto(bound), row, 1);
        List<String> writtenByUpdate = mismatches(update, bound, 1, row);

        bound.clear();
        BotStore.bindMutable(recordingInto(bound), row, 2);
        List<String> writtenByInsert = mismatches(insert.subList(1, 19), bound, 2, row);

        assertAll(
                () -> assertEquals(20, insert.size(), "the insert names the key, the 18 mutable columns and the creation stamp"),
                () -> assertEquals(18, update.size(), "the update rewrites the mutable columns only"),
                () -> assertEquals(List.of(), writtenByUpdate, "UPDATE matches its own column list"),
                () -> assertEquals(List.of(), writtenByInsert, "INSERT matches its own column list"));
    }

    /** Column/field pairs whose bound value does not come from the field the column names. */
    private static List<String> mismatches(List<String> columns, Map<Integer, Object> bound, int first,
                                           BotStore.Persisted row) {
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            Object expected = valueOf(column, row);
            Object actual = bound.get(first + i);
            if (!expected.equals(actual)) {
                wrong.add(column + ": expected " + expected + " but wrote " + actual);
            }
        }
        return wrong;
    }

    @Test
    void aBotsKeyComesFromItsPlaceInTheSpawnPlanNotFromArrivalOrder() {
        int firstTask = BotStore.planSlot(1, 0, 0);
        int lastOfTask = BotStore.planSlot(1, 0, BotStore.SLOTS_PER_TASK - 1);

        assertAll(
                () -> assertEquals(firstTask, BotStore.planSlot(1, 0, 0),
                        "the same plan position is the same slot on every boot, whatever order the cohorts finish in"),
                () -> assertEquals(firstTask + 1, BotStore.planSlot(1, 0, 1),
                        "the next bot of the same task is the next slot"),
                () -> assertEquals(lastOfTask + 1, BotStore.planSlot(1, 1, 0),
                        "a task's slots end before the next task's begin, so a fatter task cannot push its "
                                + "neighbour's bots onto other bots' stored rows"),
                () -> assertTrue(BotStore.planSlot(2, 0, 0) > BotStore.planSlot(1, BotStore.SLOTS_PER_WAVE / BotStore.SLOTS_PER_TASK - 1, BotStore.SLOTS_PER_TASK - 1),
                        "a later wave starts beyond every slot of the earlier ones"),
                () -> assertThrows(IllegalArgumentException.class, () -> BotStore.planSlot(1, 0, BotStore.SLOTS_PER_TASK),
                        "a task cannot hold more bots than it owns slots"),
                () -> assertThrows(IllegalArgumentException.class, () -> BotStore.planSlot(0, 0, 0),
                        "there is no wave zero"));
    }

    @Test
    void handSpawnedBotsDrawSlotsTheSpawnPlanNeverUses() {
        int first = BotStore.nextDynamicSlot();
        int second = BotStore.nextDynamicSlot();
        int run = BotStore.nextDynamicSlots(3);

        assertAll(
                () -> assertEquals(first + 1, second, "on-demand slots are handed out one at a time"),
                () -> assertEquals(second + 1, run),
                () -> assertFalse(BotStore.isPlanned(first),
                        "a hand-spawned bot has no plan position, so it is minted fresh instead of "
                                + "adopting whatever the previous run left in that slot"),
                () -> assertTrue(BotStore.isPlanned(BotStore.planSlot(1, 0, 0))));
    }

    @Test
    void theStoreOnlyAnswersForSlotsItCouldHaveCreated() {
        assertNull(BotStore.persistedFor(PLAYER_ID), "a player id is not a bot slot");
        assertNull(BotStore.persistedFor(BotStore.FIRST_BOT_ID - 1), "nor is anything below the first slot");
    }

    @Test
    void firstCreationGrantsAreExplicitRatherThanInherited() {
        Character bot = newBot(BOT_ID, "Minted");
        bot.setExp(500);
        bot.gainMeso(1_200, false, false, false);

        BotStore.applyInitialGrants(bot);

        assertAll(
                () -> assertEquals(BotStore.INITIAL_EXP, bot.getExp(), "a new bot starts with no experience of its own"),
                () -> assertEquals(BotStore.INITIAL_MESO, bot.getMeso(), "nor a fortune inherited from the template row"));
    }

    @Test
    void aStoredBotIsRestoredWithItsIdentityProgressionLookAndCapacity() {
        Character bot = newBot(BOT_ID, "Template");
        bot.setLevel(1);
        bot.setJob(Job.getById(0));

        BotStore.Persisted stored = row(STORED_BOT_ID);
        stored.applyTo(bot);

        assertAll(
                () -> assertEquals(BotStore.BOT_ID_BASE + STORED_BOT_ID, bot.getId(),
                        "the store key is the bot's character id, so its holdings are found again"),
                () -> assertEquals(stored.charId(), bot.getId()),
                () -> assertEquals("Recovered", bot.getName()),
                () -> assertEquals(57, bot.getLevel()),
                () -> assertEquals(4242, bot.getExp()),
                () -> assertEquals(910_000, bot.getMeso()),
                () -> assertEquals(420, bot.getJob().getId()),
                () -> assertEquals(BotTier.B, bot.getTier()),
                () -> assertEquals(3, bot.getSkinColor().getId()),
                () -> assertEquals(1, bot.getGender()),
                () -> assertEquals(30_040, bot.getHair()),
                () -> assertEquals(20_010, bot.getFace()),
                () -> assertEquals(97, bot.getInventory(InventoryType.EQUIP).getSlotLimit()),
                () -> assertEquals(98, bot.getInventory(InventoryType.USE).getSlotLimit()),
                () -> assertEquals(99, bot.getInventory(InventoryType.SETUP).getSlotLimit()),
                () -> assertEquals(100, bot.getInventory(InventoryType.ETC).getSlotLimit(),
                        "each tab keeps the capacity the row stored for that tab, not its neighbour's"));
    }

    @Test
    void adoptingTheSameSlotAgainReproducesTheSameBot() {
        BotStore.Persisted stored = row(STORED_BOT_ID);

        Character first = newBot(0, "First");
        stored.applyTo(first);

        Character second = newBot(0, "Second");
        second.setLevel(9);
        second.gainMeso(77, false, false, false);
        stored.applyTo(second);

        assertAll(
                () -> assertEquals(first.getId(), second.getId(), "the same slot is always the same bot"),
                () -> assertEquals(first.getName(), second.getName()),
                () -> assertEquals(first.getLevel(), second.getLevel()),
                () -> assertEquals(first.getExp(), second.getExp()),
                () -> assertEquals(first.getMeso(), second.getMeso()),
                () -> assertEquals(first.getJob().getId(), second.getJob().getId()),
                () -> assertEquals(first.getTier(), second.getTier()),
                () -> assertEquals(first.getSkinColor().getId(), second.getSkinColor().getId()),
                () -> assertEquals(first.getHair(), second.getHair()),
                () -> assertEquals(first.getFace(), second.getFace()));
    }

    @Test
    void aBotThatTookItsRowFromTheStoreIsMarkedAsAdopted() throws SQLException {
        // Callers that would roll fresh state onto a whole cohort (the OPQ lobby's 50-70 level band) ask
        // the store which bots came back from an earlier run, so an adopted bot keeps its progression.
        int slot = 21099;
        Connection con = mockPool(accepting());
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.recordFirstCreation(newBot(slot, "Stored"));

            Character again = newBot(slot, "Placeholder");
            Character minted = newBot(slot + 1, "Placeholder");

            assertAll(
                    () -> assertTrue(BotStore.restoreIdentity(again), "this slot has a row now"),
                    () -> assertTrue(BotStore.isAdoptedInThisRun(again.getId()),
                            "an adopted bot keeps what the store gave it, so nothing may re-roll it"),
                    () -> assertFalse(BotStore.restoreIdentity(minted), "this slot was never filled"),
                    () -> assertFalse(BotStore.isAdoptedInThisRun(minted.getId()),
                            "only a bot minted this run may be given fresh state"));
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }
    }

    @Test
    void aBotWhoseHoldingsCannotBeReadIsSettledRatherThanSilentlyDropped() throws SQLException {
        Character bot = newBot(BOT_ID, "Unreadable");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.grantMeso(bot, 3_000);

        // The row write itself succeeds; only the bot's items are unreadable. Writing the empty tabs the
        // bot is carrying would wipe the stored ones, so the write must fail - and a failed write is what
        // hands the ledger back to the map instead of dropping it.
        Connection con = mockPool(accepting());
        BotStore.markHoldingsUnreadableForTest(BOT_ID, true);
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.saveThenSettle(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
            BotStore.markHoldingsUnreadableForTest(BOT_ID, false);
        }

        verify(map).spawnMesoDrop(3_000, bot.getPosition(), bot, bot, true, (byte) 2, (short) 0);
    }

    @Test
    void durableHoldingsAreNotHandedToTheWorldAsWell() {
        Character bot = newBot(BOT_ID, "Durable");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.grantMeso(bot, 5_000);

        BotHoldings.discard(bot);
        BotHoldings.settle(bot);

        verify(map, never()).spawnMesoDrop(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyByte(), org.mockito.ArgumentMatchers.anyShort());
    }

    @Test
    void theSweepsLeavePlayerCharactersAlone() {
        Character player = newBot(PLAYER_ID, "Player");

        assertAll(
                () -> assertEquals(0, BotStore.saveAll(List.of(player)), "a player is saved by the character path, not the bot store"),
                () -> assertEquals(0, BotStore.settleAndSaveAll(List.of(player))),
                () -> assertEquals(0, BotStore.saveAll(java.util.Collections.singletonList(null))));
    }

    @Test
    void adoptionPutsTheBotBackWhereItWasLastSeenOnItsOwnMap() {
        Character bot = newBot(BOT_ID, "Placed");
        bot.setPosition(new Point(10, 10));

        MapleMap home = mock(MapleMap.class);
        when(home.getId()).thenReturn(100_000_002);

        BotStore.Persisted stored = row(STORED_BOT_ID);
        stored.restorePlacement(bot, home);

        assertAll(
                () -> assertEquals(new Point(stored.posX, stored.posY), bot.getPosition(),
                        "an adopted bot stands where it was last seen, not at the wave's spawn point"),
                () -> assertEquals(new Point(512, 640), bot.getPosition()));
    }

    @Test
    void aBotWhoseHomeMapChangedKeepsTheSpawnPoint() {
        Character bot = newBot(BOT_ID, "Placed");
        bot.setPosition(new Point(10, 10));

        MapleMap elsewhere = mock(MapleMap.class);
        when(elsewhere.getId()).thenReturn(999_999);

        BotStore.Persisted stored = row(STORED_BOT_ID);
        stored.restorePlacement(bot, elsewhere);
        stored.restorePlacement(bot, null);

        assertEquals(new Point(10, 10), bot.getPosition(),
                "a stored point belongs to the map it was taken on, and a missing map is not a reason to move the bot");
    }

    @Test
    void aBotThatCannotBeSavedIsStillSettledOntoItsMap() {
        Character bot = newBot(BOT_ID, "Homeless");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.grantMeso(bot, 4_000);

        // This suite has no database, which is the same shape as an unreachable store in the world: the
        // bot's value must still land on the map it is leaving instead of being destroyed.
        BotStore.saveThenSettle(bot);

        verify(map).spawnMesoDrop(4_000, bot.getPosition(), bot, bot, true, (byte) 2, (short) 0);
    }

    @Test
    void anOpenWindowIsClosedThroughTheTradesOwnPath() {
        Character bot = newBot(BOT_ID, "Trader");
        bot.setTrade(new Trade((byte) 1, bot));

        BotStore.closeOpenWindow(bot);

        assertAll(
                () -> assertNull(bot.getTrade(), "an open window at shutdown is closed, which refunds both sides"),
                () -> assertNull(bot.getTrade()));
    }

    @Test
    void closingAWindowThatIsNotOpenIsHarmless() {
        BotStore.closeOpenWindow(newBot(BOT_ID, "Idle"));
    }

    @Test
    void aDeadlockedFirstCreationIsRetriedInsteadOfLosingTheBot() throws SQLException {
        Character bot = newBot(BOT_ID, "Contended");
        Connection con = mockPool(deadlocked(), accepting());
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.recordFirstCreation(bot);

            assertAll(
                    () -> verify(con, times(2)).setAutoCommit(false),
                    () -> verify(con).rollback(),
                    () -> verify(con).commit());
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }
    }

    @Test
    void aWriteThatKeepsDeadlockingIsGivenUpOn() throws SQLException {
        Character bot = newBot(BOT_ID, "Hopeless");
        Connection con = mockPool(deadlocked(), deadlocked(), deadlocked(), deadlocked());
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            SQLException thrown = assertThrows(SQLException.class, () -> BotStore.recordFirstCreation(bot));

            assertAll(
                    () -> assertEquals(1213, thrown.getErrorCode(), "the deadlock is what the caller sees"),
                    () -> verify(con, times(4)).rollback(),
                    () -> verify(con, never()).commit());
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }
    }

    @Test
    void aRealErrorIsNotRetried() throws SQLException {
        Character bot = newBot(BOT_ID, "Broken");
        Connection con = mockPool(refusingRow(1062));
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            SQLException thrown = assertThrows(SQLException.class, () -> BotStore.recordFirstCreation(bot));

            assertAll(
                    () -> assertEquals(1062, thrown.getErrorCode()),
                    () -> verify(con).rollback(),
                    () -> verify(con, never()).commit());
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }
    }

    @Test
    void onlyTheCollisionsTwoWritersProduceCountAsContention() {
        assertAll(
                () -> assertTrue(BotStore.isContention(refusing(1213)), "a deadlock"),
                () -> assertTrue(BotStore.isContention(refusing(1205)), "a lock wait timeout"),
                () -> assertTrue(BotStore.isContention(withState("40001")), "a serialization failure"),
                () -> assertFalse(BotStore.isContention(refusing(1062)), "a duplicate key is ours to fix"),
                () -> assertFalse(BotStore.isContention(refusing(1146)), "a missing table is ours to fix"));
    }

    /** A SQLException carrying an error code, the way the driver reports it. */
    private static SQLException refusing(int errorCode) {
        return new SQLException("refused", "HY000", errorCode);
    }

    private static SQLException withState(String sqlState) {
        return new SQLException("contended", sqlState, 0);
    }

    /** A statement whose row write deadlocks with MySQL's deadlock code. */
    private static PreparedStatement deadlocked() throws SQLException {
        return refusingRow(1213);
    }

    /** A statement whose row write fails with the given error code, as the driver would report it. */
    private static PreparedStatement refusingRow(int errorCode) throws SQLException {
        return counting(statement -> {
            throw refusing(errorCode);
        });
    }

    private static PreparedStatement accepting() throws SQLException {
        return counting(statement -> 1);
    }

    private static PreparedStatement counting(RowWrite write) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(call -> write.apply(ps)).when(ps).executeUpdate();
        return ps;
    }

    /** A pool whose row inserts misbehave in the given order; every other statement is a no-op. */
    private static Connection mockPool(PreparedStatement... rows) throws SQLException {
        Connection con = mock(Connection.class);
        Deque<PreparedStatement> queue = new ArrayDeque<>(Arrays.asList(rows));
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        // Declared last on purpose: with two matchers that both match, the last stubbing wins.
        when(con.prepareStatement(BotStore.INSERT_ROW))
                .thenAnswer(call -> queue.size() > 1 ? queue.poll() : queue.peek());
        return con;
    }

    /** A statement that writes nothing and reads nothing. */
    private static PreparedStatement passive() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        when(keys.next()).thenReturn(false);
        when(ps.getGeneratedKeys()).thenReturn(keys);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(false);
        when(ps.executeQuery()).thenReturn(rows);
        return ps;
    }

    @FunctionalInterface
    private interface RowWrite {
        int apply(PreparedStatement statement) throws SQLException;
    }
}
