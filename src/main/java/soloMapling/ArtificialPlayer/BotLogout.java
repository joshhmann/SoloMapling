package soloMapling.ArtificialPlayer;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.server.ExecutorServiceManager;

import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * How a roster bot logs off (bot-persistence ticket 09): never in the middle of something a player is part of.
 * A bot that is free says bye if a real player can see it and goes. A bot holding a commitment - an open trade,
 * an armed invite, a conversation, a party with a player, someone it follows - waits for it to end, up to a cap;
 * at the cap it says bye, leaves the party, closes any trade window (both sides are refunded) and goes, so
 * nothing is left half-open.
 */
public final class BotLogout {

    private static final Logger log = LoggerFactory.getLogger(BotLogout.class);

    static final long WAIT_CAP_MS = TimeUnit.MINUTES.toMillis(15);
    private static final String LINES_FILE = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/LogoutDialogue.yaml";

    /** Bots that want to leave but are holding a commitment: char id -> the time they leave regardless. */
    private static final Map<Integer, Long> leaving = new ConcurrentHashMap<>();
    /** Bots between their goodbye and their removal: a second request in that beat must not log them out twice. */
    private static final java.util.Set<Integer> departing = ConcurrentHashMap.newKeySet();
    private static volatile Map<String, List<String>> lines;

    private BotLogout() {
    }

    /** A bot's session is over: it leaves now if it is free, otherwise as soon as it is (or at the cap). */
    public static void request(int charId, long now) {
        BotSM bot = CharacterStorage.getBotById(charId);
        if (bot == null || bot.getChr() == null) {
            leaving.remove(charId);
            return;
        }
        if (departing.contains(charId)) {
            return;
        }
        if (BotPopulationDirector.mayLogOut(bot)) {
            leaving.remove(charId);
            leave(bot, false);
        } else {
            leaving.putIfAbsent(charId, now + WAIT_CAP_MS);
        }
    }

    /** Whether this bot is waiting to leave, or already on its way out. */
    public static boolean isLeaving(int charId) {
        return leaving.containsKey(charId) || departing.contains(charId);
    }

    public static int waiting() {
        return leaving.size();
    }

    /** One director tick: bots whose commitment ended leave; bots past the cap leave regardless. */
    public static void process(long now) {
        for (Map.Entry<Integer, Long> entry : leaving.entrySet()) {
            int charId = entry.getKey();
            BotSM bot = CharacterStorage.getBotById(charId);
            if (bot == null || bot.getChr() == null) {
                leaving.remove(charId);
            } else if (BotPopulationDirector.mayLogOut(bot)) {
                leaving.remove(charId);
                leave(bot, false);
            } else if (now >= entry.getValue()) {
                leaving.remove(charId);
                leave(bot, true);
            }
        }
    }

    /**
     * Says bye (to a player who can see it), leaves its party, then logs out after a beat so the line is read
     * before the character disappears.
     */
    private static void leave(BotSM bot, boolean forced) {
        Character chr = bot.getChr();
        if (!departing.add(chr.getId())) {
            return;
        }
        boolean partiedWithPlayer = partiedWithPlayer(chr);
        boolean watched = partiedWithPlayer || bot.checkMainPlayersOnMap();
        if (watched) {
            String line = line(partiedWithPlayer ? "Party" : "Alone");
            if (line != null) {
                SocialCommands.BotSpeak(chr, line);
            }
        }
        long beat = watched ? 1500 + ThreadLocalRandom.current().nextLong(1500) : 0;
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                if (chr.getParty() != null) {
                    BotPartyCommands.botLeaveParty(chr);
                }
                if (forced) {
                    BotStore.closeOpenWindow(chr); // refunds both sides; removeBotFromServer would too
                }
                BotRoster.markSessionEnded(chr.getId());
                BotTypeManager.manuallyStopBot(chr);
                BotGeneration.removeBotFromServer(chr);
            } catch (Exception e) {
                log.warn("Roster bot {} could not log out", chr.getName(), e);
            } finally {
                departing.remove(chr.getId());
            }
        }), beat, TimeUnit.MILLISECONDS);
    }

    static boolean partiedWithPlayer(Character chr) {
        Party party = chr == null ? null : chr.getParty();
        if (party == null) {
            return false;
        }
        for (PartyCharacter member : party.getMembers()) {
            if (member != null && !BotHelpers.isArtificial(member.getId())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static String line(String kind) {
        Map<String, List<String>> loaded = lines;
        if (loaded == null) {
            try (FileReader file = new FileReader(LINES_FILE)) {
                loaded = (Map<String, List<String>>) new YamlReader(file).read();
            } catch (Exception e) {
                log.warn("Could not read {}; bots log off silently", LINES_FILE, e);
                loaded = Map.of();
            }
            lines = loaded;
        }
        List<String> pool = loaded.get(kind);
        return pool == null || pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
