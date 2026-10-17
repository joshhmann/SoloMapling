package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.Trade;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/*
 * Who gets paid when a stall sells something.
 *
 * PlayerShop credits the owner's wallet directly (owner.gainMeso) - correct for a player, whose wallet needs no
 * second record. But a bot can own a stall (FreeMarket builds PlayerShop around a bot Character) and a bot can
 * shop at one, and a bot's wallet is durable only through BotHoldings: while that credit stayed unrecorded the
 * sale was invisible to settle(), so the mesos the buyer really paid vanished with the bot instead of going back
 * to the world.
 *
 * The player-owned and player-buyer cases must stay untouched. PlayerShop.buy is not driven end to end here: its
 * visitor path builds a character-look packet, which needs item data and its DB row counter - neither exists in
 * this harness - so the credit both purchase paths share is pinned directly instead.
 */
class PlayerShopBotSaleTest {

    private static final int BOT_OWNER_ID = 21_000;   // above BotHelpers.BOT_BASE_ID, so isBot() holds
    private static final int BOT_BUYER_ID = 21_001;
    private static final int PLAYER_ID = 1;
    private static final int POTION = 2000000;
    private static final int PRICE = 100_000;
    private static final int TAKEN = PRICE - Trade.getFee(PRICE);   // the owner is paid the price less the trade fee

    @BeforeEach
    void resetLedgers() {
        BotHoldings.clearForTest();
    }

    // Character.getDefault leaves both the id and the name unset; sold items carry the buyer's name into a packet
    // the owner receives, so every test character needs one.
    private static Character character(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Test" + id);
        return chr;
    }

    /** A stall carrying bundles of a potion at PRICE each. */
    private static PlayerShop stallOf(Character owner) {
        PlayerShop shop = new PlayerShop(owner, "test stall", 0);
        assertTrue(shop.addItem(new PlayerShopItem(new Item(POTION, (short) 0, (short) 1, -1), (short) 3, PRICE)));
        shop.setOpen(true);
        return shop;
    }

    /** The mesos the bot's ledger hands back to the world when it leaves; only a mock map can see them. */
    private static int settledMesos(Character bot) {
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.settle(bot);

        ArgumentCaptor<Integer> dropped = ArgumentCaptor.forClass(Integer.class);
        verify(map).spawnMesoDrop(dropped.capture(), any(), eq(bot), eq(bot), eq(true), eq((byte) 2), eq((short) 0));
        return dropped.getValue();
    }

    @Test
    void aBotOwnedStallPaysItsBotOwnerThroughTheLedger() {
        Character owner = character(BOT_OWNER_ID);
        Character buyer = character(BOT_BUYER_ID);
        assertTrue(BotHoldings.grantMeso(buyer, 1_000_000));
        PlayerShop shop = stallOf(owner);

        assertTrue(shop.botBuy(buyer, shop.getItems().get(0), 0, (short) 1));

        assertEquals(1_000_000 - PRICE, buyer.getMeso(), "the buyer pays the sticker price");
        assertEquals(TAKEN, owner.getMeso(), "the owner takes the price less the trade fee");
        assertEquals(TAKEN, settledMesos(owner),
                "a bot owner's sale proceeds are its holdings, so they go back to the world when it leaves");
    }

    @Test
    void aPlayerOwnedStallKeepsItsProceedsOutOfTheLedger() {
        Character owner = character(PLAYER_ID);
        Character buyer = character(BOT_BUYER_ID);
        assertTrue(BotHoldings.grantMeso(buyer, 1_000_000));
        PlayerShop shop = stallOf(owner);

        assertTrue(shop.botBuy(buyer, shop.getItems().get(0), 0, (short) 1));

        assertEquals(TAKEN, owner.getMeso(), "a player owner is paid exactly as before, with no ledger in the way");

        MapleMap map = mock(MapleMap.class);
        owner.setMap(map);
        BotHoldings.settle(owner);
        verifyNoInteractions(map);
    }

    @Test
    void aMesoCreditReachingABotWalletIsLedgeredAndAPlayersIsNot() {
        Character bot = character(BOT_OWNER_ID);
        Character player = character(PLAYER_ID);

        // What PlayerShop.buy/botBuy do for their owner: credit the wallet, then report the credit to the ledger.
        bot.gainMeso(TAKEN, false, false, false);
        player.gainMeso(TAKEN, false, false, false);
        BotHoldings.noteMesoEarned(bot, TAKEN);
        BotHoldings.noteMesoEarned(player, TAKEN);

        assertEquals(TAKEN, settledMesos(bot), "the bot's wallet credit is its holdings until it is persisted");

        MapleMap playerMap = mock(MapleMap.class);
        player.setMap(playerMap);
        BotHoldings.settle(player);
        verifyNoInteractions(playerMap);
    }

    @Test
    void aNonPositiveCreditIsIgnored() {
        Character bot = character(BOT_OWNER_ID);

        BotHoldings.noteMesoEarned(bot, 0);
        BotHoldings.noteMesoEarned(bot, -50);

        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotHoldings.settle(bot);
        verifyNoInteractions(map);
    }
}
