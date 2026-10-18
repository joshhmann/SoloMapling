package soloMapling.MapVFX;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Reactor drops are the one provenance path that cannot be seen from BotHoldings: the OPQ cloud/record pieces
 * are fabricated by bot code but real reactor content drops with the same ids, so the flag has to ride the
 * drop call itself. These tests pin that the bot-fabricated call sites pass conjured=true and that MapleMap
 * honours whatever it is handed. The map's real drop body runs; only calcDropPos is stubbed, since that needs
 * loaded footholds and this checkout is WZ-less.
 */
class CustomReactorProvenanceTest {

    private static final Point POS = new Point(50, 50);
    private static final int STAMP = 4002000;

    private static Character newOwner() {
        Character owner = Character.getDefault(new BotClient(0, 0));
        owner.setID(1);
        return owner;
    }

    private static MapleMap realMap() {
        MapleMap map = spy(new MapleMap(900000001, 0, 1, 0, 1f));
        doReturn(POS).when(map).calcDropPos(any(), any());
        return map;
    }

    private static MapItem findDrop(MapleMap map) {
        List<MapObjectType> types = List.of(MapObjectType.ITEM);
        for (MapObject object : map.getMapObjectsInRange(POS, 40000.0, types)) {
            MapItem drop = (MapItem) object;
            if (!drop.isPickedUp()) {
                return drop;
            }
        }
        return null;
    }

    @Test
    void fabricatedReactorItemDropIsAProp() {
        MapleMap map = mock(MapleMap.class);
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(new Point(POS));
        when(map.getReactorByOid(3)).thenReturn(reactor);

        CustomReactor.dropItemAtReactor(map, 3, STAMP, newOwner());

        verify(map).dropFromReactor(any(Character.class), eq(reactor), any(Item.class), any(Point.class),
                eq((short) 0), eq((short) 0), eq(true));
    }

    @Test
    void aFabricatedReactorItemDropCarriesThePropFlagOnTheMap() {
        MapleMap map = realMap();
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(new Point(POS));

        map.dropFromReactor(newOwner(), reactor, new Item(STAMP, (short) 0, (short) 1), POS, (short) 0, (short) 0,
                true);

        MapItem drop = findDrop(map);
        assertNotNull(drop);
        assertTrue(drop.isConjured(), "a fabricated reactor drop is a prop");
    }

    @Test
    void aRealReactorItemDropStaysCreditable() {
        MapleMap map = realMap();
        Reactor reactor = mock(Reactor.class);
        when(reactor.getPosition()).thenReturn(new Point(POS));

        // The old signature is the real-content path: it must keep dropping unmarked.
        map.dropFromReactor(newOwner(), reactor, new Item(STAMP, (short) 0, (short) 1), POS, (short) 0, (short) 0);

        MapItem drop = findDrop(map);
        assertNotNull(drop);
        assertFalse(drop.isConjured(), "real reactor content stays creditable");
    }

    @Test
    void aFabricatedReactorMesoDropCarriesThePropFlagOnTheMap() {
        MapleMap map = realMap();
        Character owner = newOwner();

        map.spawnMesoDrop(1000, POS, owner, owner, false, (byte) 2, (short) 0, true);

        MapItem drop = findDrop(map);
        assertNotNull(drop);
        assertTrue(drop.isConjured(), "a fabricated reactor meso drop is a prop");
    }

    @Test
    void aRealReactorMesoDropStaysCreditable() {
        MapleMap map = realMap();
        Character owner = newOwner();

        map.spawnMesoDrop(1000, POS, owner, owner, false, (byte) 2, (short) 0);

        MapItem drop = findDrop(map);
        assertNotNull(drop);
        assertFalse(drop.isConjured(), "real reactor meso stays creditable");
    }
}
