package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Unique bot names (bot-persistence ticket 05). A roster of ~10,000 players that log in and out needs every
 * name to mean one character, so a name is claimed by exactly one bot for good, and never one a real player
 * holds.
 *
 * <p>Names come from the pool files: the original {@code FMNameDesc/randomRealMaplestoryIGNs.txt} plus every
 * {@code *.txt} in {@link #NAME_DIR} (one name per line, {@code #} starts a comment). A line is kept only if the
 * client would accept it as a character name - {@code [A-Za-z0-9]{3,12}}, the rule of
 * {@code Character.canCreateChar} - and pool names are deduplicated ignoring case. When the pool runs dry,
 * era-style variants of pool names are generated instead (trailing digits, {@code xX..Xx}, letter swaps,
 * two-name joins).
 *
 * <p>Stored bots that already share a name (the old pool reshuffled when it ran out, so most names were used
 * twice) keep it oldest-first: the earliest-created holder keeps the name, every later one is given a new name
 * the next time it is adopted, and the store records it with the bot's next save.
 */
public final class BotNames {

    private static final Logger log = LoggerFactory.getLogger(BotNames.class);

    /** Owner-supplied name lists: every {@code *.txt} here joins the pool. */
    public static final Path NAME_DIR = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotNames");
    static final Path LEGACY_POOL = Path.of("src/main/java/soloMapling/FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt");

    static final Pattern LEGAL = Pattern.compile("[A-Za-z0-9]{3,12}");
    private static final int VARIANT_ATTEMPTS = 200;
    /** Holder id for a name a real player's character holds. */
    private static final int REAL_PLAYER = -1;

    /** Where the pool and the real players' names come from; swapped by tests. */
    record Sources(Supplier<List<String>> poolLines, Supplier<Collection<String>> realPlayerNames,
                   Supplier<Collection<StoredName>> storedNames, Random random) {
    }

    /** A stored bot's name and creation time, the input to the oldest-keeps-it rule. */
    record StoredName(int charId, String name, long createdAt) {
    }

    private static Sources sources = defaultSources();
    private static boolean loaded;
    private static final Map<String, Integer> holderByName = new HashMap<>();
    private static List<String> pool = List.of();
    private static int poolIndex;

    private BotNames() {
    }

    /** A brand-new, never-used name for this bot, claimed for it. */
    public static synchronized String claimNew(int charId) {
        load();
        while (poolIndex < pool.size()) {
            String candidate = pool.get(poolIndex++);
            if (claim(candidate, charId)) {
                return candidate;
            }
        }
        for (int attempt = 0; attempt < VARIANT_ATTEMPTS; attempt++) {
            String candidate = variant(pool.isEmpty() ? "Maple" : pool.get(sources.random().nextInt(pool.size())));
            if (candidate != null && claim(candidate, charId)) {
                return candidate;
            }
        }
        // Practically unreachable (it needs ~VARIANT_ATTEMPTS collisions in a row); still legal and unique.
        String fallback = "Mapler" + Integer.toString(charId, 36);
        fallback = fallback.length() > 12 ? fallback.substring(fallback.length() - 12) : fallback;
        claim(fallback, charId);
        return fallback;
    }

    /**
     * The name an adopted bot uses: its stored name when it is this bot's to keep, otherwise a new one. A bot
     * keeps its name unless an older stored bot holds it, or a real player's character does.
     */
    public static synchronized String claimStored(int charId, String storedName) {
        load();
        if (storedName != null && LEGAL.matcher(storedName).matches()) {
            Integer holder = holderByName.get(key(storedName));
            if (holder != null && holder == charId) {
                return storedName;
            }
            if (holder == null) {
                holderByName.put(key(storedName), charId);
                return storedName;
            }
        }
        String renamed = claimNew(charId);
        log.info("Bot {} is renamed {} -> {}: its stored name belongs to {}", charId, storedName, renamed,
                storedName == null ? "nobody" : holderByName.get(key(storedName)) == REAL_PLAYER ? "a real player"
                        : "an older bot");
        return renamed;
    }

    /** Whether any bot or real player holds this name (ignoring case). */
    public static synchronized boolean isTaken(String name) {
        load();
        return name != null && holderByName.containsKey(key(name));
    }

    /** How many names the pool files hold after filtering, for the GM read-out. */
    public static synchronized int poolSize() {
        load();
        return pool.size();
    }

    /** How many pool names have not been handed out yet. */
    public static synchronized int poolRemaining() {
        load();
        return Math.max(0, pool.size() - poolIndex);
    }

    private static boolean claim(String name, int charId) {
        if (!LEGAL.matcher(name).matches()) {
            return false;
        }
        return holderByName.putIfAbsent(key(name), charId) == null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (String real : sources.realPlayerNames().get()) {
            if (real != null) {
                holderByName.put(key(real), REAL_PLAYER);
            }
        }
        List<StoredName> stored = new ArrayList<>(sources.storedNames().get());
        stored.sort(Comparator.comparingLong(StoredName::createdAt).thenComparingInt(StoredName::charId));
        int duplicates = 0;
        for (StoredName row : stored) {
            if (row.name() == null || holderByName.putIfAbsent(key(row.name()), row.charId()) != null) {
                duplicates++;
            }
        }
        pool = filterPool(sources.poolLines().get());
        Collections.shuffle(pool, sources.random());
        poolIndex = 0;
        log.info("Bot names: pool of {} names, {} stored bots ({} share a name and will be renamed when adopted)",
                pool.size(), stored.size(), duplicates);
    }

    /** Legal, case-insensitively unique pool names, first spelling kept. */
    static List<String> filterPool(List<String> lines) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String name = line.replace("﻿", "").trim();
            if (name.isEmpty() || name.startsWith("#") || !LEGAL.matcher(name).matches()) {
                continue;
            }
            unique.putIfAbsent(key(name), name);
        }
        return new ArrayList<>(unique.values());
    }

    /** One era-style spelling built from a pool name, or null when this attempt made nothing legal. */
    static String variant(String base) {
        Random random = sources.random();
        String candidate = switch (random.nextInt(6)) {
            case 0 -> fit(base, String.valueOf(1 + random.nextInt(99)));
            case 1 -> base.length() <= 8 ? "xX" + base + "Xx" : fit(base, "x");
            case 2 -> swapLetters(base, random);
            case 3 -> fit(base, new String[]{"z", "x", "xx", "ms", "kun", "chan", "pwnz"}[random.nextInt(7)]);
            case 4 -> {
                String other = pool.isEmpty() ? "Leaf" : pool.get(random.nextInt(pool.size()));
                String joined = capitalize(shorten(base, 6)) + capitalize(shorten(other, 6));
                yield joined.length() <= 12 ? joined : joined.substring(0, 12);
            }
            default -> fit(base, String.valueOf(1980 + random.nextInt(30)).substring(2));
        };
        return candidate != null && LEGAL.matcher(candidate).matches() ? candidate : null;
    }

    private static String fit(String base, String suffix) {
        int room = 12 - suffix.length();
        return (base.length() > room ? base.substring(0, room) : base) + suffix;
    }

    private static String swapLetters(String base, Random random) {
        char[] chars = base.toCharArray();
        List<Integer> spots = new ArrayList<>();
        for (int i = 0; i < chars.length; i++) {
            if ("ilIoO".indexOf(chars[i]) >= 0) {
                spots.add(i);
            }
        }
        if (spots.isEmpty()) {
            return fit(base, String.valueOf(random.nextInt(10)));
        }
        int at = spots.get(random.nextInt(spots.size()));
        chars[at] = switch (chars[at]) {
            case 'i' -> 'l';
            case 'l' -> 'i';
            case 'I' -> 'l';
            case 'o', 'O' -> '0';
            default -> chars[at];
        };
        return new String(chars);
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : java.lang.Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Sources defaultSources() {
        return new Sources(BotNames::readPoolFiles, BotNames::readRealPlayerNames, BotNames::readStoredNames,
                new Random());
    }

    private static List<String> readPoolFiles() {
        List<String> lines = new ArrayList<>();
        readLines(LEGACY_POOL, lines);
        if (Files.isDirectory(NAME_DIR)) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(NAME_DIR, "*.txt")) {
                dir.forEach(files::add);
            } catch (IOException e) {
                log.warn("Could not list bot name files in {}", NAME_DIR, e);
            }
            files.sort(null);
            for (Path file : files) {
                readLines(file, lines);
            }
        }
        return lines;
    }

    private static void readLines(Path file, List<String> into) {
        try {
            into.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Could not read bot name file {}", file, e);
        }
    }

    private static Collection<String> readRealPlayerNames() {
        Set<String> names = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT name FROM characters");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("Could not read real character names; bot names are only unique among bots this run", e);
        }
        return names;
    }

    private static Collection<StoredName> readStoredNames() {
        List<StoredName> names = new ArrayList<>();
        for (BotStore.Persisted row : BotStore.storedRows()) {
            names.add(new StoredName(row.charId(), row.name, row.createdAt));
        }
        return names;
    }

    /** Test seam: swaps the sources and forgets every claim (null restores the real sources). */
    static synchronized void useSourcesForTest(Sources testSources) {
        sources = testSources == null ? defaultSources() : testSources;
        loaded = false;
        holderByName.clear();
        pool = List.of();
        poolIndex = 0;
    }
}
