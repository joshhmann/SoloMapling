package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lets tests outside the package reach BotMenus' seams. */
public final class BotMenusTestAccess {

    private BotMenusTestAccess() {
    }

    public static void useViewForTest(BotMenus.View view) {
        BotMenus.useViewForTest(view);
    }

    public static String script() {
        return BotMenus.SCRIPT;
    }

    public static BotSM botNamed(String name) {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(20_600);
        chr.setName(name);
        when(bot.getChr()).thenReturn(chr);
        return bot;
    }
}
