package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.awt.Point;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Where real players spend their time (bot-persistence ticket 18): a per-map grid of how often a player stood in
 * each cell, fading with a {@link #HALF_LIFE_MS} half-life, so bots can choose the town corners and grind ledges
 * players actually use. Fed from real players' movement packets (sampled once per {@link #SAMPLE_MS} per player,
 * on the ground only); read by spot choice as a 0..1 warmth. With no data every cell is 0 and spot choice is exactly
 * what it was. Saved under {@code cache/bot-nav/heat/<map>.json}.
 */
public final class PlayerHeat {

    private static final Logger log = LoggerFactory.getLogger(PlayerHeat.class);

    static final int CELL_PX = 100;
    static final long SAMPLE_MS = 2_000;
    static final long HALF_LIFE_MS = TimeUnit.DAYS.toMillis(3);
    static final Path DIR = Path.of("cache", "bot-nav", "heat");

    private static final class Cell {
        double weight;
        long at;

        double fadedAt(long now) {
            return at > 0 && now > at ? weight * Math.pow(0.5, (now - at) / (double) HALF_LIFE_MS) : weight;
        }
    }

    private static final class MapHeat {
        final Map<Long, Cell> cells = new ConcurrentHashMap<>();
        volatile boolean dirty;
        volatile boolean loaded;
    }

    private static final Map<Integer, MapHeat> maps = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastSampleByPlayer = new ConcurrentHashMap<>();
    private static volatile Path dir = DIR;
    private static volatile boolean flusherStarted;

    private PlayerHeat() {
    }

    private static long cell(int x, int y) {
        return ((long) Math.floorDiv(x, CELL_PX) << 32) | (Math.floorDiv(y, CELL_PX) & 0xffffffffL);
    }

    /** One movement element of a real player; counted at most once per {@link #SAMPLE_MS}, on the ground only. */
    public static void onPlayerMove(Character player, int x, int y, int fh) {
        if (player == null || fh == 0 || player.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSampleByPlayer.get(player.getId());
        if (last != null && now - last < SAMPLE_MS) {
            return;
        }
        lastSampleByPlayer.put(player.getId(), now);
        add(player.getMapId(), x, y, 1.0, now);
    }

    static void add(int mapId, int x, int y, double amount, long now) {
        startFlusher();
        MapHeat heat = heatOf(mapId);
        Cell c = heat.cells.computeIfAbsent(cell(x, y), k -> new Cell());
        synchronized (c) {
            c.weight = c.fadedAt(now) + amount;
            c.at = now;
        }
        heat.dirty = true;
    }

    /** How warm a point is, 0 (no player ever stood near it) to 1 (the map's warmest cell). */
    public static double warmth(int mapId, Point p) {
        MapHeat heat = heatOf(mapId);
        if (heat.cells.isEmpty() || p == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        Cell here = heat.cells.get(cell(p.x, p.y));
        if (here == null) {
            return 0;
        }
        double max = 0;
        for (Cell c : heat.cells.values()) {
            max = Math.max(max, c.fadedAt(now));
        }
        return max <= 0 ? 0 : here.fadedAt(now) / max;
    }

    private static MapHeat heatOf(int mapId) {
        MapHeat heat = maps.computeIfAbsent(mapId, id -> new MapHeat());
        if (!heat.loaded) {
            synchronized (heat) {
                if (!heat.loaded) {
                    load(mapId, heat);
                    heat.loaded = true;
                }
            }
        }
        return heat;
    }

    // ── persistence ─────────────────────────────────────────────────────────────────────────────

    private static void startFlusher() {
        if (flusherStarted) {
            return;
        }
        synchronized (PlayerHeat.class) {
            if (flusherStarted) {
                return;
            }
            flusherStarted = true;
            var future = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                    .scheduleAtFixedRate(PlayerHeat::flush, 10, 10, TimeUnit.MINUTES);
            soloMapling.server.BotShutdown.onQuiesce(() -> {
                future.cancel(false);
                flush();
            });
        }
    }

    public static int flush() {
        int written = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, MapHeat> entry : maps.entrySet()) {
            MapHeat heat = entry.getValue();
            if (!heat.dirty) {
                continue;
            }
            heat.dirty = false;
            Map<String, Object> cells = new LinkedHashMap<>();
            heat.cells.forEach((key, c) -> {
                double w = c.fadedAt(now);
                if (w >= 0.05) {
                    cells.put(String.valueOf(key), List.of(Math.round(w * 100) / 100.0, now));
                }
            });
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(entry.getKey() + ".json"), Json.write(Map.of("cells", cells)),
                        StandardCharsets.UTF_8);
                written++;
            } catch (IOException e) {
                heat.dirty = true;
                log.debug("Could not save player heat of map {}", entry.getKey(), e);
            }
        }
        return written;
    }

    private static void load(int mapId, MapHeat heat) {
        Path file = dir.resolve(mapId + ".json");
        if (!Files.exists(file)) {
            return;
        }
        try {
            if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> root
                    && root.get("cells") instanceof Map<?, ?> cells) {
                cells.forEach((k, v) -> {
                    if (v instanceof List<?> l && l.size() == 2) {
                        Cell c = new Cell();
                        c.weight = ((Number) l.get(0)).doubleValue();
                        c.at = ((Number) l.get(1)).longValue();
                        heat.cells.put(Long.parseLong(String.valueOf(k)), c);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable player heat {}", file, e);
        }
    }

    /** For the GM read-out. */
    public static String describe() {
        int cells = 0;
        for (MapHeat heat : maps.values()) {
            cells += heat.cells.size();
        }
        return "player heat: " + cells + " warm cells on " + maps.size() + " maps";
    }

    /** Test seam: a scratch directory (null restores the cache dir) and a clean slate. */
    static void useDirForTest(Path testDir) {
        dir = testDir == null ? DIR : testDir;
        maps.clear();
        lastSampleByPlayer.clear();
    }
}
