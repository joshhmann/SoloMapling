package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/*
 * DropCommands is the fabrication boundary: every drop it makes itself is a prop (conjured=true) except an item
 * the caller hands in. These tests read the flag off the drop the map really registered, so a future refactor
 * that drops MapItem.setConjured from the map's drop paths - or flips a fabrication site back to real value -
 * fails here. Only calcDropPos is stubbed, since it needs loaded footholds and this checkout is WZ-less.
 */
class DropCommandsProvenanceTest {

    private static final int STAMP = 4002000;
    private static final Point AT = new Point(10, 10);

    private static Character newBot() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(7);
        return bot;
    }

    private static MapleMap mapFor(Character bot) {
        MapleMap map = spy(new MapleMap(900000001, 0, 1, 0, 1f));
        doReturn(AT).when(map).calcDropPos(any(), any());
        bot.setMap(map);
        return map;
    }

    private static MapItem registeredDrop(MapleMap map) {
        for (MapObject object : map.getMapObjectsInRange(AT, 40000.0, List.of(MapObjectType.ITEM))) {
            MapItem drop = (MapItem) object;
            if (!drop.isPickedUp()) {
                return drop;
            }
        }
        return null;
    }

    @Test
    void aFabricatedItemDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botDropItem(bot, STAMP);

        MapItem drop = registeredDrop(map);
        assertNotNull(drop, "the drop has to be on the map");
        assertTrue(drop.isConjured(), "a bot-fabricated item drop is a prop");
    }

    @Test
    void aFabricatedEquipDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botThrowEquip(bot, STAMP, AT);

        MapItem drop = registeredDrop(map);
        assertNotNull(drop, "the drop has to be on the map");
        assertTrue(drop.isConjured(), "a bot-fabricated equip drop is a prop");
    }

    @Test
    void aCallerSuppliedEquipStaysCreditable() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        // A mocked Equip: the constructor needs loaded item data, and all this seam cares about is the flag.
        DropCommands.botThrowEquip(bot, mock(Equip.class), AT);

        MapItem drop = registeredDrop(map);
        assertNotNull(drop, "the drop has to be on the map");
        assertFalse(drop.isConjured(), "a caller-supplied equip is real value and stays creditable");
    }

    @Test
    void aFabricatedMesoDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botThrowMeso(bot, 1000, AT);

        MapItem drop = registeredDrop(map);
        assertNotNull(drop, "the drop has to be on the map");
        assertTrue(drop.isConjured(), "a bot-fabricated meso drop is a prop");
    }

    @Test
    void aFabricatedOwnerOnlyDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botThrowItemNoExpireOwnerOnly(bot, STAMP, AT);

        MapItem drop = registeredDrop(map);
        assertNotNull(drop, "the drop has to be on the map");
        assertTrue(drop.isConjured(), "a bot-fabricated owner-only drop is a prop");
    }

    @Test
    void aFabricatedItemIsHandedBackUnchanged() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        Item handedBack = DropCommands.botThrowItem(bot, STAMP, AT);

        MapItem drop = registeredDrop(map);
        assertNotNull(drop, "the drop has to be on the map");
        assertTrue(drop.isConjured(), "a bot-fabricated item drop is a prop");
        assertSame(handedBack, drop.getItem(), "the map gets the item the command made, not a copy of it");
    }
}
