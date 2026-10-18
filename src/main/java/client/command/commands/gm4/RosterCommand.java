package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
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
 */
public class RosterCommand extends Command {
    {
        setDescription("Bot roster: !roster status [name|id], !roster saveall");
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
                default -> player.yellowMessage("Usage: !roster status [name|id] | !roster saveall");
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
