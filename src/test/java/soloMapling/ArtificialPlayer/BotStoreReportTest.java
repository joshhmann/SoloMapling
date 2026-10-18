package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Job;
import client.SkinColor;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotStoreReportTest {

    private static final long NOW = 1_700_000_600_000L;

    private static BotStore.Persisted row(int botId) {
        return new BotStore.Persisted(botId, "Recovered", BotTier.B.name(), 57, 4242, 910_000,
                Job.getById(420).getId(), SkinColor.getById(3).getId(), 1, 30_040, 20_010,
                100_000_002, 512, 640, 97, 98, 99, 100, 1_700_000_000_000L, 1_700_000_300_000L);
    }

    private static Character liveFrom(BotStore.Persisted stored) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        stored.applyTo(chr);
        chr.setPosition(new Point(stored.posX, stored.posY));
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(stored.mapId);
        chr.setMap(map);
        return chr;
    }

    @Test
    void aBotThatMatchesItsRowShowsNoDifferences() {
        int slot = BotStore.FIRST_PLANNED_SLOT;
        BotStore.Persisted stored = row(slot);
        Character live = liveFrom(stored);

        List<String> lines = BotStoreReport.describe(stored.charId(), live, stored, true, false, NOW);

        assertTrue(lines.get(0).contains("planned") && lines.get(0).contains("adopted from the store this run"),
                lines.get(0));
        assertTrue(lines.stream().noneMatch(line -> line.contains("*")), String.join("\n", lines));
        assertTrue(lines.get(lines.size() - 1).startsWith("last saved 5m ago"), lines.get(lines.size() - 1));
    }

    @Test
    void unsavedProgressIsMarkedAgainstTheRow() {
        BotStore.Persisted stored = row(BotStore.FIRST_PLANNED_SLOT);
        Character live = liveFrom(stored);
        live.setLevel(58);

        List<String> lines = BotStoreReport.describe(stored.charId(), live, stored, true, false, NOW);

        assertTrue(lines.get(1).contains("Lv* 58 (stored 57)"), lines.get(1));
    }

    @Test
    void anUnsavedBotAndAnOfflineRowAreBothDescribed() {
        BotStore.Persisted stored = row(BotStore.FIRST_SLOT);
        Character live = liveFrom(stored);

        List<String> unsaved = BotStoreReport.describe(stored.charId(), live, null, false, false, NOW);
        assertTrue(unsaved.get(0).contains("on-demand") && unsaved.get(0).contains("minted fresh"), unsaved.get(0));
        assertEquals("No stored row: this bot has not been written yet.", unsaved.get(1));

        List<String> offline = BotStoreReport.describe(stored.charId(), null, stored, false, false, NOW);
        assertTrue(offline.get(0).endsWith("offline"), offline.get(0));
        assertTrue(offline.get(1).startsWith("stored: Lv 57"), offline.get(1));
    }

    @Test
    void ageReadsInTheLargestSensibleUnit() {
        assertEquals("never", BotStoreReport.ago(0, NOW));
        assertEquals("30s ago", BotStoreReport.ago(NOW - 30_000, NOW));
        assertEquals("90m ago", BotStoreReport.ago(NOW - 90 * 60_000L, NOW));
        assertEquals("3d ago", BotStoreReport.ago(NOW - 72 * 3_600_000L, NOW));
    }
}
