package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.MonsterDropEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Headless tests for the offscreen reward budget's yield math. Offscreen loot must be earned off the map's
// own spawn table, its respawn times, the live occupancy rule and the mobs' real drop tables at the world's
// rates - and one map instance must own exactly one of them, however many bots grind on it.
class OffscreenRewardBudgetTest {

    private static final int MOB = 100100;
    private static final int POTION = 2000000;

    @BeforeEach
    void resetMeters() {
        OffscreenRewardBudget.clearMeters();
    }

    @Test
    void yieldFollowsSpawnSlotsRespawnAndOccupancy() {
        Fake src = spawnTable(List.of(5_000, 5_000),
                List.of(meso(999_999, 100, 100), drop(POTION, 999_999, 1, 1)));

        // 2 spawn slots x (60s window / 5s respawn) = 24 kills, scaled by the live occupancy rule
        // (MapleMap.getCurrentSpawnRate): 1 occupant -> 0.375, 6 occupants -> 0.5.
        OffscreenRewardBudget.Yield solo = OffscreenRewardBudget.windowYield(1, 1, 1, 1, src);
        assertEquals(900.0, solo.mesos(), 1e-6);
        assertEquals(9.0, solo.items().get(POTION), 1e-6);

        OffscreenRewardBudget.Yield crowd = OffscreenRewardBudget.windowYield(1, 6, 1, 1, src);
        assertEquals(1200.0, crowd.mesos(), 1e-6);
        assertEquals(12.0, crowd.items().get(POTION), 1e-6);
    }

    @Test
    void worldDropAndMesoRatesScaleTheYield() {
        // Double drops double the expected meso roll; the meso rate multiplies it; a halved drop chance
        // halves the expectation. No bot-side constant enters any of these numbers.
        Fake doubled = spawnTable(List.of(5_000, 5_000), List.of(meso(999_999, 100, 100)));
        assertEquals(1800.0, OffscreenRewardBudget.windowYield(2, 1, 2, 1, doubled).mesos(), 1e-6);
        assertEquals(4500.0, OffscreenRewardBudget.windowYield(2, 1, 1, 5, doubled).mesos(), 1e-6);

        Fake halfChance = spawnTable(List.of(5_000, 5_000), List.of(meso(500_000, 100, 100)));
        assertEquals(450.0, OffscreenRewardBudget.windowYield(2, 1, 1, 1, halfChance).mesos(), 0.01);
    }

    @Test
    void configRespawnIntervalFloorsFastSpawnPoints() {
        // A 1s mobTime would give 60 kills per slot, but the map's global respawn cycle still gates it:
        // the effective period is max(RESPAWN_INTERVAL, mobTime), so this yields what the 5s slot above did.
        Fake src = spawnTable(List.of(1_000, 1_000), List.of(meso(999_999, 100, 100)));
        assertEquals(900.0, OffscreenRewardBudget.windowYield(3, 1, 1, 1, src).mesos(), 1e-6);
    }

    @Test
    void neverRespawningSpawnPointYieldsNothing() {
        Fake src = spawnTable(List.of(-1, 5_000),
                List.of(meso(999_999, 100, 100), drop(POTION, 999_999, 1, 1)));
        OffscreenRewardBudget.Yield yield = OffscreenRewardBudget.windowYield(4, 1, 1, 1, src);
        assertEquals(450.0, yield.mesos(), 1e-6);
        assertEquals(4.5, yield.items().get(POTION), 1e-6);
    }

    @Test
    void mapItemLimitClampsTheWindowAllowance() {
        Fake src = spawnTable(List.of(5_000, 5_000), List.of(drop(POTION, 999_999, 1, 1)));
        src.itemLimitOnMap = 3;
        assertEquals(3.0, OffscreenRewardBudget.windowYield(5, 1, 1, 1, src).items().get(POTION), 1e-6);
    }

    @Test
    void moblessMapYieldsNothing() {
        Fake src = new Fake();
        assertEquals(0.0, OffscreenRewardBudget.windowYield(100000000, 1, 1, 1, src).mesos(), 1e-9);
        assertTrue(OffscreenRewardBudget.reserve(100000000, 1, 60_000, 60_000, 1, 1, src).items().isEmpty());
    }

    @Test
    void botsOnOneMapShareOneWindowOfYield() {
        Fake src = spawnTable(List.of(5_000, 5_000), List.of(meso(999_999, 100, 100)));

        // 2 occupants -> 0.40 spawn rate -> 960 mesos per 60s of map time. Both bots lived a full window,
        // but the second one only 100ms after the first: it may have that 100ms, not a second window.
        OffscreenRewardBudget.Plan first = OffscreenRewardBudget.reserve(7, 2, 60_000, 60_000, 1, 1, src);
        OffscreenRewardBudget.Plan second = OffscreenRewardBudget.reserve(7, 2, 60_000, 60_100, 1, 1, src);

        assertEquals(960, first.mesos());
        assertTrue(second.mesos() < 16, "second bot minted a second window: " + second.mesos());
        assertEquals(960.0 * 60.1 / 60.0, first.mesos() + second.mesos(), 20.0);
    }

    @Test
    void mapLeftAloneRefillsOnlyOneWindow() {
        Fake src = spawnTable(List.of(5_000, 5_000), List.of(meso(999_999, 100, 100)));

        // Ten minutes of map time bank at most one window, so a dormant map cannot dump a backlog.
        OffscreenRewardBudget.Plan afterDormancy = OffscreenRewardBudget.reserve(8, 1, 60_000, 600_000, 1, 1, src);
        assertEquals(900, afterDormancy.mesos());

        OffscreenRewardBudget.Plan next = OffscreenRewardBudget.reserve(8, 1, 60_000, 600_100, 1, 1, src);
        assertEquals(1, next.mesos());
    }

    @Test
    void repeatedWindowsTrackTheMapRate() {
        Fake src = spawnTable(List.of(10_000, 10_000), List.of(drop(POTION, 999_999, 1, 1)));

        // 12 kills per window -> 4.5 items expected. Whole-item grants may undershoot, never overshoot.
        int total = 0;
        for (int window = 1; window <= 5; window++) {
            OffscreenRewardBudget.Plan plan =
                    OffscreenRewardBudget.reserve(9, 1, 60_000, 60_000L * window, 1, 1, src);
            total += plan.items().getOrDefault(POTION, 0);
        }
        assertTrue(total <= 23, "5 windows of a 4.5/window map yielded " + total);
        assertTrue(total >= 20, "whole-item flooring lost more than one item per window: " + total);
    }

    private static Fake spawnTable(List<Integer> respawnMs, List<MonsterDropEntry> drops) {
        Fake src = new Fake();
        src.mobIds = respawnMs.stream().map(ignored -> MOB).toList();
        src.respawnMs = respawnMs;
        src.drops.put(MOB, drops);
        return src;
    }

    private static MonsterDropEntry meso(int chance, int min, int max) {
        return new MonsterDropEntry(0, chance, min, max, (short) 0);
    }

    private static MonsterDropEntry drop(int itemId, int chance, int min, int max) {
        return new MonsterDropEntry(itemId, chance, min, max, (short) 0);
    }

    private static final class Fake implements OffscreenRewardBudget.Sources {
        Map<Integer, List<MonsterDropEntry>> drops = new HashMap<>();
        List<Integer> mobIds = List.of();
        List<Integer> respawnMs = List.of();
        long respawnIntervalMs = 5_000;
        int itemLimitOnMap = 200;

        @Override
        public List<Integer> mobIds(int mapId) {
            return mobIds;
        }

        @Override
        public List<Integer> respawnMs(int mapId) {
            return respawnMs;
        }

        @Override
        public List<MonsterDropEntry> drops(int mobId) {
            return drops.getOrDefault(mobId, List.of());
        }

        @Override
        public long respawnIntervalMs() {
            return respawnIntervalMs;
        }

        @Override
        public int itemLimitOnMap() {
            return itemLimitOnMap;
        }
    }
}
