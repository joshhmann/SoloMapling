package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.Trade;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHoldings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/*
 * What a bot's trade offer does to the bot's own accounting.
 *
 * A bot's item offers are generated for the window, so they are window-only value: an unfinished trade must not
 * hand the offer back as a real item. That credit used to happen (Trade.cancel returned every bot offer to the
 * bot's holdings), and the bot's next despawn then spawned it on the floor - items out of nothing. A bot's mesos
 * are real: they leave its wallet while offered and come back if the trade dies.
 *
 * The offer is posted through the Trade API rather than BotTradeCommands.addItemToTrade because announcing an
 * offer builds an item packet, which reads item data and a DB row count at class-init time - not available in this
 * harness. Trade.cancel is what the regression is about, and it is driven here exactly as a decline drives it.
 */
class BotTradeOfferTest {

    private static final int POTION = 2000000;

    private static Character newBot() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(21000); // Trade's bot branches key on BotHelpers.isArtificial: any id above 20000
        return bot;
    }

    private static int heldPotions(Character bot) {
        return bot.getInventory(InventoryType.USE).listById(POTION).stream()
                .mapToInt(Item::getQuantity)
                .sum();
    }

    private static void postOffer(Character bot, int quantity) {
        assertTrue(bot.getTrade().addItem(new Item(POTION, (short) 1, (short) quantity, -1)));
    }

    @Test
    void decliningATradeDoesNotCreditTheBotsOwnItemOffer() {
        Character bot = newBot();
        Trade.startTrade(bot);
        postOffer(bot, 5);

        Trade.declineTrade(bot);

        assertEquals(0, heldPotions(bot), "an offer is window value and must not come home as a real item");
        assertTrue(bot.getInventory(InventoryType.USE).listById(POTION).isEmpty());
    }

    @Test
    void declinedTradeReturnsTheBotsOfferedMesos() {
        Character bot = newBot();
        assertTrue(BotHoldings.grantMeso(bot, 1_000));
        Trade.startTrade(bot);

        BotTradeCommands.setMeso(bot, 400);
        assertEquals(600, bot.getMeso(), "offered mesos leave the bot's wallet while they are posted");
        assertEquals(400, BotTradeCommands.readMeso(bot));

        Trade.declineTrade(bot);

        assertEquals(1_000, bot.getMeso(), "an unfinished trade must give the real mesos back");
        assertEquals(1_000, settledMesos(bot), "and the ledger follows the wallet, so all of it settles");
    }

    @Test
    void offeredMesosAreNotSpawnedBySettleWhileTheTradeIsStillOpen() {
        Character bot = newBot();
        assertTrue(BotHoldings.grantMeso(bot, 1_000));
        Trade.startTrade(bot);
        BotTradeCommands.setMeso(bot, 400);

        // A bot that despawns with an offer still standing keeps its wallet; the offered mesos are withheld, not
        // handed back onto the floor as if the bot still had them.
        assertEquals(600, settledMesos(bot));
    }

    private static int settledMesos(Character bot) {
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.settle(bot);
        ArgumentCaptor<Integer> dropped = ArgumentCaptor.forClass(Integer.class);
        verify(map, times(1)).spawnMesoDrop(dropped.capture(), any(), eq(bot), eq(bot), eq(true), eq((byte) 2),
                eq((short) 0));
        return dropped.getValue();
    }

    @Test
    void aBotCannotOfferMesosItDoesNotHold() {
        Character bot = newBot();
        assertTrue(BotHoldings.grantMeso(bot, 100));
        Trade.startTrade(bot);

        BotTradeCommands.setMeso(bot, 500);

        assertEquals(100, bot.getMeso());
        assertEquals(0, BotTradeCommands.readMeso(bot), "nothing is posted that the bot cannot pay for");
    }
}
