package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BotConversationRoutingTest {

    @Test
    void botsWithAMenuKeepItAndBotsWithoutOneTalk() {
        assertTrue(Dispatcher.hasChatMenu(mock(TrainingBot.class)), "trainers keep their numbered menu");
        assertFalse(Dispatcher.hasChatMenu(mock(TownWandererBot.class)), "wanderers have no menu: they just talk");
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
