package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import client.SkinColor;
import client.inventory.Inventory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.Trade;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void aDebitIsWrittenInsteadOfBeingLeftForARestartToRestore() throws SQLException {
        Character bot = newBot(BOT_ID, "Indebted");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        // What this bot holds it got before this run: the mesos never came through the ledger, so the ledger
        // still measures everything it gives away from here on as a debit against its stored row.
        bot.gainMeso(910_000, false, false, false);
        assertTrue(BotHoldings.spendMeso(bot, 3_000), "a stored bot spends out of what its row gave it");
        assertTrue(BotHoldings.hasUnpersistedDebit(bot), "its row still claims the mesos it spent");

        // The bot's stored holdings cannot be read at all, which makes an ordinary write fail. That is the
        // right answer for a bot that merely earned - the test above settles those onto its map - but a bot
        // that gave value away is the other direction: forgetting the debit hands it back at the next boot
        // while the other side still holds it. The write is what has to happen, so it goes through here.
        Map<Integer, Object> durable = new HashMap<>();   // what a restart would read back
        Connection con = committingPool(durable);
        BotStore.markHoldingsUnreadableForTest(BOT_ID, true);
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.saveThenSettle(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
            BotStore.markHoldingsUnreadableForTest(BOT_ID, false);
        }

        assertAll(
                () -> assertEquals(907_000, durable.get(updateColumns().indexOf("Meso") + 1),
                        "the row a restart reads has to carry the debit, not the mesos the bot gave away"),
                () -> assertFalse(BotHoldings.hasUnpersistedDebit(bot),
                        "the write records it, so there is nothing left to forget"),
                () -> verify(map, never()).spawnMesoDrop(anyInt(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyByte(),
                        org.mockito.ArgumentMatchers.anyShort()));
    }

    @Test
    void aDebitThatWillNotWriteTakesTheStoredClaimDownWithIt() throws SQLException {
        Character bot = newBot(BOT_ID, "Chips");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        int chip = 2_000_000;
        bot.getInventory(InventoryType.USE).addItem(new Item(chip, (short) 0, (short) 10, -1));
        when(map.spawnItemDropNoExpire(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(mock(MapItem.class));

        assertEquals(3, BotHoldings.payOut(bot, chip, 3, newBot(PLAYER_ID, "Winner"), new Point(30, 40)));
        assertTrue(BotHoldings.hasUnpersistedDebit(bot), "the chips came out of stacks its row still claims");

        // A store that will not take the write at all, on the write or on the retry, while the item tables
        // still answer. The stored claim cannot be corrected, so it is taken down: restoring it at the next
        // boot would hand the bot three chips on top of the three the winner is holding. The bot's own items
        // go with the claim, which is the only direction to lose in - a value minted this way has no later
        // write that could take it back.
        Map<Integer, Object> cleared = new HashMap<>();
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        PreparedStatement wipe = recordingInto(cleared);
        when(con.prepareStatement(org.mockito.ArgumentMatchers.contains("DELETE `inventoryitems`")))
                .thenReturn(wipe);
        PreparedStatement refusing = deadlocked();
        when(con.prepareStatement(BotStore.INSERT_ROW)).thenReturn(refusing);
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.saveThenSettle(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> verify(con).commit(),
                () -> assertEquals(bot.getId(), cleared.get(2),
                        "the stored claim that was taken down is this bot's own"),
                () -> assertFalse(BotHoldings.hasUnpersistedDebit(bot),
                        "the store holds a record of the debit now, so nothing may settle or restore it later"));
    }

    @Test
    void aSnapshotDescribesTheItemsAsTheyWereRatherThanAsTheyAre() throws SQLException {
        Character bot = newBot(BOT_ID, "House");
        int chip = 2_000_000;
        Item chips = new Item(chip, (short) 0, (short) 4, -1);
        bot.getInventory(InventoryType.USE).addItem(chips);
        short slot = chips.getPosition();

        // A payout empties part of the stack while the sweep is writing it. ItemFactory binds each item's
        // state when its statement runs, after the tab's lock is long gone, so a snapshot that keeps the item
        // itself writes down what the item has since become - a row claiming chips the house handed to a
        // winner, which the next boot restores to the house as a second copy. The row has to carry the state
        // the chips were in at the moment the snapshot was taken; the change is the ledger's business.
        Map<Integer, Object> boundItems = new HashMap<>();
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        PreparedStatement statement = passive();
        doAnswer(call -> {
            // the race: the stack changes after the snapshot was taken but before the item rows are bound,
            // which is the window the delete of the old rows leaves open
            chips.setQuantity((short) 1);
            return 0;
        }).when(statement).executeUpdate();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        PreparedStatement row = accepting();
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(row);
        PreparedStatement itemStatement = recordingInto(boundItems);
        when(con.prepareStatement(org.mockito.ArgumentMatchers.contains("INSERT INTO `inventoryitems`"),
                anyInt())).thenReturn(itemStatement);
        when(con.prepareStatement(org.mockito.ArgumentMatchers.contains("DELETE `inventoryitems`")))
                .thenReturn(statement);
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.save(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(chip, boundItems.get(4), "the row has to carry the item the snapshot saw"),
                () -> assertEquals((int) slot, boundItems.get(6), "in the slot it was in"),
                () -> assertEquals(4, boundItems.get(7),
                        "with the quantity it had, not what the payout left behind"));
    }

    @Test
    void theStoredEquipmentKeepsTheRingIdTheInsertBinds() throws SQLException {
        Character bot = newBot(BOT_ID, "Ringed");
        // An Equip cannot be built in this run - its constructor asks the item data for the equip level - so
        // the held item and the copy the snapshot takes are both doubles. What the test pins is that the ring
        // id crosses from one to the other: the equipment insert binds it, and it is not in the item insert.
        Equip held = mock(Equip.class);
        Equip copied = mock(Equip.class);
        when(held.copy()).thenReturn(copied);
        when(held.getRingId()).thenReturn(40_000_000);
        int[] crossed = {-1};
        doAnswer(call -> {
            crossed[0] = call.getArgument(0);
            return null;
        }).when(copied).setRingId(anyInt());
        when(copied.getRingId()).thenAnswer(call -> crossed[0]);
        bot.getInventory(InventoryType.EQUIP).addItem(held);
        Map<Integer, Object> boundRing = new HashMap<>();
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        PreparedStatement rowStatement = accepting();
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(rowStatement);
        ResultSet keys = mock(ResultSet.class);
        when(keys.next()).thenReturn(true);
        when(keys.getInt(1)).thenReturn(4_242);
        PreparedStatement itemStatement = passive();
        when(itemStatement.getGeneratedKeys()).thenReturn(keys);
        when(con.prepareStatement(org.mockito.ArgumentMatchers.contains("INSERT INTO `inventoryitems`"),
                anyInt())).thenReturn(itemStatement);
        PreparedStatement equipment = recordingInto(boundRing);
        when(con.prepareStatement(org.mockito.ArgumentMatchers.contains("INSERT INTO `inventoryequipment`")))
                .thenReturn(equipment);
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.save(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(40_000_000, boundRing.get(23),
                        "the row a restart reads has to keep the ring the instance was wearing"),
                () -> verify(copied).setRingId(40_000_000));
    }

    @Test
    void aChangeTheRowMissedKeepsItsDeltasInsteadOfBeingForgotten() throws SQLException {
        Character bot = newBot(BOT_ID, "Racer");
        int chip = 2_000_000;
        Item chips = new Item(chip, (short) 0, (short) 10, -1);
        bot.getInventory(InventoryType.USE).addItem(chips);

        // The write names the state of the bot when it was captured, so a change that lands after that point
        // is not in the row: it lives in the ledger, which is the bot's holdings minus what the row claims.
        // Dropping the ledger here - the way a plain "the write went through" does - loses the only record of
        // it, and the next boot restores the ten chips the bot has already given away.
        Map<Integer, Object> durable = new HashMap<>();
        Map<Integer, Object> pending = new HashMap<>();
        Map<Integer, Object> boundedItems = new HashMap<>();
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        PreparedStatement row = recordingInto(pending);
        when(row.executeUpdate()).thenReturn(1);
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(row);
        PreparedStatement itemStatement = recordingInto(boundedItems);
        doAnswer(call -> {
            // the chips are destroyed while the sweep is writing the row that still describes them
            assertTrue(BotHoldings.destroyHeldItem(bot, chips), "the bot holds the instance being destroyed");
            return 0;
        }).when(itemStatement).executeUpdate();
        when(con.prepareStatement(org.mockito.ArgumentMatchers.contains("INSERT INTO `inventoryitems`"),
                anyInt())).thenReturn(itemStatement);
        doAnswer(call -> {
            durable.putAll(pending);
            pending.clear();
            return null;
        }).when(con).commit();
        doAnswer(call -> {
            pending.clear();
            return null;
        }).when(con).rollback();

        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.saveAll(List.of(bot));
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(10, boundedItems.get(7),
                        "the write describes the moment it captured, so it is one change behind"),
                () -> assertTrue(durable.get(updateColumns().indexOf("Meso") + 1) != null,
                        "the row itself was written"),
                () -> assertTrue(BotHoldings.hasUnpersistedDebit(bot),
                        "the change the row missed is a debit the next write still has to carry"));
    }

    @Test
    void theLastWriteOfTheProcessRetriesADebitItCouldNotWriteAtFirst() throws SQLException {
        Character bot = newBot(BOT_ID, "Closing");
        bot.gainMeso(910_000, false, false, false);
        assertTrue(BotHoldings.spendMeso(bot, 3_000), "it spends out of what its row gave it");
        assertTrue(BotHoldings.hasUnpersistedDebit(bot));

        // Shutdown is the process's last chance to write: the bot's despawn path is not the only place a debit
        // can be lost, so the sweep that closes the server has to give it the same treatment. The pool below
        // refuses the sweep's own attempts - a contention that clears - and then takes the retry.
        Map<Integer, Object> durable = new HashMap<>();
        Connection good = committingPool(durable);
        Connection refusing = mockPool(deadlocked());
        int[] attempts = {0};
        BotStore.useConnectionSourceForTest(() -> attempts[0]++ < 4 ? refusing : good);
        try {
            BotStore.settleAndSaveAll(List.of(bot));
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(907_000, durable.get(updateColumns().indexOf("Meso") + 1),
                        "the last row of the process has to carry the debit"),
                () -> assertFalse(BotHoldings.hasUnpersistedDebit(bot),
                        "the retry recorded it, so nothing is left for the next boot to restore"),
                () -> verify(good).commit());
    }

    @Test
    void aCreditCapturedIntoTheRowIsNotHandedToTheWorldAsWell() throws SQLException {
        Character bot = newBot(BOT_ID, "Earner");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);

        // A creditor that runs while this bot is being despawned, but before the write has captured anything:
        // a sale in a bot-owned shop, a trade that completes as the window is closed. The credit is part of the
        // state the row is captured from, so the row carries it and the ledger has to let it go - settling it
        // as well would leave the value in the row and on the floor at the same time.
        Map<Integer, Object> durable = new HashMap<>();
        Connection con = committingPool(durable);
        boolean[] credited = {false};
        BotStore.useConnectionSourceForTest(() -> {
            if (!credited[0]) {
                credited[0] = true;
                assertTrue(BotHoldings.grantMeso(bot, 4_000));
            }
            return con;
        });
        try {
            BotStore.saveThenSettle(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }
        assertAll(
                () -> assertEquals(4_000, durable.get(updateColumns().indexOf("Meso") + 1),
                        "the credit landed before the capture, so the row is what holds it"),
                () -> assertEquals(0, BotHoldings.ledgerStamp(bot),
                        "a delta the row already holds must not be kept for a settle to hand out a second time"),
                () -> verify(map, never()).spawnMesoDrop(4_000, bot.getPosition(), bot, bot, true, (byte) 2,
                        (short) 0));
    }

    @Test
    void aMesoDebitInTheCaptureWindowIsMintedBackByTheRow() throws SQLException {
        Character bot = newBot(BOT_ID, "Spender");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        // What it holds it got before this run: the thousand mesos never came through the ledger, so anything
        // it spends from here on is a debit against what its stored row claims.
        bot.gainMeso(1_000, false, false, false);

        // The spend lands after the write read the row's scalars (of(bot) bound the 1_000) but before its
        // capture ended, so that attempt would describe two moments. Accepting it commits a row claiming the
        // thousand, and the caller - seeing a stamp that moved - then keeps the deltas, so the debit is in no
        // row and a restart restores the thousand on top of the three hundred the counterparty durably holds:
        // a mint. The bracketed capture refuses that row instead (rollback below) and commits exactly one, the
        // one built from the 700. What distinguishes the two is therefore which rows get committed at all, so
        // the committed values are recorded here and asserted below - not just where they ended up.
        Map<Integer, Object> durable = new HashMap<>();
        Map<Integer, Object> pending = new HashMap<>();
        List<Object> committedMeso = new ArrayList<>();
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        PreparedStatement row = recordingInto(pending);
        boolean[] spent = {false};
        when(row.executeUpdate()).thenAnswer(call -> {
            if (!spent[0]) {
                spent[0] = true;
                assertTrue(BotHoldings.spendMeso(bot, 300), "the bot spends out of what its row claims");
            }
            return 1;
        });
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(row);
        doAnswer(call -> {
            committedMeso.add(pending.get(updateColumns().indexOf("Meso") + 1));
            durable.putAll(pending);
            pending.clear();
            return null;
        }).when(con).commit();
        doAnswer(call -> {
            pending.clear();
            return null;
        }).when(con).rollback();
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.saveThenSettle(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(List.of(700), committedMeso,
                        "no row claiming the mesos the bot spent may be committed at all"),
                () -> assertEquals(700, durable.get(updateColumns().indexOf("Meso") + 1),
                        "the row a restart reads must not claim the mesos the bot spent while that same row was"
                                + " being captured"),
                () -> assertFalse(BotHoldings.hasUnpersistedDebit(bot),
                        "the retried capture records the debit, so there is nothing left to forgive"),
                () -> verify(map, never()).spawnMesoDrop(anyInt(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyByte(),
                        org.mockito.ArgumentMatchers.anyShort()));
    }

    @Test
    void aMesoCreditInTheCaptureWindowIsCapturedRatherThanSettled() throws SQLException {
        Character bot = newBot(BOT_ID, "Earner");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        bot.gainMeso(1_000, false, false, false);

        // Same window, the other direction: a bot-owned shop sale lands after the row's scalars were read and
        // before the capture ended. A capture that is accepted anyway is two moments, and whichever way the
        // caller then goes is wrong - dropping the delta destroys the sale, settling it hands out a credit the
        // row holds. The capture is retried instead, so the row carries it.
        Map<Integer, Object> durable = new HashMap<>();
        Map<Integer, Object> pending = new HashMap<>();
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        PreparedStatement row = recordingInto(pending);
        boolean[] credited = {false};
        when(row.executeUpdate()).thenAnswer(call -> {
            if (!credited[0]) {
                credited[0] = true;
                assertTrue(BotHoldings.grantMeso(bot, 400));
            }
            return 1;
        });
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(row);
        doAnswer(call -> {
            durable.putAll(pending);
            pending.clear();
            return null;
        }).when(con).commit();
        doAnswer(call -> {
            pending.clear();
            return null;
        }).when(con).rollback();
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.saveThenSettle(bot);
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(1_400, durable.get(updateColumns().indexOf("Meso") + 1),
                        "the retried capture takes the credit in, so the row a restart reads holds it"),
                () -> assertEquals(0, BotHoldings.ledgerStamp(bot),
                        "and the ledger has nothing left to hand to the world a second time"),
                () -> verify(map, never()).spawnMesoDrop(anyInt(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyByte(),
                        org.mockito.ArgumentMatchers.anyShort()));
    }

    @Test
    void theLastWriteOfTheProcessRetriesAChangeItMissed() throws SQLException {
        Character bot = newBot(BOT_ID, "Earner");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);

        // The credit lands after the state was captured but before that write commits, so the row does not
        // carry it. Mid-run the deltas would simply wait for the next sweep; this is the shutdown sweep and
        // there is no next write, so waiting is losing them.
        Map<Integer, Object> durable = new HashMap<>();
        Map<Integer, Object> pending = new HashMap<>();
        boolean[] credited = {false};
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        PreparedStatement row = recordingInto(pending);
        when(row.executeUpdate()).thenReturn(1);
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(row);
        doAnswer(call -> {
            durable.putAll(pending);
            pending.clear();
            if (!credited[0]) {
                credited[0] = true;
                assertTrue(BotHoldings.grantMeso(bot, 4_000));
            }
            return null;
        }).when(con).commit();
        doAnswer(call -> {
            pending.clear();
            return null;
        }).when(con).rollback();
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            BotStore.settleAndSaveAll(List.of(bot));
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }

        assertAll(
                () -> assertEquals(4_000, durable.get(updateColumns().indexOf("Meso") + 1),
                        "the last write of the process has to record the credit it missed, not keep waiting"),
                () -> assertEquals(0, BotHoldings.ledgerStamp(bot),
                        "the retry recorded it, so nothing is left for the next boot to restore"));
    }

    @Test
    void durableHoldingsAreNotHandedToTheWorldAsWell() {
        Character bot = newBot(BOT_ID, "Durable");
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.grantMeso(bot, 5_000);

        BotHoldings.discardIfUnchanged(bot, BotHoldings.ledgerStamp(bot));
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
    void theHoldingsSnapshotIsTakenUnderTheTabsOwnLock() throws SQLException {
        Character bot = newBot(BOT_ID, "Racer");
        LockedViewInventory use = new LockedViewInventory(bot, InventoryType.USE, (byte) 96);
        use.addItem(new Item(2_000_000, (short) 0, (short) 1, -1));
        bot.setInventory(InventoryType.USE, use);

        // A casino pays a pot out of the house's own inventory on a thread of its own, seconds of deliberate
        // sleeps and all, while the house keeps ticking and the sweep keeps saving it. Inventory.list() hands
        // out a view of the tab's live map rather than a copy, so reading it without the tab's lock is a race,
        // and an unlocked iteration can also throw part way through - which in the despawn path flips the bot
        // onto the failed-save route. This tab refuses to be read by a thread that is not holding its lock, so
        // the save below only gets through if it reads the tab while it holds it. What the read has to be worth
        // is pinned separately: aSnapshotDescribesTheItemsAsTheyWereRatherThanAsTheyAre for the row describing
        // one moment, aChangeTheRowMissedKeepsItsDeltasInsteadOfBeingForgotten for a change it missed.
        Connection con = mockPool(accepting());
        BotStore.useConnectionSourceForTest(() -> con);
        try {
            assertDoesNotThrow(() -> BotStore.save(bot),
                    "the sweep has to read each tab while it holds that tab");
        } finally {
            BotStore.useConnectionSourceForTest(null);
        }
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
        // The item rows are inserted through the generated-keys overload, which is a statement like any other.
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        // Declared last on purpose: with two matchers that both match, the last stubbing wins.
        when(con.prepareStatement(BotStore.INSERT_ROW))
                .thenAnswer(call -> queue.size() > 1 ? queue.poll() : queue.peek());
        return con;
    }

    /**
     * A pool whose row statement answers that it updated a row and keeps what it bound, so a test can read
     * back what a restart would: the values only land in {@code durable} once the transaction commits.
     */
    private static Connection committingPool(Map<Integer, Object> durable) throws SQLException {
        Connection con = mock(Connection.class);
        PreparedStatement spare = passive();
        when(con.prepareStatement(anyString())).thenReturn(spare);
        // The item insert is prepared through the generated-keys overload; an unstubbed call there returns
        // null and the write dies with a NullPointerException instead of the failure a test means to stage.
        when(con.prepareStatement(anyString(), anyInt())).thenReturn(spare);
        Map<Integer, Object> pending = new HashMap<>();
        PreparedStatement row = recordingInto(pending);
        when(row.executeUpdate()).thenReturn(1);
        when(con.prepareStatement(BotStore.UPDATE_ROW)).thenReturn(row);
        doAnswer(call -> {
            durable.putAll(pending);
            pending.clear();
            return null;
        }).when(con).commit();
        doAnswer(call -> {
            pending.clear();
            return null;
        }).when(con).rollback();
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

    /**
     * A tab that will not hand out its live view to a caller that is not already holding its lock: that view is
     * the tab's own map, so reading it outside the lock is not a snapshot, it is a race with whoever is paying
     * a pot out of the same tab.
     */
    private static class LockedViewInventory extends Inventory {

        LockedViewInventory(Character owner, InventoryType type, byte slotLimit) {
            super(owner, type, slotLimit);
        }

        @Override
        public Collection<Item> list() {
            if (!((ReentrantLock) lock).isHeldByCurrentThread()) {
                throw new IllegalStateException("the live view of a tab was read without holding its lock");
            }
            return super.list();
        }
    }

    @FunctionalInterface
    private interface RowWrite {
        int apply(PreparedStatement statement) throws SQLException;
    }
}
