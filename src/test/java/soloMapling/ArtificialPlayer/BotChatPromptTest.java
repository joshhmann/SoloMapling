package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChatPromptTest {

    private static Character character(String name, int level, int job) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setName(name);
        chr.setLevel(level);
        chr.setJob(Job.getById(job));
        return chr;
    }

    @Test
    void aPersonaIsStableAndSurvivesItsColumn() {
        BotPersona.Persona a = BotPersona.generate(557241);
        assertEquals(a, BotPersona.generate(557241));
        assertEquals(a, BotPersona.parse(a.toJson()));
        assertEquals(2, a.interests().size());
        assertTrue(BotPersona.describe(a, 0.1).contains("a few words"));
        assertTrue(BotPersona.describe(a, 0.9).contains("1-2 short sentences"));
    }

    @Test
    void theSystemPromptCarriesWhoWhereWhoTheyAreTalkingToFactsAndRules() {
        Character bot = character("xXSkyBladeXx", 34, 310);
        Character player = character("Josh", 52, 211);
        String system = BotChatPrompt.system(bot, player, BotPersona.generate(1), 0.5, "grinding",
                List.of("said they were saving for a pink adventurer cape"));

        assertTrue(system.startsWith("You are xXSkyBladeXx, a real person playing MapleStory in 2006."));
        assertTrue(system.contains("level 34"));
        assertTrue(system.contains("currently grinding"));
        assertTrue(system.contains("talking to Josh, a level 52"));
        assertTrue(system.contains("pink adventurer cape"));
        assertTrue(system.contains("Ellinia station"), "world facts are included");
        assertTrue(system.contains("never say you are an AI"));
    }

    @Test
    void theConversationStartsWithTheSystemPromptThenTheHistory() {
        List<BotBrain.Turn> turns = BotChatPrompt.conversation("sys", List.of(BotBrain.Turn.user("yo"),
                BotBrain.Turn.assistant("sup"), BotBrain.Turn.user("nice hat")));
        assertEquals(4, turns.size());
        assertEquals(BotBrain.Role.SYSTEM, turns.get(0).role());
        assertEquals("nice hat", turns.get(3).content());
        assertEquals("hanging out in town", BotChatPrompt.activityPhrase("social"));
    }
}
