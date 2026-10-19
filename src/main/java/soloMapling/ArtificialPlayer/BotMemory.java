package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a bot remembers about a player (bot-persistence ticket 14): short notes its brain writes when a
 * conversation ends ("said they're saving for a pink adventurer cape"), kept in {@code botmemory} so they survive
 * restarts and logouts, and put back into the prompt the next time the two talk. A pair holds at most
 * {@link #MAX_PER_PAIR} notes; past that the oldest are condensed into one by the brain (or dropped when it is down).
 * The scripted brain writes nothing, so a fallback never invents a memory.
 */
public final class BotMemory {

    private static final Logger log = LoggerFactory.getLogger(BotMemory.class);

    public enum Source { DIRECT, HEARSAY }

    public record Note(long id, int botId, int aboutCharId, String text, Source source, int fromBotId, int hops,
                       long createdAt, long lastUsedAt) {
    }

    static final int MAX_PER_PAIR = 10;
    static final int RECALL = 5;
    static final int CONDENSE = 4;

    static final String SELECT_PAIR = "SELECT * FROM botmemory WHERE BotId = ? AND AboutCharacterId = ? ORDER BY CreatedAt, Id";
    static final String INSERT = "INSERT INTO botmemory (BotId, AboutCharacterId, Note, Source, FromBotId, Hops, CreatedAt, "
            + "LastUsedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    static final String DELETE = "DELETE FROM botmemory WHERE Id = ?";
    static final String TOUCH = "UPDATE botmemory SET LastUsedAt = ? WHERE Id = ?";
    static final String RECENT_OF_BOT = "SELECT * FROM botmemory WHERE BotId = ? AND Hops < ? ORDER BY CreatedAt DESC LIMIT ?";

    /** A note passes through at most this many mouths: what a bot saw, and what it heard from that bot. */
    public static final int MAX_HOPS = 2;

    private static final Map<Long, List<Note>> cache = new ConcurrentHashMap<>();
    private static volatile BotStore.ConnectionSource connectionSource;

    private BotMemory() {
    }

    private static long key(int botId, int charId) {
        return ((long) botId << 32) | (charId & 0xffffffffL);
    }

    /** Every note this bot holds about this player, oldest first. */
    public static List<Note> about(int botId, int charId) {
        return cache.computeIfAbsent(key(botId, charId), k -> load(botId, charId));
    }

    /**
     * The notes to put in the prompt: what the bot saw itself before hearsay, newest first, at most {@link #RECALL}.
     * Recalled notes are stamped as used.
     */
    public static List<String> recall(int botId, int charId, long now) {
        List<Note> notes = new ArrayList<>(about(botId, charId));
        notes.sort(Comparator.comparing((Note n) -> n.source() != Source.DIRECT)
                .thenComparing(Comparator.comparingLong(Note::createdAt).reversed()));
        List<String> recalled = new ArrayList<>();
        for (Note note : notes.subList(0, Math.min(RECALL, notes.size()))) {
            recalled.add(note.source() == Source.HEARSAY ? "heard from " + teller(note.fromBotId()) + ": " + note.text()
                    : note.text());
            touch(note, now);
        }
        return recalled;
    }

    /** Who passed a note on, as the listener knows them: the teller's name, or "someone". */
    private static String teller(int fromBotId) {
        try {
            BotStore.Persisted row = BotStore.persistedFor(BotStore.BOT_ID_BASE + fromBotId);
            return row == null ? "someone" : row.name;
        } catch (RuntimeException e) {
            return "someone"; // the store is unreadable: the note still counts, its teller is just unnamed
        }
    }

    /** The newest notes a bot holds about anyone, that can still be passed on (ticket 15). */
    public static List<Note> recentPassable(int botId, int limit) {
        List<Note> notes = new ArrayList<>();
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(RECENT_OF_BOT)) {
            ps.setInt(1, botId);
            ps.setInt(2, MAX_HOPS);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.debug("Could not read the notes of bot {}", botId, e);
        }
        return notes;
    }

    /**
     * Bot {@code listener} hears a note from {@code teller} (ticket 15): it keeps it as hearsay, one hop further,
     * unless it already holds that note, the note is about to exceed {@link #MAX_HOPS}, or it is the listener's own.
     *
     * @return whether the listener kept it
     */
    public static boolean hear(int listener, Note heard, long now) {
        int hops = heard.hops() + 1;
        if (listener == heard.botId() || hops > MAX_HOPS || listener == heard.fromBotId()) {
            return false;
        }
        for (Note known : about(listener, heard.aboutCharId())) {
            if (known.text().equalsIgnoreCase(heard.text())) {
                return false;
            }
        }
        remember(listener, heard.aboutCharId(), List.of(heard.text()), Source.HEARSAY, heard.botId(), hops, now);
        return true;
    }

    /** Stores new notes about a player, condensing the oldest when the pair is full. */
    public static void remember(int botId, int charId, List<String> texts, Source source, int fromBotId, int hops,
                                long now) {
        List<Note> pair = new ArrayList<>(about(botId, charId));
        for (String text : texts) {
            String clean = text == null ? "" : text.trim();
            if (clean.isEmpty()) {
                continue;
            }
            Note stored = insert(new Note(0, botId, charId, clean.length() > 160 ? clean.substring(0, 160) : clean,
                    source, fromBotId, hops, now, 0));
            if (stored != null) {
                pair.add(stored);
            }
        }
        cache.put(key(botId, charId), pair);
        if (pair.size() > MAX_PER_PAIR) {
            condense(botId, charId, now);
        }
    }

    /**
     * A conversation ended: asks the brain for notes about the player and keeps them. Runs on the brain's own
     * threads; nothing waits on it.
     */
    public static void writeConversationNotes(client.Character bot, client.Character player, List<BotBrain.Turn> transcript) {
        boolean botSpoke = transcript.stream().anyMatch(t -> t.role() == BotBrain.Role.ASSISTANT);
        if (!botSpoke || !BotBrains.get().isLive()) {
            return;
        }
        int botId = bot.getId() - BotStore.BOT_ID_BASE;
        int charId = player.getId();
        BotBrains.get().notes(notesPrompt(bot.getName(), player.getName(), transcript)).thenAccept(reply -> reply
                .ifPresent(text -> remember(botId, charId, List.of(text.split("\n")), Source.DIRECT, 0, 0,
                        System.currentTimeMillis())));
    }

    public static List<BotBrain.Turn> notesPrompt(String botName, String playerName, List<BotBrain.Turn> transcript) {
        StringBuilder chat = new StringBuilder();
        for (BotBrain.Turn turn : transcript) {
            if (turn.role() == BotBrain.Role.SYSTEM) {
                continue;
            }
            chat.append(turn.role() == BotBrain.Role.USER ? playerName : "You").append(": ").append(turn.content())
                    .append('\n');
        }
        String system = "You are " + botName + ", a MapleStory player. Below is a chat you just had with " + playerName
                + ". Write at most 2 short notes, each under 140 characters, about things worth remembering about "
                + playerName + " next time: their plans and goals, items, level or job news, what they like, promises "
                + "either of you made, how they treated you. Write each note on its own line from your point of view, "
                + "for example: said they're saving up for a pink adventurer cape. Use only what was said in the chat. "
                + "If nothing is worth remembering, write NONE.";
        return List.of(BotBrain.Turn.system(system), BotBrain.Turn.user(chat.toString().trim()));
    }

    /** Merges the oldest notes of a full pair into one, or drops them when the brain cannot help. */
    private static void condense(int botId, int charId, long now) {
        List<Note> pair = about(botId, charId);
        List<Note> oldest = new ArrayList<>(pair.subList(0, Math.min(CONDENSE, pair.size())));
        StringBuilder joined = new StringBuilder();
        for (Note note : oldest) {
            joined.append("- ").append(note.text()).append('\n');
        }
        List<BotBrain.Turn> prompt = List.of(BotBrain.Turn.system("Merge these notes about a player into ONE note "
                        + "under 140 characters that keeps what matters most. Write only the note."),
                BotBrain.Turn.user(joined.toString().trim()));
        BotBrains.get().notes(prompt).thenAccept(reply -> {
            for (Note note : oldest) {
                delete(note);
            }
            List<Note> rest = new ArrayList<>(about(botId, charId));
            rest.removeAll(oldest);
            cache.put(key(botId, charId), rest);
            reply.ifPresent(merged -> remember(botId, charId, List.of(merged.split("\n")[0]), Source.DIRECT, 0, 0,
                    oldest.get(oldest.size() - 1).createdAt()));
        });
    }

    // ── storage ─────────────────────────────────────────────────────────────────────────────────

    private static List<Note> load(int botId, int charId) {
        List<Note> notes = new ArrayList<>();
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(SELECT_PAIR)) {
            ps.setInt(1, botId);
            ps.setInt(2, charId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read what bot {} remembers about {}", botId, charId, e);
        }
        return notes;
    }

    private static Note read(ResultSet rs) throws SQLException {
        return new Note(rs.getLong("Id"), rs.getInt("BotId"), rs.getInt("AboutCharacterId"), rs.getString("Note"),
                Source.valueOf(rs.getString("Source")), rs.getInt("FromBotId"), rs.getInt("Hops"),
                rs.getLong("CreatedAt"), rs.getLong("LastUsedAt"));
    }

    private static Note insert(Note note) {
        try (Connection con = connections().get();
             PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, note.botId());
            ps.setInt(2, note.aboutCharId());
            ps.setString(3, note.text());
            ps.setString(4, note.source().name());
            ps.setInt(5, note.fromBotId());
            ps.setInt(6, note.hops());
            ps.setLong(7, note.createdAt());
            ps.setLong(8, note.lastUsedAt());
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new Note(id, note.botId(), note.aboutCharId(), note.text(), note.source(), note.fromBotId(),
                    note.hops(), note.createdAt(), note.lastUsedAt());
        } catch (SQLException e) {
            log.warn("Could not store a memory of bot {}", note.botId(), e);
            return null;
        }
    }

    private static void delete(Note note) {
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(DELETE)) {
            ps.setLong(1, note.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not forget memory {} of bot {}", note.id(), note.botId(), e);
        }
    }

    private static void touch(Note note, long now) {
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(TOUCH)) {
                ps.setLong(1, now);
                ps.setLong(2, note.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.debug("Could not stamp memory {} as used", note.id(), e);
            }
        });
    }

    private static BotStore.ConnectionSource connections() {
        BotStore.ConnectionSource source = connectionSource;
        return source != null ? source : DatabaseConnection::getConnection;
    }

    /** Test seam: a mock pool (null restores the real one); forgets every cached note. */
    static void useConnectionSourceForTest(BotStore.ConnectionSource source) {
        connectionSource = source;
        cache.clear();
    }
}
