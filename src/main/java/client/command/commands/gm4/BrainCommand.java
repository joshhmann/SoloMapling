package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;

import java.util.List;

/**
 * Bot chat LLM check (ticket bot-persistence/12).
 * !brain status       - endpoint, model, free slots, answered / fell-back counts
 * !brain ask <line>   - one reply from a generic player persona, with its latency
 */
public class BrainCommand extends Command {
    {
        setDescription("Bot chat LLM: !brain status, !brain ask <line>");
    }

    private static final String TEST_PERSONA = "You are a MapleStory player in Henesys in 2006. Type like a real "
            + "player: lowercase, short, slang, no period at the end. Reply with one chat line, max 12 words. "
            + "Never mention being an AI.";

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotBrain brain = BotBrains.get();
        if (params.length == 0 || params[0].equalsIgnoreCase("status")) {
            player.yellowMessage("Bot brain: " + brain.describe());
            return;
        }
        if (!params[0].equalsIgnoreCase("ask") || params.length < 2) {
            player.yellowMessage("Usage: !brain status | !brain ask <line>");
            return;
        }
        String line = String.join(" ", List.of(params).subList(1, params.length));
        long started = System.currentTimeMillis();
        brain.complete(player.getId(), List.of(BotBrain.Turn.system(TEST_PERSONA), BotBrain.Turn.user(line)))
                .thenAccept(reply -> player.yellowMessage(reply
                        .map(text -> "[" + (System.currentTimeMillis() - started) + "ms] " + text)
                        .orElse("No reply (scripted fallback) - " + brain.describe())));
    }
}
