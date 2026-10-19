package scripting;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMenus;
import soloMapling.ArtificialPlayer.BotMenusTestAccess;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** bot-persistence ticket 23: botmenu.js runs in Cosmic's own Graal setup and drives BotMenus. */
class BotMenuScriptTest {

    /** The parts of NPCConversationManager the script uses. */
    public static final class FakeCm {
        private final Character player;
        public String sent;
        public boolean disposed;

        FakeCm(Character player) {
            this.player = player;
        }

        public Character getPlayer() {
            return player;
        }

        public void sendSimple(String text) {
            sent = text;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @AfterEach
    void realWindow() {
        BotMenusTestAccess.useViewForTest(null);
    }

    @Test
    void theScriptShowsTheMenuAndAClickReachesTheBotsOption() throws Exception {
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1001);
        List<Integer> clicks = new ArrayList<>();
        BotMenusTestAccess.useViewForTest((p, bot, labels) -> true);
        BotMenus.open(BotMenusTestAccess.botNamed("Kimchiz"), player, List.of("Wanna party up?"),
                (index, who) -> clicks.add(index));

        ScriptEngine engine = new Loader().load("npc/" + BotMenusTestAccess.script() + ".js");
        assertNotNull(engine, "botmenu.js evaluates");
        FakeCm cm = new FakeCm(player);
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;

        script.invokeFunction("start");
        assertTrue(cm.sent.contains("#L0#Wanna party up?#l"), cm.sent);
        script.invokeFunction("action", (byte) 1, (byte) 4, 0);
        assertEquals(List.of(0), clicks);
        assertTrue(cm.disposed);
    }
}
