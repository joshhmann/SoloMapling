package soloMapling.ArtificialPlayer;

import client.Character;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * A free-form chat between one bot and one player through the bot's brain (bot-persistence ticket 21): the
 * same engine for every bot type. It holds the last turns, builds the prompt from the bot's persona, live state and
 * what it remembers about the player, types the reply at a human pace, and when the chat ends - by goodbye or by
 * a minute of silence - hands the transcript to {@link BotMemory} for notes.
 *
 * <p>SocialBot runs its conversations through here; bots with a numbered menu send it the lines that are not a
 * menu choice; bots with no menu at all get it straight from the dispatcher.
 */
public final class BotConversation {

    static final int HISTORY_TURNS = 8;
    static final long IDLE_MS = TimeUnit.SECONDS.toMillis(60);

    private record Key(int botId, int playerId) {
    }

    private static final class Session {
        final BotSM bot;
        final Character player;
        final List<BotBrain.Turn> history = new ArrayList<>();
        volatile long lastAt;

        Session(BotSM bot, Character player, long now) {
            this.bot = bot;
            this.player = player;
            this.lastAt = now;
        }
    }

    private static final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private static volatile boolean sweeperStarted;

    private BotConversation() {
    }

    /**
     * The player said {@code line} to the bot. The reply arrives later (or not: {@code fallback} runs instead when
     * the brain does not answer - the caller's scripted line, or nothing). Returns false when the brain is not
     * live, so the caller can answer from its script straight away.
     */
    public static boolean say(BotSM bot, Character player, String line, Runnable fallback) {
        if (bot == null || bot.getChr() == null || player == null || !BotBrains.get().isLive()) {
            return false;
        }
        startSweeper();
        long now = System.currentTimeMillis();
        Session session = sessions.computeIfAbsent(new Key(bot.getChr().getId(), player.getId()),
                k -> new Session(bot, player, now));
        session.lastAt = now;
        List<BotBrain.Turn> history;
        synchronized (session.history) {
            session.history.add(BotBrain.Turn.user(line));
            while (session.history.size() > HISTORY_TURNS) {
                session.history.remove(0);
            }
            history = new ArrayList<>(session.history);
        }
        // Off the caller's thread: the first recall of a pair reads the database.
        ExecutorServiceManager.runAsync(() -> ask(session, history, now, fallback));
        return true;
    }

    /** Whether this player is mid-chat with this bot. */
    public static boolean isTalking(int botCharId, int playerId) {
        Session session = sessions.get(new Key(botCharId, playerId));
        return session != null && System.currentTimeMillis() - session.lastAt <= IDLE_MS;
    }

    /** The bot this player is mid-chat with, or -1 (the most recent one, if several). */
    public static int partnerOf(int playerId) {
        long now = System.currentTimeMillis();
        Session latest = null;
        for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
            Session s = entry.getValue();
            if (entry.getKey().playerId() == playerId && now - s.lastAt <= IDLE_MS
                    && (latest == null || s.lastAt > latest.lastAt)) {
                latest = s;
            }
        }
        return latest == null ? -1 : latest.bot.getChr().getId();
    }

    /** The chat is over: forget it, and let the bot write down what it wants to remember. */
    public static void end(int botCharId, int playerId) {
        Session session = sessions.remove(new Key(botCharId, playerId));
        if (session == null) {
            return;
        }
        List<BotBrain.Turn> transcript;
        synchronized (session.history) {
            transcript = new ArrayList<>(session.history);
        }
        if (!transcript.isEmpty()) {
            BotMemory.writeConversationNotes(session.bot.getChr(), session.player, transcript);
        }
    }

    /** Ends every chat of a bot that is leaving the world. */
    public static void endAllOf(int botCharId) {
        for (Key key : new ArrayList<>(sessions.keySet())) {
            if (key.botId() == botCharId) {
                end(key.botId(), key.playerId());
            }
        }
    }

    private static void ask(Session session, List<BotBrain.Turn> history, long asked, Runnable fallback) {
        Character bot = session.bot.getChr();
        Character player = session.player;
        int slot = bot.getId() - BotStore.BOT_ID_BASE;
        BotRoster.Member member = BotRoster.member(slot);
        BotPersona.Persona persona = member == null ? null : member.personaOrNull();
        if (persona == null) {
            persona = BotPersona.generate(slot); // staff and on-demand bots get a stable persona too
        }
        BotPersonality.Traits traits = member == null ? null : member.traits();
        BotRoster.Activity doing = session.bot.activitySnapshot();
        String system = BotChatPrompt.system(bot, player, persona, traits == null ? 0.4 : traits.chattiness(),
                BotChatPrompt.activityPhrase(doing == null ? null : doing.name()),
                BotMemory.recall(slot, player.getId(), asked));
        BotBrains.get().complete(player.getId(), BotChatPrompt.conversation(system, history)).thenAccept(reply -> {
            if (!sessions.containsKey(new Key(bot.getId(), player.getId()))) {
                return; // the chat ended while the brain was thinking
            }
            if (reply.isEmpty()) {
                if (fallback != null) {
                    fallback.run();
                }
                return;
            }
            String line = reply.get();
            synchronized (session.history) {
                session.history.add(BotBrain.Turn.assistant(line));
            }
            // Type at a human pace: about 55 ms a character, minus the time the brain already took.
            long typing = Math.max(300, Math.min(5000, 1200 + 55L * line.length() - (System.currentTimeMillis() - asked)));
            BotTiming.chain()
                    .stopUnless(() -> sessions.containsKey(new Key(bot.getId(), player.getId())))
                    .pause(typing)
                    .run(() -> botFaceTowardsPoint(bot, player.getPosition()))
                    .run(() -> {
                        BotSpeak(bot, line);
                        session.lastAt = System.currentTimeMillis();
                    })
                    .start();
        });
    }

    private static void startSweeper() {
        if (sweeperStarted) {
            return;
        }
        synchronized (BotConversation.class) {
            if (sweeperStarted) {
                return;
            }
            sweeperStarted = true;
            ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                for (Map.Entry<Key, Session> entry : sessions.entrySet()) {
                    if (now - entry.getValue().lastAt > IDLE_MS) {
                        end(entry.getKey().botId(), entry.getKey().playerId());
                    }
                }
            }, 10, 10, TimeUnit.SECONDS);
        }
    }
}
