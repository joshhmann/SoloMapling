package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import net.server.world.World;
import server.ItemInformationProvider;
import server.life.MonsterDropEntry;
import server.life.MonsterInformationProvider;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Hunting-capacity budget for tradable rewards earned while a bot's map is unobserved.
//
// Bots nobody is watching never fight a real mob, so their loot cannot come from a kill. Instead of letting
// each offscreen bot mint its own loot at an invented rate, this class computes what the map instance
// itself could yield over the elapsed window - from the map's WZ spawn table, each spawn point's respawn
// time, the live occupancy rule (MapleMap.getCurrentSpawnRate) and the mobs' real drop tables at the
// world's current drop/meso rates - and never hands out more than that. One budget per map is shared by every
// bot grinding there (meters are keyed by map id, so channels of the same map share one meter, which
// under-produces rather than over-produces), so N bots cannot produce N maps' worth of loot.
//
// The meter is a token bucket per map: yield accrues at the map's expected rate, the bucket holds at most
// one window of it, and a draw can never take more than the window the bot just lived through. Rates come
// from the existing drop tables and world rates, never from a bot-side constant.
public final class OffscreenRewardBudget {

    // One budget window. Matches the floor of TrainingBot's offscreen GRIND cadence (60-120s), so a bot
    // normally sees one window per tick and the bucket cap never bites.
    public static final long WINDOW_MS = 60_000;

    // Bounds a single draw so a long-dormant map cannot dump its whole backlog at once.
    private static final int MAX_ITEMS_PER_DRAW = 20;

    // A bounded share of a map instance's expected yield. Empty when the bot is owed nothing yet.
    public record Reward(long mesos, List<Item> items) {
        public static final Reward NONE = new Reward(0, List.of());

        public boolean isEmpty() {
            return mesos <= 0 && items.isEmpty();
        }
    }

    // What reserve() decided to hand out, before Item objects are materialized.
    record Plan(long mesos, Map<Integer, Integer> items) {
    }

    // Expected yield of one map instance over one window. Fractional, so tests can see sub-1 allowances.
    record Yield(double mesos, Map<Integer, Double> items) {
    }

    // Where the rate model reads its inputs. The live implementation reads WZ spawn/drop data and the
    // server's respawn/drop-limit config; tests substitute one so the yield math runs without a loaded
    // data set or a config file.
    interface Sources {
        List<Integer> mobIds(int mapId);

        List<Integer> respawnMs(int mapId);

        List<MonsterDropEntry> drops(int mobId);

        long respawnIntervalMs();

        int itemLimitOnMap();
    }

    private static final Sources WZ = new Sources() {
        @Override
        public List<Integer> mobIds(int mapId) {
            return MapMobIndex.mobIds(mapId);
        }

        @Override
        public List<Integer> respawnMs(int mapId) {
            return MapMobIndex.respawnMs(mapId);
        }

        @Override
        public List<MonsterDropEntry> drops(int mobId) {
            List<MonsterDropEntry> drops = MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mobId);
            return drops == null ? List.of() : drops;
        }

        @Override
        public long respawnIntervalMs() {
            return YamlConfig.config.server.RESPAWN_INTERVAL;
        }

        @Override
        public int itemLimitOnMap() {
            return YamlConfig.config.server.ITEM_LIMIT_ON_MAP;
        }
    };

    // Per-map accrual state. Guarded by its own monitor; every draw for a map serializes on it.
    private static final class Meter {
        double mesos;
        long lastAccrualMs;
        final Map<Integer, Double> items = new HashMap<>();
    }

    // Bounded by the number of maps in the game (one entry per map with offscreen grinders).
    private static final Map<Integer, Meter> METERS = new ConcurrentHashMap<>();

    private OffscreenRewardBudget() {
    }

    // Draw this bot's bounded share of its map instance's offscreen yield for the window it just lived
    // through. Tokens are consumed here: a bot whose inventory cannot hold its share forfeits it rather
    // than banking it, which keeps the map's yield an upper bound even when nobody can carry the loot.
    public static Reward draw(int mapId, int occupants, long elapsedMs, long nowMs, World world) {
        if (world == null) {
            return Reward.NONE;
        }
        Plan plan = reserve(mapId, occupants, elapsedMs, nowMs, Math.max(1, world.getDropRate()),
                Math.max(1, world.getMesoRate()), WZ);
        if (plan.mesos() <= 0 && plan.items().isEmpty()) {
            return Reward.NONE;
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : plan.items().entrySet()) {
            appendItems(items, entry.getKey(), entry.getValue());
        }
        return new Reward(plan.mesos(), items);
    }

    // Consume tokens from the map's meter and report what this bot may take. `nowMs` is wall clock: the
    // map's yield accrues with map time, not with the number of bots asking, so two bots grinding the same
    // unobserved map for a minute share that one minute of yield between them.
    static Plan reserve(int mapId, int occupants, long elapsedMs, long nowMs, int dropRate, int mesoRate,
                        Sources sources) {
        if (mapId <= 0 || elapsedMs <= 0) {
            return new Plan(0, Map.of());
        }
        Yield yield = windowYield(mapId, occupants, dropRate, mesoRate, sources);
        if (yield.mesos() <= 0 && yield.items().isEmpty()) {
            return new Plan(0, Map.of());
        }

        Meter meter = METERS.computeIfAbsent(mapId, id -> new Meter());
        long mesos;
        Map<Integer, Integer> items = new HashMap<>();
        synchronized (meter) {
            // One window of yield may accrue per window of map time; a map left alone longer than that
            // still refills only one window, so a dormant map cannot dump a backlog.
            long elapsedSinceAccrual = meter.lastAccrualMs == 0
                    ? WINDOW_MS
                    : Math.max(0, Math.min(nowMs - meter.lastAccrualMs, WINDOW_MS));
            meter.lastAccrualMs = nowMs;

            // Fractions of a window, not per-millisecond rates: multiplying back a rounded 1/60000 loses a
            // unit on a full-window claim.
            double mapTime = Math.min(1.0, (double) elapsedSinceAccrual / WINDOW_MS);
            double botWindow = Math.min(1.0, Math.max(0, elapsedMs) / (double) WINDOW_MS);

            meter.mesos = Math.min(meter.mesos + yield.mesos() * mapTime, yield.mesos());
            // A bot never takes more than the window it just lived through, even if the map banked more.
            mesos = (long) Math.min(meter.mesos, yield.mesos() * botWindow);
            meter.mesos -= mesos;

            int budgetLeft = MAX_ITEMS_PER_DRAW;
            for (Map.Entry<Integer, Double> entry : yield.items().entrySet()) {
                int itemId = entry.getKey();
                double perWindow = entry.getValue();
                double bucket = Math.min(meter.items.getOrDefault(itemId, 0.0) + perWindow * mapTime, perWindow);
                // Same bound as mesos: never take more than the window this bot just lived through. Items
                // leave in whole units, so the unspent fraction stays in the bucket for the next draw.
                double takeable = Math.min(bucket, perWindow * botWindow);
                int count = (int) Math.min(Math.floor(takeable), budgetLeft);
                meter.items.put(itemId, bucket - Math.max(0, count));
                if (count <= 0) {
                    continue;
                }
                budgetLeft -= count;
                items.merge(itemId, count, Integer::sum);
                if (budgetLeft <= 0) {
                    break;
                }
            }
        }
        return new Plan(mesos, items);
    }

    // Expected yield of a whole map instance over one window: every spawn point's kill count over the
    // window, scaled by the live occupancy rule, at the mob's real drop expectation.
    static Yield windowYield(int mapId, int occupants, int dropRate, int mesoRate, Sources sources) {
        List<Integer> mobIds = sources.mobIds(mapId);
        List<Integer> respawnMs = sources.respawnMs(mapId);
        if (mobIds.isEmpty() || respawnMs.isEmpty()) {
            return new Yield(0, Map.of());
        }
        double spawnRate = MapleMap.getCurrentSpawnRate(Math.max(1, occupants));
        long respawnInterval = Math.max(1L, sources.respawnIntervalMs());

        double mesos = 0;
        Map<Integer, Double> items = new HashMap<>();
        int slots = Math.min(mobIds.size(), respawnMs.size());
        for (int slot = 0; slot < slots; slot++) {
            int respawn = respawnMs.get(slot);
            if (respawn < 0) {
                continue; // spawn point never respawns
            }
            double kills = (double) WINDOW_MS / Math.max(respawnInterval, respawn);
            mesos += accumulate(sources, mobIds.get(slot), kills, dropRate, mesoRate, items);
        }
        mesos *= spawnRate;
        if (mesos <= 0 && items.isEmpty()) {
            return new Yield(0, Map.of());
        }

        // Clamp each item's window allowance by the per-map drop limit: the map cannot yield more of an
        // item in a window than it could ever hold on its floor at once.
        int itemLimit = Math.max(1, sources.itemLimitOnMap());
        items.replaceAll((itemId, qty) -> Math.min(qty * spawnRate, itemLimit));
        // Mesos are whole units: round the expectation once so a full-window claim is not lost to the last
        // bit of the occupancy factor.
        return new Yield(Math.min(Math.round(mesos), Integer.MAX_VALUE), items);
    }

    // Clears accrued tokens. Static meter state must not leak between test cases.
    static void clearMeters() {
        METERS.clear();
    }

    // Adds one mob's expected yield over `kills` kills into the running item totals and returns its meso
    // contribution. Rates and probabilities follow BotAttackEffects.dropMobLoot, with one deliberate
    // simplification: every mob's drop chance uses the world's normal drop rate, not the higher boss rate a
    // boss mob would get there, so a map of boss mobs is estimated below its real yield. Boss maps are not
    // bot grind targets and under-estimating keeps the map's yield an upper bound, which is the safe side.
    private static double accumulate(Sources sources, int mobId, double kills, int dropRate, int mesoRate,
                                     Map<Integer, Double> itemTotals) {
        double mesos = 0;
        for (MonsterDropEntry de : sources.drops(mobId)) {
            if (de.questid != 0) {
                continue; // quest drops need a real quest-taking player; bots never have one
            }
            double probability = Math.min((double) de.chance * dropRate, Integer.MAX_VALUE) / 999999.0;
            if (probability <= 0) {
                continue;
            }
            if (de.itemId == 0) {
                double rolled = expectedRoll(de.Minimum, de.Maximum) * mesoRate;
                mesos += kills * probability * Math.max(1, rolled);
            } else if (ItemConstants.getInventoryType(de.itemId) == InventoryType.EQUIP) {
                itemTotals.merge(de.itemId, kills * probability, Double::sum);
            } else {
                double qty = de.Maximum != 1 ? expectedRoll(de.Minimum, de.Maximum) : 1;
                itemTotals.merge(de.itemId, kills * probability * qty, Double::sum);
            }
        }
        return mesos;
    }

    // Mean of BotAttackEffects.rollAmount(min, max): min + uniform(0..span-1).
    private static double expectedRoll(int min, int max) {
        int span = max - min;
        return min + (span > 0 ? (span - 1) / 2.0 : 0);
    }

    // Build the item(s) a successful drop roll would have produced, split across stacks the way the
    // inventory would hold them. Mirrors BotAttackEffects.dropMobLoot's item construction.
    private static void appendItems(List<Item> out, int itemId, int count) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP) {
            for (int i = 0; i < count; i++) {
                Item equip = ii.getEquipById(itemId);
                if (equip == null) {
                    return;
                }
                out.add(ii.randomizeStats((Equip) equip));
            }
            return;
        }
        int slotMax = Math.max(1, (int) ii.getSlotMax(itemId));
        int remaining = count;
        while (remaining > 0) {
            short qty = (short) Math.min(remaining, slotMax);
            out.add(new Item(itemId, (short) 0, qty));
            remaining -= qty;
        }
    }
}
