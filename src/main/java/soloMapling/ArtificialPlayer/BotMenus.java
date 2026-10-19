package soloMapling.ArtificialPlayer;

import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import scripting.npc.NPCScriptManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * One bot menu, every bot (bot-persistence tickets 21 part 2 and 23): the bot type's own options - a trainer's
 * "How's the training?" / "Wanna party up?", a social bot's "What's up?" - followed by the options every bot shares,
 * "Let's talk" and "Where do you train?". Shown as the client's NPC talk window with clickable options
 * ({@code scripts/npc/botmenu.js}) when {@code BOT_MENU_WINDOW} is on and the player has no other NPC window
 * open; otherwise as the old numbered chat hint, where typing the number works as before.
 *
 * <p>The window is a {@link View}: a custom client can replace it without touching any bot.
 */
public final class BotMenus {

    /** How a menu reaches the player. */
    public interface View {
        /** Shows the menu; returns false when it could not (the caller falls back to the chat hint). */
        boolean show(Character player, BotSM bot, List<String> labels);
    }

    /** The menu a player has open: whose, what it lists, and what each of the type's own options does. */
    record Open(BotSM bot, List<String> labels, int typeOptions, BiConsumer<Integer, Character> onTypeOption) {
    }

    public static final String TALK = "Let's talk";
    public static final String WHERE_TRAIN = "Where do you train?";
    static final List<String> SHARED = List.of(TALK, WHERE_TRAIN);

    /** The NPC whose window frames the menu: Maple Administrator, a neutral face every v83 client has. */
    static final int WINDOW_NPC = 9010000;
    static final String SCRIPT = "botmenu";

    private static final Map<Integer, Open> openByPlayer = new ConcurrentHashMap<>();
    private static volatile View view = BotMenus::showWindow;

    private BotMenus() {
    }

    /**
     * Opens a bot's menu for a player: its own options, then the shared ones.
     *
     * @return false when the window is off or could not open, so the caller shows its chat hint instead
     */
    public static boolean open(BotSM bot, Character player, List<String> typeLabels,
                               BiConsumer<Integer, Character> onTypeOption) {
        if (!windowEnabled() || bot == null || player == null) {
            return false;
        }
        List<String> labels = new ArrayList<>(typeLabels);
        labels.addAll(SHARED);
        openByPlayer.put(player.getId(), new Open(bot, List.copyOf(labels), typeLabels.size(), onTypeOption));
        if (!view.show(player, bot, labels)) {
            openByPlayer.remove(player.getId());
            return false;
        }
        return true;
    }

    /** The window's text: the bot's line, then one clickable option per label. Called by {@code botmenu.js}. */
    public static String text(Character player) {
        Open menu = openByPlayer.get(player.getId());
        if (menu == null) {
            return "...";
        }
        return render(menu.bot().getChr().getName(), menu.labels());
    }

    static String render(String botName, List<String> labels) {
        StringBuilder text = new StringBuilder("#b").append(botName).append("#k\r\n");
        for (int i = 0; i < labels.size(); i++) {
            text.append("\r\n#L").append(i).append('#').append(labels.get(i)).append("#l");
        }
        return text.toString();
    }

    /** A click in the window. Called by {@code botmenu.js}. */
    public static void select(Character player, int index) {
        Open menu = openByPlayer.remove(player.getId());
        if (menu == null || index < 0 || index >= menu.labels().size()) {
            return;
        }
        if (index < menu.typeOptions()) {
            menu.onTypeOption().accept(index, player);
            return;
        }
        String shared = menu.labels().get(index);
        if (shared.equals(TALK)) {
            BotConversation.say(menu.bot(), player, "hey", null);
        } else if (shared.equals(WHERE_TRAIN)) {
            BotConversation.say(menu.bot(), player, "where do u usually train?", null);
        }
    }

    /** The window was closed without a choice. Called by {@code botmenu.js}. */
    public static void closed(Character player) {
        openByPlayer.remove(player.getId());
    }

    static boolean windowEnabled() {
        ServerConfig server = YamlConfig.config.server;
        return server != null && server.BOT_MENU_WINDOW;
    }

    private static boolean showWindow(Character player, BotSM bot, List<String> labels) {
        client.Client c = player.getClient();
        // start() closes an NPC window the player already has open, and during the NPC click cooldown it shows
        // nothing yet reports success - either way the chat hint is the better answer.
        if (c == null || NPCScriptManager.getInstance().getCM(c) != null || !c.canClickNPC()) {
            return false;
        }
        return NPCScriptManager.getInstance().start(c, WINDOW_NPC, SCRIPT, player);
    }

    /** Test seam: a stand-in view (null restores the NPC window). */
    static void useViewForTest(View testView) {
        view = testView == null ? BotMenus::showWindow : testView;
        openByPlayer.clear();
    }
}
