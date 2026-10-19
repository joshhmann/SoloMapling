package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotMemoryTest {

    /** The botmemory table, as rows of column -> value. */
    private final List<Map<String, Object>> table = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @BeforeEach
    void fakeTable() {
        BotMemory.useConnectionSourceForTest(() -> {
            Connection con = mock(Connection.class);
            when(con.prepareStatement(anyString())).thenAnswer(c -> statement(c.getArgument(0)));
            when(con.prepareStatement(anyString(), anyInt())).thenAnswer(c -> statement(c.getArgument(0)));
            return con;
        });
    }

    @AfterEach
    void realPool() {
        BotMemory.useConnectionSourceForTest(null);
    }

    private PreparedStatement statement(String sql) throws Exception {
        Map<Integer, Object> bound = new HashMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setInt(anyInt(), anyInt());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setLong(anyInt(), anyLong());
        doAnswer(c -> bound.put(c.getArgument(0), c.getArgument(1))).when(ps).setString(anyInt(), anyString());
        long[] lastId = {0};
        when(ps.executeUpdate()).thenAnswer(c -> {
            if (sql.equals(BotMemory.INSERT)) {
                Map<String, Object> row = new HashMap<>();
                row.put("Id", lastId[0] = nextId.getAndIncrement());
                row.put("BotId", bound.get(1));
                row.put("AboutCharacterId", bound.get(2));
                row.put("Note", bound.get(3));
                row.put("Source", bound.get(4));
                row.put("FromBotId", bound.get(5));
                row.put("Hops", bound.get(6));
                row.put("CreatedAt", bound.get(7));
                row.put("LastUsedAt", bound.get(8));
                table.add(row);
            } else if (sql.equals(BotMemory.DELETE)) {
                table.removeIf(r -> r.get("Id").equals(bound.get(1)));
            }
            return 1;
        });
        when(ps.getGeneratedKeys()).thenAnswer(c -> {
            ResultSet keys = mock(ResultSet.class);
            boolean[] given = {false};
            when(keys.next()).thenAnswer(n -> !given[0] && (given[0] = true));
            when(keys.getLong(1)).thenAnswer(n -> lastId[0]);
            return keys;
        });
        when(ps.executeQuery()).thenAnswer(c -> {
            Iterator<Map<String, Object>> rows = table.stream()
                    .filter(r -> r.get("BotId").equals(bound.get(1)) && r.get("AboutCharacterId").equals(bound.get(2)))
                    .toList().iterator();
            ResultSet rs = mock(ResultSet.class);
            Map<String, Object>[] current = new Map[1];
            when(rs.next()).thenAnswer(n -> rows.hasNext() && (current[0] = rows.next()) != null);
            when(rs.getLong(anyString())).thenAnswer(n -> ((Number) current[0].get(n.getArgument(0))).longValue());
            when(rs.getInt(anyString())).thenAnswer(n -> ((Number) current[0].get(n.getArgument(0))).intValue());
            when(rs.getString(anyString())).thenAnswer(n -> (String) current[0].get(n.getArgument(0)));
            return rs;
        });
        return ps;
    }

    @Test
    void notesSurviveTheCacheAndComeBackWhatTheBotSawFirstNewestFirst() {
        BotMemory.remember(7, 1001, List.of("said they main a fire/poison mage"), BotMemory.Source.DIRECT, 0, 0, 100);
        BotMemory.remember(7, 1001, List.of("heard they got scammed in the FM"), BotMemory.Source.HEARSAY, 9, 1, 300);
        BotMemory.remember(7, 1001, List.of("saving up for a pink adventurer cape"), BotMemory.Source.DIRECT, 0, 0, 200);
        BotMemory.useConnectionSourceForTest(null); // forget the cache ...
        fakeTable();                                 // ... and read the "database" back, like after a restart

        List<String> recalled = BotMemory.recall(7, 1001, 400);
        assertEquals(List.of("saving up for a pink adventurer cape", "said they main a fire/poison mage",
                "heard from another player: heard they got scammed in the FM"), recalled);
        assertEquals(List.of(), BotMemory.recall(7, 2002, 400), "notes belong to one pair");
    }

    @Test
    void aFullPairDropsItsOldestWhenTheBrainCannotCondense() {
        for (int i = 0; i < BotMemory.MAX_PER_PAIR + 1; i++) {
            BotMemory.remember(3, 1001, List.of("note " + i), BotMemory.Source.DIRECT, 0, 0, i);
        }
        // The test config has no LLM endpoint, so the condense step can only drop the oldest four.
        List<BotMemory.Note> left = BotMemory.about(3, 1001);
        assertEquals(BotMemory.MAX_PER_PAIR + 1 - BotMemory.CONDENSE, left.size());
        assertEquals("note 4", left.get(0).text());
        assertEquals(left.size(), table.size());
    }

    @Test
    void theScriptedBrainNeverInventsAMemory() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BotStore.BOT_ID_BASE + 7);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1001);
        BotMemory.writeConversationNotes(bot, player, List.of(BotBrain.Turn.user("im saving for a cape"),
                BotBrain.Turn.assistant("nice gl")));
        assertTrue(table.isEmpty());
    }

    @Test
    void theNotesPromptShowsTheChatFromTheBotsSide() {
        List<BotBrain.Turn> prompt = BotMemory.notesPrompt("Nayrb", "Josh", List.of(BotBrain.Turn.system("persona"),
                BotBrain.Turn.user("im saving for a cape"), BotBrain.Turn.assistant("nice gl")));
        assertEquals(2, prompt.size());
        assertTrue(prompt.get(0).content().contains("If nothing is worth remembering, write NONE"));
        assertEquals("Josh: im saving for a cape\nYou: nice gl", prompt.get(1).content());
    }
}
