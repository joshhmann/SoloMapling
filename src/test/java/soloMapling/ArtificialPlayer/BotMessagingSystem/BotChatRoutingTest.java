package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChatRoutingTest {

    private static ChatMessage line(int senderId, String text) {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(senderId);
        return new ChatMessage(player, text);
    }

    @Test
    void aYesOrTheBotsNameConfirmsAndAnythingElseDoesNot() {
        long now = 1_000;
        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertTrue(BotChatRouting.confirmation(line(7, "ya"), "Ressurectme", now + 2_000).isPresent());

        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertTrue(BotChatRouting.confirmation(line(7, "yes u!!"), "Ressurectme", now + 2_000).isPresent());

        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertTrue(BotChatRouting.confirmation(line(7, "ressurect"), "Ressurectme", now + 2_000).isPresent());

        BotChatRouting.askFirst(20_501, line(7, "hey resme"), now);
        assertFalse(BotChatRouting.confirmation(line(7, "nah not u"), "Ressurectme", now + 2_000).isPresent());
        assertEquals(null, BotChatRouting.pendingFor(7), "a question is answered once");

        BotChatRouting.askFirst(20_501, line(8, "hey resme"), now);
        assertFalse(BotChatRouting.confirmation(line(8, "ya"), "Ressurectme",
                now + BotChatRouting.CONFIRM_MS + 1).isPresent(), "too late");
    }

    @Test
    void aShortAcceptanceAnswersTheBotThatJustAskedAndChatterDoesNot() {
        BotChatRouting.LastSpeaker partyCall = new BotChatRouting.LastSpeaker(20_100, "xXSkyBladeXx",
                "anyone wanna party for ant tunnel", 0);
        for (String yes : List.of("ill come", "sure count me in", "inv me pls", "me!", "i'm in")) {
            assertEquals(Optional.of(20_100), BotChatRouting.replyToLastSpeaker(yes, partyCall), yes);
        }
        for (String no : List.of("brb mom says dinner", "lol", "selling 60% glove att 3m",
                "sure but first i need to go sell all my etc items in henesys")) {
            assertEquals(Optional.empty(), BotChatRouting.replyToLastSpeaker(no, partyCall), no);
        }
        BotChatRouting.LastSpeaker statement = new BotChatRouting.LastSpeaker(20_100, "xXSkyBladeXx", "nice weather", 0);
        assertEquals(Optional.empty(), BotChatRouting.replyToLastSpeaker("ok", statement), "nothing was asked");
        assertEquals(Optional.empty(), BotChatRouting.replyToLastSpeaker("ill come", null));
    }

    @Test
    void buffRequestsAreLeftToTheBuffHandlerAndTradeTalkFindsNobody() {
        assertTrue(soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.isBuffRequest("hs pls"));
        List<BotAddressee.Candidate> nearby = List.of(new BotAddressee.Candidate(20_100, "xXSkyBladeXx", 310),
                new BotAddressee.Candidate(20_101, "Kimchiz", 231));
        assertEquals(Optional.empty(), BotChatRouting.decideByJob("selling claws, anyone?", nearby));
        assertEquals(Optional.empty(), BotChatRouting.decideByJob("nice", nearby), "not a request");
    }

    @Test
    void jobFamiliesSplitClericsFromOtherMagicians() {
        assertEquals(BotChatRouting.JobFamily.CLERIC, BotChatRouting.JobFamily.of(231));
        assertEquals(BotChatRouting.JobFamily.MAGICIAN, BotChatRouting.JobFamily.of(211));
        assertEquals(BotChatRouting.JobFamily.BOWMAN, BotChatRouting.JobFamily.of(310));
        assertEquals(BotChatRouting.JobFamily.THIEF, BotChatRouting.JobFamily.of(420));
        assertEquals(null, BotChatRouting.JobFamily.of(0), "beginners are no kind");
    }

    @Test
    void theJobRequestIsCleanAndItsAnswerIsReadBack() {
        String json = Json.write(BotChatRouting.jobRequest("can i get a bless (pls) :)"));
        assertFalse(json.contains("(pls)") || json.contains(":)"), json);
        assertTrue(json.contains("\"cleric\""));
        assertEquals(BotChatRouting.JobFamily.CLERIC, BotChatRouting.parseJob(
                "{\"answers\":{\"need\":{\"choice\":\"cleric\",\"confidence\":0.6}}}").orElseThrow().family());
        assertEquals(Optional.empty(), BotChatRouting.parseJob(
                "{\"answers\":{\"need\":{\"choice\":\"any\",\"confidence\":0.9}}}"));
    }
}
