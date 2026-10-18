package soloMapling.ArtificialPlayer.BotTypes.Blackjack;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * The casino table pays a winning hand out of the bets it collected: a real player gets real units taken from
 * the dealer's stock through the ledger, while a bot seat gets a display-only prop that moves no value. These
 * tests drive the per-unit decision directly so the policy stays pinned even though the payout itself runs on a
 * background thread. Characters and Items are real; only the map is a mock, so the drops it would spawn are
 * visible to the test.
 */
class BlackjackPayoutTest {

    // An ETC stamp: the denomination the blackjack table bets with.
    private static final int STAMP = 4002000;

    private final List<MapItem> spawned = new ArrayList<>();

    @BeforeEach
    void clearDrops() {
        spawned.clear();
    }

    private static Character newCharacter(int id) {
        Character character = Character.getDefault(new BotClient(0, 0));
        character.setID(id);
        return character;
    }

    // Mirrors what a real map does after building the drop: publish it and stamp the provenance it was given.
    private MapleMap mapPublishingDrops() {
        MapleMap map = mock(MapleMap.class);
        when(map.spawnItemDropNoExpire(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    Item item = invocation.getArgument(2);
                    Character owner = invocation.getArgument(1);
                    byte dropType = (byte) (invocation.<Boolean>getArgument(4) ? 2 : 0);
                    MapItem drop = new MapItem(item, invocation.getArgument(3), invocation.getArgument(0),
                            owner, owner.getClient(), dropType, invocation.getArgument(5));
                    drop.setConjured(invocation.getArgument(6));
                    spawned.add(drop);
                    return drop;
                });
        return map;
    }

    @Test
    void aBotSeatIsPaidAPropAndMovesNoValue() {
        Character dealer = newCharacter(21000);
        Character winner = newCharacter(21001);
        BotHoldings.grantItem(dealer, new Item(STAMP, (short) 0, (short) 2, -1));
        dealer.setMap(mapPublishingDrops());

        assertTrue(BlackjackDealerBot.payUnit(dealer, winner, true, STAMP, new Point(5, 5)));

        assertEquals(1, spawned.size());
        MapItem prop = spawned.get(0);
        assertTrue(prop.isConjured(), "a bot seat's unit is a display-only prop");
        assertEquals(winner.getId(), prop.getOwnerId());
        assertEquals(2, dealer.getInventory(InventoryType.ETC).listById(STAMP).get(0).getQuantity(),
                "a bot seat moves no value, so the dealer's stock is untouched");
    }

    @Test
    void aRealPlayerIsPaidOutOfTheHousesStock() {
        Character dealer = newCharacter(21000);
        Character winner = newCharacter(21001);
        BotHoldings.grantItem(dealer, new Item(STAMP, (short) 0, (short) 1, -1));
        dealer.setMap(mapPublishingDrops());

        assertTrue(BlackjackDealerBot.payUnit(dealer, winner, false, STAMP, new Point(7, 7)));

        assertEquals(1, spawned.size());
        MapItem payout = spawned.get(0);
        assertFalse(payout.isConjured(), "a real player's unit is real value taken from the house");
        assertEquals(winner.getId(), payout.getOwnerId());
        assertTrue(dealer.getInventory(InventoryType.ETC).listById(STAMP).isEmpty(),
                "the paid unit left the dealer's stock");
    }

    @Test
    void anEmptyHousePaysNothingInsteadOfMintingChips() {
        Character dealer = newCharacter(21000);
        Character winner = newCharacter(21001);
        dealer.setMap(mapPublishingDrops());

        assertFalse(BlackjackDealerBot.payUnit(dealer, winner, false, STAMP, new Point(0, 0)));

        assertTrue(spawned.isEmpty(), "the house mints nothing when it holds nothing");
    }
}
