package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * DropCommands is the fabrication boundary: every drop it makes itself is a prop (conjured=true) except an item
 * the caller hands in. These tests capture the flag at the MapleMap call so a future refactor cannot quietly
 * flip a fabrication site back to real value.
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
        MapleMap map = mock(MapleMap.class);
        when(map.spawnItemDropNoExpire(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(mock(MapItem.class));
        bot.setMap(map);
        return map;
    }

    private static boolean itemDropFlag(MapleMap map) {
        ArgumentCaptor<Boolean> conjured = ArgumentCaptor.forClass(Boolean.class);
        verify(map).spawnItemDrop(any(), any(), any(), any(), anyBoolean(), anyBoolean(), conjured.capture());
        return conjured.getValue();
    }

    @Test
    void aFabricatedItemDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botDropItem(bot, STAMP);

        assertTrue(itemDropFlag(map), "a bot-fabricated item drop is a prop");
    }

    @Test
    void aFabricatedEquipDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botThrowEquip(bot, STAMP, AT);

        assertTrue(itemDropFlag(map), "a bot-fabricated equip drop is a prop");
    }

    @Test
    void aCallerSuppliedEquipStaysCreditable() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        // A mocked Equip: the constructor needs loaded item data, and all this seam cares about is the flag.
        DropCommands.botThrowEquip(bot, mock(Equip.class), AT);

        assertFalse(itemDropFlag(map), "a caller-supplied equip is real value and stays creditable");
    }

    @Test
    void aFabricatedMesoDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botThrowMeso(bot, 1000, AT);

        ArgumentCaptor<Boolean> conjured = ArgumentCaptor.forClass(Boolean.class);
        verify(map).spawnMesoDrop(anyInt(), any(), any(), any(), anyBoolean(), anyByte(), anyShort(),
                conjured.capture());
        assertTrue(conjured.getValue(), "a bot-fabricated meso drop is a prop");
    }

    @Test
    void aFabricatedOwnerOnlyDropIsAProp() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        DropCommands.botThrowItemNoExpireOwnerOnly(bot, STAMP, AT);

        ArgumentCaptor<Boolean> conjured = ArgumentCaptor.forClass(Boolean.class);
        verify(map).spawnItemDropNoExpire(any(), any(), any(), any(), anyBoolean(), anyBoolean(), conjured.capture());
        assertTrue(conjured.getValue(), "a bot-fabricated owner-only drop is a prop");
    }

    @Test
    void aFabricatedItemIsHandedBackUnchanged() {
        Character bot = newBot();
        MapleMap map = mapFor(bot);

        Item handedBack = DropCommands.botThrowItem(bot, STAMP, AT);

        assertTrue(itemDropFlag(map), "a bot-fabricated item drop is a prop");
        verify(map).spawnItemDrop(any(), any(), eq(handedBack), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }
}
