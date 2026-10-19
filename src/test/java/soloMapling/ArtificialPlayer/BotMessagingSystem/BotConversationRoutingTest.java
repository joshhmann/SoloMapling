package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BotConversationRoutingTest {

    @Test
    void botsThatReadTypedLinesKeepThemAndTheRestTalk() {
        assertTrue(new TrainingBot(Character.getDefault(new BotClient(0, 0))).consumesFreeText(), "trainers keep their menu");
        assertFalse(new TownWandererBot(Character.getDefault(new BotClient(0, 0))).consumesFreeText(), "wanderers talk");
        assertFalse(new SellingMerchantBot(Character.getDefault(new BotClient(0, 0))).consumesFreeText(),
                "a merchant shows its hint but talks back instead of dropping what it is told");
        assertTrue(Dispatcher.showsOwnHint(mock(SellingMerchantBot.class)));
        assertFalse(Dispatcher.showsOwnHint(mock(TownWandererBot.class)), "a wanderer gets the shared menu window");
    }

    @Test
    void withNoBrainTheCallerAnswersFromItsScript() {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1001);
        // The test config has no LLM endpoint.
        assertFalse(BotConversation.say(mock(TownWandererBot.class), player, "yo", null));
        assertEquals(-1, BotConversation.partnerOf(1001));
        assertFalse(BotConversation.isTalking(20_123, 1001));
    }
}
