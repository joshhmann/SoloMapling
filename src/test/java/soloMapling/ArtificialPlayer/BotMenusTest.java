package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotMenusTest {

    @AfterEach
    void realWindow() {
        BotMenus.useViewForTest(null);
    }

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        return chr;
    }

    private static BotSM bot(String name) {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(20_500, name);
        when(bot.getChr()).thenReturn(chr);
        return bot;
    }

    @Test
    void theTypesOptionsComeFirstThenTheSharedOnesAndAClickRunsTheRightOne() {
        List<List<String>> shown = new ArrayList<>();
        BotMenus.useViewForTest((player, bot, labels) -> shown.add(labels) || true);
        Character player = character(1001, "Josh");
        List<Integer> typeClicks = new ArrayList<>();

        assertTrue(BotMenus.open(bot("Kimchiz"), player, List.of("How's the training?", "Wanna party up?"),
                (index, who) -> typeClicks.add(index)));
        assertEquals(List.of("How's the training?", "Wanna party up?", BotMenus.TALK, BotMenus.WHERE_TRAIN), shown.get(0));
        assertEquals("#bKimchiz#k\r\n\r\n#L0#How's the training?#l\r\n#L1#Wanna party up?#l\r\n#L2#Let's talk#l"
                + "\r\n#L3#Where do you train?#l", BotMenus.text(player));

        BotMenus.select(player, 1);
        assertEquals(List.of(1), typeClicks, "the type's own option runs with its own index");
        BotMenus.select(player, 0);
        assertEquals(List.of(1), typeClicks, "a menu is used once");
    }

    @Test
    void aSharedOptionNeverReachesTheTypesHandlerAndClosingForgetsTheMenu() {
        BotMenus.useViewForTest((player, bot, labels) -> true);
        Character player = character(1002, "Josh");
        List<Integer> typeClicks = new ArrayList<>();
        BotMenus.open(bot("Kimchiz"), player, List.of("Wanna party up?"), (index, who) -> typeClicks.add(index));
        BotMenus.select(player, 1); // "Let's talk": the brain answers (none in the test config), never the type
        assertTrue(typeClicks.isEmpty());

        BotMenus.open(bot("Kimchiz"), player, List.of("Wanna party up?"), (index, who) -> typeClicks.add(index));
        BotMenus.closed(player);
        assertEquals("...", BotMenus.text(player));
    }

    @Test
    void aViewThatCannotShowFallsBackToTheChatHint() {
        BotMenus.useViewForTest((player, bot, labels) -> false);
        assertFalse(BotMenus.open(bot("Kimchiz"), character(1003, "Josh"), List.of("x"), (i, w) -> { }));
    }

    @Test
    void theWindowScriptShowsTheMenuAndReportsTheClick() throws Exception {
        String script = Files.readString(Path.of("scripts/npc/" + BotMenus.SCRIPT + ".js"));
        assertTrue(script.contains("Java.type('soloMapling.ArtificialPlayer.BotMenus')"));
        assertTrue(script.contains("BotMenus.text(cm.getPlayer())"));
        assertTrue(script.contains("BotMenus.select(cm.getPlayer(), selection)"));
        assertTrue(script.contains("BotMenus.closed(cm.getPlayer())"));
    }
}
