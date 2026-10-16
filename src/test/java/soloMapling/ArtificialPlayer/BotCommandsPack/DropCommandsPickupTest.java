package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import server.maps.MapItem;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Bot loot pickup runs on the bot's own tick thread, and several bots sweep the same map's drops, so the
 * "is this drop still there" check, the credit and the removal have to happen under the drop's lock - the same
 * discipline Character.pickupItem uses. A drop that is credited twice is a duplicated item or meso that the
 * bot's despawn then hands to the floor as if it were real. Character/Inventory/Item here are real; only the map
 * and the drop are mocks so the lock order and the removal can be observed.
 */
class DropCommandsPickupTest {

    private static Character newBot() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(21000);   // bot ids are above 20000 (BotHelpers.isArtificial)
        return bot;
    }

    private static MapItem drop() {
        MapItem drop = mock(MapItem.class);
        when(drop.isPickedUp()).thenReturn(false);
        when(drop.getObjectId()).thenReturn(7);
        return drop;
    }

    @Test
    void aPickupCreditsAndRemovesTheDropUnderItsLock() {
        Character bot = newBot();
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        MapItem mesos = drop();
        when(mesos.getMeso()).thenReturn(300);

        DropCommands.botLootSingleDrop(bot, mesos);

        assertEquals(300, bot.getMeso(), "the drop is credited, not just deleted");
        InOrder order = inOrder(mesos, map);
        order.verify(mesos).lockItem();
        order.verify(map).pickItemDrop(any(Packet.class), eq(mesos));
        order.verify(mesos).unlockItem();
    }

    @Test
    void aDropTheBotCannotHoldIsLeftOnTheFloorUnlockedAndUncredited() {
        Character bot = newBot();
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);

        Inventory etc = bot.getInventory(InventoryType.ETC);
        for (int itemId = 4000000; etc.getNumFreeSlot() > 0; itemId++) {
            etc.addItem(new Item(itemId, (short) 0, (short) 1, -1));
        }

        MapItem overflow = drop();
        when(overflow.getMeso()).thenReturn(0);
        when(overflow.getItem()).thenReturn(new Item(4001000, (short) 0, (short) 1, -1));

        DropCommands.botLootSingleDrop(bot, overflow);

        verify(map, never()).pickItemDrop(any(Packet.class), any(MapItem.class));
        verify(overflow).unlockItem();
        assertTrue(bot.getInventory(InventoryType.ETC).listById(4001000).isEmpty(),
                "a drop that does not fit stays on the floor for someone who can hold it");
    }
}
