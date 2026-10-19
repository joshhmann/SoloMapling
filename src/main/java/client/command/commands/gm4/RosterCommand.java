package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotStoreReport;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * Bot roster read-out (ticket bot-persistence/01).
 * !roster status              - store and online counts
 * !roster status <name|id>    - one bot, live next to its stored row
 * !roster saveall             - write every online bot now (the hourly autosave, on demand)
 * !roster director            - the population director's numbers (ticket 08)
 * !roster curve               - today's online target, hour by hour
 * !roster login|logout <name|id> - log one roster bot in or out now
 * !roster pause|resume        - hold the population still, or let the director run
 */
public class RosterCommand extends Command {
    {
        setDescription("Bot roster: !roster status [name|id], saveall, director, curve, login|logout <bot>, pause|resume");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            String sub = params.length == 0 ? "status" : params[0].toLowerCase();
            switch (sub) {
                case "status" -> {
                    if (params.length < 2) {
                        BotStoreReport.summary(onlineBots()).forEach(player::yellowMessage);
                    } else {
                        showBot(player, params[1]);
                    }
                }
                case "saveall" -> {
                    List<Character> bots = onlineBots();
                    int saved = BotStore.saveAll(bots);
                    player.yellowMessage("Saved " + saved + " of " + bots.size() + " online bots.");
                }
                case "director" -> player.yellowMessage(BotPopulationDirector.describe());
                case "curve" -> {
                    StringBuilder line = new StringBuilder("Target by hour:");
                    BotPopulationDirector.todaysTargets().forEach((hour, target) -> line.append(' ').append(hour)
                            .append('h').append('=').append(target));
                    player.yellowMessage(line.toString());
                }
                case "pause", "resume" -> {
                    BotPopulationDirector.setPaused(sub.equals("pause"));
                    player.yellowMessage(BotPopulationDirector.describe());
                }
                case "login", "logout" -> {
                    if (params.length < 2) {
                        player.yellowMessage("Usage: !roster " + sub + " <name|id>");
                        return;
                    }
                    for (int charId : rosterIds(params[1])) {
                        int slot = charId - BotStore.BOT_ID_BASE;
                        boolean done = sub.equals("login") ? BotPopulationDirector.logIn(slot)
                                : BotPopulationDirector.logOut(slot);
                        player.yellowMessage(sub + " " + charId + ": " + (done ? (sub.equals("logout")
                                ? "leaving (now, or once its commitment ends)" : "done")
                                : sub.equals("login") ? "not logged in (already online, not a member, or no map)"
                                : "not online"));
                    }
                }
                default -> player.yellowMessage("Usage: !roster status [name|id] | saveall | director | curve"
                        + " | login|logout <name|id> | pause|resume");
            }
        });
    }

    private static void showBot(Character player, String target) {
        long now = System.currentTimeMillis();
        if (isInteger(target)) {
            int id = Integer.parseInt(target);
            BotSM live = CharacterStorage.getBotById(id);
            if (live == null && BotStore.persistedFor(id) == null) {
                player.yellowMessage("No online or stored bot with id " + id);
                return;
            }
            BotStoreReport.describe(id, live != null ? live.getChr() : null, now).forEach(player::yellowMessage);
            return;
        }
        // Names are not unique, so every live and stored bot with this name is shown.
        List<Integer> ids = new ArrayList<>(BotStoreReport.storedIdsNamed(target));
        for (Character bot : onlineBots()) {
            if (bot.getName().equalsIgnoreCase(target) && !ids.contains(bot.getId())) {
                ids.add(bot.getId());
            }
        }
        if (ids.isEmpty()) {
            player.yellowMessage("No online or stored bot named " + target);
            return;
        }
        for (int id : ids) {
            BotSM live = CharacterStorage.getBotById(id);
            BotStoreReport.describe(id, live != null ? live.getChr() : null, now).forEach(player::yellowMessage);
        }
    }

    /** Roster members by character id or by name (names are unique for new claims, but old rows may share one). */
    private static List<Integer> rosterIds(String target) {
        List<Integer> ids = new ArrayList<>();
        if (isInteger(target)) {
            int id = Integer.parseInt(target);
            if (BotRoster.isMember(id - BotStore.BOT_ID_BASE)) {
                ids.add(id);
            }
            return ids;
        }
        for (int id : BotStoreReport.storedIdsNamed(target)) {
            if (BotRoster.isMember(id - BotStore.BOT_ID_BASE)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static List<Character> onlineBots() {
        List<Character> bots = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (bot.getChr() != null) {
                bots.add(bot.getChr());
            }
        }
        return bots;
    }
}
