package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import soloMapling.ArtificialPlayer.BotHoldings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/*
 * Rule: a bot never travels out of an open trade window. GCTravel asks this helper to close the window
 * before it enables movement, so the trip never starts with a trade that would freeze the driver. The
 * close goes through Trade.cancelTrade(PARTNER_CANCEL) - the same path BotStore.closeOpenWindow uses -
 * which refunds both sides, and the trip itself is not refused.
 */
class BotTravelTradeAbortTest {

    private static Character newBot() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(21000); // Trade's bot branches key on BotHelpers.isArtificial: any id above 20000
        return bot;
    }

    @Test
    void anOpenTradeIsCancelledWithPartnerCancelExactlyOnce() {
        Character bot = mock(Character.class);
        when(bot.getTrade()).thenReturn(mock(Trade.class));

        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            assertTrue(BotTradeCommands.abortOpenTradeForTravel(bot),
                    "the caller must learn that a window was closed");
            trades.verify(() -> Trade.cancelTrade(bot, Trade.TradeResult.PARTNER_CANCEL), times(1));
            trades.verifyNoMoreInteractions();
        }
    }

    @Test
    void noTradeMeansNoInteractionAndFalse() {
        Character bot = mock(Character.class);

        assertFalse(BotTradeCommands.abortOpenTradeForTravel(bot));
        verify(bot, times(1)).getTrade();
        verifyNoMoreInteractions(bot);
    }

    @Test
    void aRealWindowIsClosedAndTheOfferedMesosComeBack() {
        Character bot = newBot();
        assertTrue(BotHoldings.grantMeso(bot, 1_000));
        Trade.startTrade(bot);
        BotTradeCommands.setMeso(bot, 400);
        assertEquals(600, bot.getMeso(), "offered mesos leave the bot's wallet while they are posted");

        assertTrue(BotTradeCommands.abortOpenTradeForTravel(bot));

        assertNull(bot.getTrade(), "travel must not start with a window still open");
        assertEquals(1_000, bot.getMeso(), "the cancel path refunds the offered mesos");
        assertFalse(BotTradeCommands.abortOpenTradeForTravel(bot),
                "calling again after the window is gone is a no-op");
        assertEquals(1_000, bot.getMeso(), "and must not refund a second time");
    }
}
