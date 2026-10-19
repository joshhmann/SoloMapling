package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Bots talk about players behind their backs (bot-persistence ticket 15). Every minute a few online bots each
 * recall one note about a player and, with some chance, pass it to another bot standing on the same map, which
 * keeps it as hearsay ("heard from Nayrb: said they're saving for a pink cape"). No brain call - a copy with
 * attribution - and a note goes at most {@link BotMemory#MAX_HOPS} mouths deep, so a story spreads around a town
 * without reaching every bot in the world.
 */
public final class BotGossip {

    static final long PERIOD_MS = TimeUnit.MINUTES.toMillis(1);
    /** Bots that may gossip in one step. */
    static final int TELLERS_PER_STEP = 20;
    /** The chance a chosen teller actually passes its note on. */
    static final double PASS_CHANCE = 0.3;

    private static volatile long lastStepAt;

    private BotGossip() {
    }

    /** Runs at most once a minute, from the director's tick. */
    static void maybeStep(long now) {
        if (now - lastStepAt < PERIOD_MS) {
            return;
        }
        lastStepAt = now;
        Map<Integer, List<Character>> byMap = new java.util.HashMap<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr != null && chr.getMap() != null) {
                byMap.computeIfAbsent(chr.getMapId(), k -> new ArrayList<>()).add(chr);
            }
        }
        step(byMap, now, ThreadLocalRandom.current());
    }

    /** One round over bots grouped by map; returns how many notes changed hands. */
    static int step(Map<Integer, List<Character>> botsByMap, long now, Random rng) {
        List<Character[]> pairs = new ArrayList<>();
        for (List<Character> here : botsByMap.values()) {
            if (here.size() < 2) {
                continue;
            }
            for (Character teller : here) {
                Character listener;
                do {
                    listener = here.get(rng.nextInt(here.size()));
                } while (listener == teller);
                pairs.add(new Character[]{teller, listener});
            }
        }
        java.util.Collections.shuffle(pairs, rng);
        int passed = 0;
        for (Character[] pair : pairs.subList(0, Math.min(TELLERS_PER_STEP, pairs.size()))) {
            if (rng.nextDouble() >= PASS_CHANCE) {
                continue;
            }
            int teller = pair[0].getId() - BotStore.BOT_ID_BASE;
            List<BotMemory.Note> notes = BotMemory.recentPassable(teller, 5);
            if (notes.isEmpty()) {
                continue;
            }
            BotMemory.Note note = notes.get(rng.nextInt(notes.size()));
            if (BotMemory.hear(pair[1].getId() - BotStore.BOT_ID_BASE, note, now)) {
                passed++;
            }
        }
        return passed;
    }
}
