package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// The bot accounting path, exercised on a real bot Character (a real Character carrying the shared headless
// BotClient, exactly what a live bot is) with real Item/Inventory objects - nothing stands in for the bot, its
// inventories or its items. Only the map a bot settles onto is a mock, so the drops it hands over are visible to
// the test. Bots are never written back to the DB, so this seam is the only thing standing between a looted drop
// and its silent destruction.
class BotHoldingsTest {

    // The ledger is static and keyed by Character identity, so a fresh Character per test would still share the
    // map with its siblings; start every test from an empty one.
    @BeforeEach
    void resetLedgers() {
        BotHoldings.clearForTest();
    }

    // USE items: they stack, and with no WZ data loaded they fall back to the item data reader's own default
    // slot max (100), the same value the live server gives an item without an explicit slotMax.
    private static final int POTION = 2000000;
    private static final int OTHER_POTION = 2000001;

    private static Character newBot() {
        return Character.getDefault(new BotClient(0, 0));
    }

    private static Item itemOf(int itemId, int quantity) {
        return new Item(itemId, (short) 0, (short) quantity, -1);
    }

    private static MapItem itemDropOf(Character bot, Item item) {
        return new MapItem(item, new Point(0, 0), bot, bot, bot.getClient(), (byte) 2, true);
    }

    private static MapItem mesoDropOf(Character bot, int meso) {
        return new MapItem(meso, new Point(0, 0), bot, bot, bot.getClient(), (byte) 2, true);
    }

    // Fills every slot of an inventory with a distinct item id (so nothing can stack) starting at firstItemId,
    // which the ids used by the assertions stay clear of.
    private static void fillInventory(Character bot, InventoryType type, int firstItemId) {
        Inventory inv = bot.getInventory(type);
        for (int i = 0; i < inv.getSlotLimit(); i++) {
            assertTrue(inv.addItem(itemOf(firstItemId + i, 1)) > 0);
        }
        assertTrue(inv.isFull());
    }

    @Test
    void creditedDropMesoLandsInBotMeso() {
        Character bot = newBot();

        assertTrue(BotHoldings.creditDrop(bot, mesoDropOf(bot, 1234)));

        assertEquals(1234, bot.getMeso());
    }

    @Test
    void creditedDropItemStacksIntoTheRightInventorySlot() {
        Character bot = newBot();
        Inventory use = bot.getInventory(InventoryType.USE);
        use.addItem(itemOf(POTION, 10));
        short existingSlot = use.listById(POTION).get(0).getPosition();

        assertTrue(BotHoldings.creditDrop(bot, itemDropOf(bot, itemOf(POTION, 5))));

        List<Item> stacked = use.listById(POTION);
        assertEquals(1, stacked.size());
        assertEquals(existingSlot, stacked.get(0).getPosition());
        assertSame(stacked.get(0), use.getItem(existingSlot));
        assertEquals(15, stacked.get(0).getQuantity());

        assertTrue(BotHoldings.creditDrop(bot, itemDropOf(bot, itemOf(OTHER_POTION, 7))));

        List<Item> fresh = use.listById(OTHER_POTION);
        assertEquals(1, fresh.size());
        assertTrue(fresh.get(0).getPosition() > 0);
        assertSame(fresh.get(0), use.getItem(fresh.get(0).getPosition()));
        assertEquals(7, fresh.get(0).getQuantity());
    }

    @Test
    void spendMesoRefusesAndLeavesMesoUnchangedWhenShort() {
        Character bot = newBot();
        assertTrue(BotHoldings.grantMeso(bot, 100));
        assertEquals(100, bot.getMeso());

        assertFalse(BotHoldings.spendMeso(bot, 101));
        assertEquals(100, bot.getMeso());

        assertTrue(BotHoldings.spendMeso(bot, 100));
        assertEquals(0, bot.getMeso());
    }

    @Test
    void grantItemRefusesAndGrantsNothingWhenInventoryIsFull() {
        Character bot = newBot();
        fillInventory(bot, InventoryType.USE, 2010000);
        Inventory use = bot.getInventory(InventoryType.USE);
        assertEquals(0, use.getNumFreeSlot());

        assertFalse(BotHoldings.grantItem(bot, itemOf(POTION, 1)));

        assertTrue(use.listById(POTION).isEmpty());
        assertEquals(0, use.getNumFreeSlot());
    }

    @Test
    void refusedCreditLeavesTheDropOnTheFloor() {
        Character bot = newBot();
        fillInventory(bot, InventoryType.USE, 2020000);
        MapItem drop = itemDropOf(bot, itemOf(POTION, 1));

        assertFalse(BotHoldings.canCreditDrop(bot, drop));
        assertFalse(BotHoldings.creditDrop(bot, drop));

        assertFalse(drop.isPickedUp());
        assertTrue(bot.getInventory(InventoryType.USE).listById(POTION).isEmpty());
    }

    @Test
    void settleHandsTheBotsNetHoldingsToTheMapItLeaves() {
        Character bot = newBot();
        BotHoldings.grantMeso(bot, 700);
        BotHoldings.grantItem(bot, itemOf(POTION, 5));
        assertTrue(BotHoldings.spendMeso(bot, 200)); // spent meso must not come back

        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);

        BotHoldings.settle(bot);
        BotHoldings.settle(bot); // the ledger is handed over once; a second settle has nothing left to drop

        ArgumentCaptor<Item> dropped = ArgumentCaptor.forClass(Item.class);
        verify(map, times(1)).spawnItemDrop(eq(bot), eq(bot), dropped.capture(), any(), eq(true), eq(true));
        assertEquals(POTION, dropped.getValue().getItemId());
        assertEquals(5, dropped.getValue().getQuantity());

        ArgumentCaptor<Integer> droppedMeso = ArgumentCaptor.forClass(Integer.class);
        verify(map, times(1)).spawnMesoDrop(droppedMeso.capture(), any(), eq(bot), eq(bot), eq(true), eq((byte) 2), eq((short) 0));
        assertEquals(500, droppedMeso.getValue());

        // Settling is a projection of the ledger onto the map, not a second drain of the bot itself.
        assertEquals(500, bot.getMeso());
        assertEquals(5, bot.getInventory(InventoryType.USE).listById(POTION).get(0).getQuantity());
    }

    @Test
    void settleKeepsTheLedgerWhenTheBotHasNoMapYet() {
        Character bot = newBot();
        BotHoldings.grantMeso(bot, 300);

        BotHoldings.settle(bot); // nowhere to drop it: the holdings must stay on the books, not vanish

        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.settle(bot);

        ArgumentCaptor<Integer> droppedMeso = ArgumentCaptor.forClass(Integer.class);
        verify(map, times(1)).spawnMesoDrop(droppedMeso.capture(), any(), eq(bot), eq(bot), eq(true), eq((byte) 2), eq((short) 0));
        assertEquals(300, droppedMeso.getValue());
    }
}
