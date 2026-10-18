package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import client.SkinColor;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.Trade;
import server.maps.MapleMap;
import soloMapling.server.SoloMaplingConstants;
import tools.DatabaseConnection;
import tools.Pair;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable identity, progression and holdings for the simulated bot population (ticket 06).
 *
 * <p>A bot is not a player: it never logs in, so it can never pass the {@code loggedIn} gate that guards
 * character saves, and it cannot re-create itself the way a player can. Its durable state therefore lives
 * in the {@code botstore} table, one row per bot, with the bot's character id derived from the row
 * ({@code BOT_ID_BASE + BotId}, always in the bot id range and never handed out by the {@code characters}
 * counter). Inventory and worn equipment use the shared item tables through the ordinary
 * {@link ItemFactory} path, keyed by that same character id, so item shapes and slot rules stay in one
 * implementation.
 *
 * <p>The row key is the bot's <em>plan slot</em>: which wave, which task of that wave and which bot of that
 * task spawned it - see {@link #planSlot(int, int, int)}. Wave tasks spawn in parallel and a wave's counts
 * are drawn per boot, so the plan position (a code constant) is the only stable identity a bot has; the
 * population is reproduced by place, not by arrival order.
 *
 * <p>Three things are written: identity/progression/look/capacity in the row, holdings in the item
 * tables. Reconstructible state (movement trails, coarse plans, combat presentation, chatter pacing) is
 * deliberately not stored - it is rebuilt by the same code that builds it for a new bot.
 *
 * <p>Saving happens at three points: first creation, bot removal ({@link #saveThenSettle}), and the
 * periodic/shutdown sweeps ({@link #saveAll}, {@link #settleAndSaveAll}). Nothing else writes a bot.
 */
public final class BotStore {

    private static final Logger log = LoggerFactory.getLogger(BotStore.class);

    /** Bot character ids start here; {@link BotHelpers#isBot(Character)} classifies anything above it as a bot. */
    public static final int BOT_ID_BASE = SoloMaplingConstants.GameConstants.BOT_BASE_ID;

    /**
     * The first id the store hands out. Bots used to be numbered from here by an in-process counter, so
     * the first creation slot keeps the id the first spawned bot always had.
     */
    public static final int FIRST_BOT_ID = BOT_ID_BASE + 100;

    /*
     * The key space the store hands out. A bot's key is its position in the boot plan - which wave, which
     * task of that wave, and which bot of that task - not the order in which it happened to reach the
     * spawner. Wave tasks run in parallel and a wave's counts are drawn per boot
     * ({@code EnvironmentManager.randomizeCount}), so arrival order is not stable across boots: keying on
     * it hands one bot's level, gear and holdings to an unrelated bot and mints a fresh template bot for
     * every bot that shifted past the end of the roster. A plan position is a code constant, so the same
     * position in the same wave is the same bot forever.
     */
    /** The store's first row is BotId 100; slots below it are unused. */
    public static final int FIRST_SLOT = 100;
    /** Slots a single wave task owns; a task that fans out encodes its own sub-index inside this range. */
    public static final int SLOTS_PER_TASK = 2048;
    /**
     * Slots one cohort inside a wave task owns. A task that spawns several cohorts gives each a fixed
     * stride ({@code slotBase + n * SLOTS_PER_GROUP}), so a change in one cohort's headcount can never
     * shift the next cohort onto its neighbour's stored identity. Sixteen cohorts fit one task, and no
     * single cohort in a shared task may be larger than this.
     */
    public static final int SLOTS_PER_GROUP = 128;
    /** Slots a wave owns, and therefore how many tasks a wave may have. */
    public static final int SLOTS_PER_WAVE = 65536;
    /** Slots a wave may have. */
    private static final int TASKS_PER_WAVE = SLOTS_PER_WAVE / SLOTS_PER_TASK;
    /** Waves the plan may have. */
    private static final int MAX_WAVE = 64;
    /**
     * The on-demand block: slots for bots spawned by hand (GM commands), which have no plan position.
     * Their slots are handed out in arrival order and never adopted - an on-demand spawn is a new bot,
     * not a resurrection of whatever the previous run's on-demand spawn left in that slot.
     */
    private static final int DYNAMIC_SLOTS = 8192;
    /** The first slot the boot plan owns; everything below it belongs to on-demand spawns. */
    public static final int FIRST_PLANNED_SLOT = FIRST_SLOT + DYNAMIC_SLOTS;

    /**
     * The first slot of the roster block (bot-persistence ticket 04): bots that join the roster after it was
     * enrolled take slots above every slot {@link #planSlot} can produce, so a new player can never collide with
     * a boot-plan position. Roster slots count as planned - a roster bot is adopted from its row like any
     * planned bot.
     */
    public static final int FIRST_ROSTER_SLOT = FIRST_PLANNED_SLOT + (MAX_WAVE + 1) * SLOTS_PER_WAVE;

    /*
     * First-creation policy. A new bot owns exactly the look and gear its decorator gave it: no
     * experience, no mesos, and inventory space limited by the row it is cloned from. Stated here,
     * applied by applyInitialGrants, so a changed template row cannot quietly hand every new bot a
     * starting fortune. The decorator still owns name, level, job, look and tier.
     */
    public static final int INITIAL_EXP = 0;
    public static final int INITIAL_MESO = 0;

    /** Inventory tabs a bot's items live in. {@code UNDEFINED} and {@code CANHOLD} are guards, not tabs. */
    private static final List<InventoryType> STORED_TYPES = List.of(InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH, InventoryType.EQUIPPED);

    private static final String SELECT_ROSTER = "SELECT * FROM botstore ORDER BY BotId";
    // Package-visible with read/bindMutable so the mapping test can check the statements' column order
    // against the order the values are written in: a swap there would corrupt every row silently.
    static final String INSERT_ROW = "INSERT INTO botstore (BotId, Name, Tier, Level, Exp, Meso, Job, "
            + "Skin, Gender, Hair, Face, MapId, PosX, PosY, EquipSlots, UseSlots, SetupSlots, EtcSlots, "
            + "SavedAt, CreatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String UPDATE_ROW = "UPDATE botstore SET Name = ?, Tier = ?, Level = ?, Exp = ?, "
            + "Meso = ?, Job = ?, Skin = ?, Gender = ?, Hair = ?, Face = ?, MapId = ?, PosX = ?, PosY = ?, "
            + "EquipSlots = ?, UseSlots = ?, SetupSlots = ?, EtcSlots = ?, SavedAt = ? WHERE BotId = ?";

    private static final Object ROSTER_LOCK = new Object();
    /** A deadlocked write is retried this many times before the bot's write is given up on. */
    private static final int WRITE_ATTEMPTS = 4;
    private static final long CONTENTION_BACKOFF_MS = 20L;
    private static final long CONTENTION_JITTER_MS = 40L;
    private static final AtomicInteger dynamicOrdinal = new AtomicInteger();
    /** Concurrent because spawn waves run their cohorts in parallel; the lock only guards the load. */
    private static final Map<Integer, Persisted> roster = new ConcurrentHashMap<>();
    /** Bot ids whose stored items could not be read; their inventory is never written back this run. */
    private static final Set<Integer> holdingsUnavailable = ConcurrentHashMap.newKeySet();
    /** Bot ids that adopted a stored row this run; nothing may re-roll their stored state afterwards. */
    private static final Set<Integer> adoptedThisRun = ConcurrentHashMap.newKeySet();
    private static volatile boolean rosterLoaded;

    private BotStore() {
    }

    /** One bot's durable state, exactly as it is stored and exactly as it is restored. */
    static final class Persisted {
        final int botId;
        final String name;
        final String tier;
        final int level;
        final int exp;
        final int meso;
        final int job;
        final int skin;
        final int gender;
        final int hair;
        final int face;
        final int mapId;
        final int posX;
        final int posY;
        final int equipSlots;
        final int useSlots;
        final int setupSlots;
        final int etcSlots;
        final long createdAt;
        final long savedAt;

        Persisted(int botId, String name, String tier, int level, int exp, int meso, int job, int skin,
                  int gender, int hair, int face, int mapId, int posX, int posY, int equipSlots, int useSlots,
                  int setupSlots, int etcSlots, long createdAt, long savedAt) {
            this.botId = botId;
            this.name = name;
            this.tier = tier;
            this.level = level;
            this.exp = exp;
            this.meso = meso;
            this.job = job;
            this.skin = skin;
            this.gender = gender;
            this.hair = hair;
            this.face = face;
            this.mapId = mapId;
            this.posX = posX;
            this.posY = posY;
            this.equipSlots = equipSlots;
            this.useSlots = useSlots;
            this.setupSlots = setupSlots;
            this.etcSlots = etcSlots;
            this.createdAt = createdAt;
            this.savedAt = savedAt;
        }

        int charId() {
            return BOT_ID_BASE + botId;
        }

        /**
         * The durable half of a bot, written onto a freshly cloned character: identity, progression, look,
         * tier and inventory capacity. Movement, combat and pacing state is rebuilt, not restored.
         */
        void applyTo(Character bot) {
            bot.setID(charId());
            bot.setName(name);
            bot.setFame(charId());
            bot.setTier(BotTier.fromString(tier));
            bot.setLevel(level);
            bot.setExp(exp);
            int delta = meso - bot.getMeso();
            if (delta != 0) {
                bot.gainMeso(delta, false, false, false);
            }
            bot.setJob(Job.getById(job));
            bot.setSkinColor(SkinColor.getById(skin));
            bot.setGender(gender);
            bot.setHair(hair);
            bot.setFace(face);
            bot.getInventory(InventoryType.EQUIP).setSlotLimit(equipSlots);
            bot.getInventory(InventoryType.USE).setSlotLimit(useSlots);
            bot.getInventory(InventoryType.SETUP).setSlotLimit(setupSlots);
            bot.getInventory(InventoryType.ETC).setSlotLimit(etcSlots);
        }

        /**
         * This bot's holdings: every stored tab, straight out of the shared item tables. Loaded with the
         * non-login flag so the worn-equipment filter the player login path uses does not drop the bot's
         * carried items.
         */
        void loadHoldings(Character bot) throws SQLException {
            for (Pair<Item, InventoryType> item : ItemFactory.INVENTORY.loadItems(charId(), false)) {
                bot.getInventory(item.getRight()).addItemFromDB(item.getLeft());
            }
        }

        /**
         * Puts an adopted bot back where it was last seen, when the wave spawned it on the map the row
         * remembers. The point is applied as stored - ground is not re-derived here, because every path
         * that makes a bot visible again (observed physics, and the promotion rebuild) snaps it to the
         * ground before it can be seen. A bot whose home map changed with the environment config keeps
         * the spawn point the wave placed it on instead.
         */
        void restorePlacement(Character bot, MapleMap waveMap) {
            if (waveMap == null || waveMap.getId() != mapId) {
                return;
            }
            bot.setPosition(new Point(posX, posY));
        }
    }

    /**
     * The slot at the given position of the boot plan: {@code ordinal} is the bot's index inside its task
     * (a loop counter for a flat cohort, or an encoded sub-index for a task that fans out over rooms and
     * spots). Deterministic by construction, so a bot keeps its row across boots no matter how the wave
     * tasks interleave or how many bots a wave draws this time.
     */
    public static int planSlot(int wave, int task, int ordinal) {
        if (wave < 1 || wave > MAX_WAVE) {
            throw new IllegalArgumentException("Boot plan slot out of range: wave " + wave);
        }
        if (task < 0 || task >= TASKS_PER_WAVE) {
            throw new IllegalArgumentException("Boot plan slot out of range: task " + task + " of wave " + wave
                    + " (a wave owns " + TASKS_PER_WAVE + " tasks)");
        }
        if (ordinal < 0 || ordinal >= SLOTS_PER_TASK) {
            throw new IllegalArgumentException("Boot plan slot out of range: ordinal " + ordinal + " of task "
                    + task + " (a task owns " + SLOTS_PER_TASK + " slots)");
        }
        return FIRST_PLANNED_SLOT + wave * SLOTS_PER_WAVE + task * SLOTS_PER_TASK + ordinal;
    }

    /** True for a slot of the roster block, see {@link #FIRST_ROSTER_SLOT}. */
    public static boolean isRosterSlot(int slot) {
        return slot >= FIRST_ROSTER_SLOT;
    }

    /** The boot-plan wave a planned slot belongs to, or -1 for an on-demand or roster-block slot. */
    public static int waveOf(int slot) {
        if (!isPlanned(slot) || isRosterSlot(slot)) {
            return -1;
        }
        return (slot - FIRST_PLANNED_SLOT) / SLOTS_PER_WAVE;
    }

    /** True for a slot the boot plan owns. Only planned slots are adopted; see {@link #nextDynamicSlot()}. */
    public static boolean isPlanned(int slot) {
        return slot >= FIRST_PLANNED_SLOT;
    }

    /**
     * The slot for an on-demand spawn (a GM command): these bots have no plan position, so they are handed
     * the next slot of the on-demand block in arrival order. A later spawn reuses the slot rather than
     * adopting what the previous run left there - an on-demand bot is always minted fresh.
     */
    public static int nextDynamicSlot() {
        return nextDynamicSlots(1);
    }

    /**
     * Reserves a run of consecutive on-demand slots and returns the first (a GM cohort spawns its bots
     * into {@code first}, {@code first + 1}, ... in its own order).
     */
    public static int nextDynamicSlots(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Cannot reserve " + count + " on-demand slots");
        }
        int ordinal = dynamicOrdinal.getAndAdd(count);
        if (ordinal + count > DYNAMIC_SLOTS) {
            throw new IllegalStateException("On-demand bot slots are exhausted (" + DYNAMIC_SLOTS
                    + "); restart the server to spawn more bots by hand");
        }
        return FIRST_SLOT + ordinal;
    }

    /** The stored bot the given id belongs to, or {@code null} when the slot has never been filled. */
    public static Persisted persistedFor(int botId) {
        if (botId < FIRST_BOT_ID) {
            return null;
        }
        return roster().get(botId - BOT_ID_BASE);
    }

    /**
     * Adopts the stored bot of this id onto a freshly cloned character: identity, progression, look,
     * capacity, holdings and last-seen placement. Returns {@code false} when the slot has never been
     * filled, which means the caller is holding a brand new bot that still needs to be minted.
     *
     * <p>Called after the bot has been put on the map, because the placement restore re-uses the map the
     * spawn wave chose.
     */
    public static boolean adopt(Character bot, MapleMap map) {
        if (!restoreIdentity(bot)) {
            return false;
        }
        Persisted stored = persistedFor(bot.getId());
        stored.restorePlacement(bot, map);
        return true;
    }

    /**
     * Applies the stored identity, progression, look, capacity and holdings to a freshly cloned bot.
     * Returns {@code false} when the slot has never been filled. Split from the placement half so a caller
     * can apply the identity <em>before</em> the bot is put on the map: the spawn packet carries the name
     * the bot already has, so an adopted bot must not be broadcast under its placeholder name.
     */
    public static boolean restoreIdentity(Character bot) {
        Persisted stored = persistedFor(bot.getId());
        if (stored == null) {
            return false;
        }
        stored.applyTo(bot);
        adoptedThisRun.add(bot.getId());
        try {
            stored.loadHoldings(bot);
        } catch (Exception e) {
            // The row is still readable, but this bot's items are not. Let it run this boot with empty
            // tabs, but never write them: the item tables are rewritten tab by tab, so a save now would
            // wipe the stored items with the empty ones the bot is carrying. The write is failed, not
            // skipped, so the ledger is settled onto the map instead of being silently dropped - except for a
            // debit, which writeHoldings writes anyway because the stale row would restore it (see there).
            holdingsUnavailable.add(bot.getId());
            log.warn("Could not load the holdings of bot {}: {} - this bot will not write its inventory",
                    bot.getId(), e.getMessage());
        }
        // Level, job and worn gear all came off the row, so the clone's stats are stale until this point
        // (ticket 07 rule 2: stored progression must be internally consistent, not just visible).
        bot.refreshStatsAfterProgression();
        return true;
    }

    /**
     * Whether this bot took its identity from the store in this run. A caller that would otherwise roll
     * fresh state onto a freshly spawned cohort (the OPQ lobby's level band) must leave these bots alone:
     * their level, job and looks are what the store gave back, and re-rolling them would throw away the
     * progression the bot earned in an earlier run.
     */
    public static boolean isAdoptedInThisRun(int botId) {
        return adoptedThisRun.contains(botId);
    }

    /** Puts an adopted bot back on the pixel it was last seen at, when it is standing on the same map. */
    public static boolean restorePlacement(Character bot, MapleMap map) {
        Persisted stored = persistedFor(bot.getId());
        if (stored == null) {
            return false;
        }
        stored.restorePlacement(bot, map);
        return true;
    }

    /**
     * First-creation grants: a brand new bot has no experience and no mesos of its own. Anything it owns
     * comes from the decorator (gear and looks) or from what it earns in the world.
     */
    static void applyInitialGrants(Character bot) {
        bot.setExp(INITIAL_EXP);
        int held = bot.getMeso();
        if (held != INITIAL_MESO) {
            bot.gainMeso(INITIAL_MESO - held, false, false, false);
        }
    }

    /**
     * Writes a brand new bot: the initial grants, then its row and holdings. Called once, right after the
     * bot is decorated. It is the same row write a save does, so an on-demand spawn that reuses the slot
     * of an earlier run's on-demand bot replaces that row instead of colliding with it.
     */
    static void recordFirstCreation(Character bot) throws SQLException {
        applyInitialGrants(bot);
        writeTransaction(bot, con -> writeRow(bot, botId(bot), con));
        remember(botId(bot), bot);
    }

    /**
     * Saves one bot: its row and every tab of its inventory. A missing row is re-created from the bot
     * itself, so a bot that somehow never got a row still cannot lose what it holds.
     *
     * @return the ledger stamp read once the state had been captured for the row, or {@link #NO_STAMP} when
     *         there was no row to write. A caller that has to decide whether the row holds a bot's deltas
     *         compares the stamp the bot has after the commit against this one: equal means the row is what
     *         happened up to the capture, different means a delta arrived after it and is not in the row.
     */
    public static int save(Character bot) throws SQLException {
        int botId = botId(bot);
        if (botId < 1) {
            return NO_STAMP;
        }
        int captured = writeTransaction(bot, con -> writeRow(bot, botId, con));
        remember(botId, bot);
        return captured;
    }

    /**
     * The stamp {@link #save(Character)} reports when the bot has no row: there is nothing captured for a
     * restart to read back, so a caller has no row to compare a bot's deltas against.
     */
    static final int NO_STAMP = -1;

    /**
     * Returned by a write whose capture did not bracket one moment: the bot's ledger changed between the start of
     * the capture (the row's scalars) and its end (the item tabs), so the row would describe two different
     * instants and no caller could tell whether a delta it holds is in it. {@link #writeTransaction} treats it
     * as retryable rather than committed - the capture is taken again from scratch - because the alternatives are
     * both wrong: committing it and letting the caller keep the deltas duplicates a delta the row already has
     * (a mint), and committing it and letting the caller drop them loses a delta the row does not have (a
     * destroy) whenever the delta landed between the two halves.
     */
    static final int UNSTABLE_CAPTURE = -2;

    /** The insert half of a row write: used when the update matched no row, i.e. the slot is still empty. */
    private static int insertRow(Character bot, int botId, Connection con, int capturedAt) throws SQLException {
        Persisted row = of(bot);
        try (PreparedStatement ps = con.prepareStatement(INSERT_ROW)) {
            ps.setInt(1, row.botId);
            bindMutable(ps, row, 2);
            ps.setLong(20, row.createdAt);
            ps.executeUpdate();
        }
        return writeHoldings(bot, con, capturedAt);
    }

    /** The row write of a save: an update, re-created from the bot itself when the row is missing. */
    private static int writeRow(Character bot, int botId, Connection con) throws SQLException {
        // The capture starts here: everything below - the row's scalars from of(bot) and the item copies from
        // dumpInventory - has to come from one moment, or a caller cannot tell whether a delta it holds is
        // already in the row. writeHoldings compares the ledger stamp against this one when the capture ends.
        int capturedAt = BotHoldings.ledgerStamp(bot);
        Persisted row = of(bot);
        try (PreparedStatement ps = con.prepareStatement(UPDATE_ROW)) {
            bindMutable(ps, row, 1);
            ps.setInt(19, botId);
            if (ps.executeUpdate() == 0) {
                return insertRow(bot, botId, con, capturedAt);
            }
        }
        return writeHoldings(bot, con, capturedAt);
    }

    /**
     * The one writer of the store's tables. Spawn cohorts create their bots in parallel, and two of them
     * writing the same tables at once can collide - MySQL rolls one of them back with a deadlock instead of
     * letting it wait. That collision is retried so a bot does not lose its first row to it; anything else
     * is the caller's problem.
     */
    private static int writeTransaction(Character bot, Write write) throws SQLException {
        SQLException contention = null;
        boolean captureUnstable = false;
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try (Connection con = connectionSource.get()) {
                con.setAutoCommit(false);
                try {
                    int captured = write.apply(con);
                    if (captured == UNSTABLE_CAPTURE) {
                        // The bot changed across its own capture. Nothing of that attempt is wanted - the row
                        // would describe two moments - so it is rolled back and the whole capture is taken
                        // again, on the same attempt budget and with the same backoff as a contention retry.
                        con.rollback();
                        captureUnstable = true;
                    } else {
                        con.commit();
                        return captured;
                    }
                } catch (SQLException e) {
                    con.rollback();
                    captureUnstable = false;
                    if (!isContention(e)) {
                        throw e;
                    }
                    contention = e;
                }
            }
            try {
                // A few milliseconds of jitter is enough for the other writer to commit and release the lock.
                Thread.sleep(CONTENTION_BACKOFF_MS + ThreadLocalRandom.current().nextLong(CONTENTION_JITTER_MS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while writing bot " + bot.getId(), interrupted);
            }
        }
        if (captureUnstable) {
            throw new SQLException("Could not capture the state of bot " + bot.getId() + " (" + bot.getName()
                    + ") after " + WRITE_ATTEMPTS + " attempts: it kept changing while its row was being"
                    + " written, so no row could be written from one moment of it");
        }
        log.warn("Could not write bot {} ({}) after {} attempts: {}", bot.getId(), bot.getName(), WRITE_ATTEMPTS,
                contention.getMessage());
        throw contention;
    }

    /** True for the two errors two colliding writers produce: a deadlock and a lock wait timeout. */
    static boolean isContention(SQLException e) {
        return "40001".equals(e.getSQLState()) || e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
    }

    /**
     * One bot's write, run inside its own transaction and committed by {@link #writeTransaction}. It returns
     * either the ledger stamp that brackets the state it captured - read before the capture began and checked
     * again when it ended - or {@link #UNSTABLE_CAPTURE} when the bot changed across its own capture.
     */
    @FunctionalInterface
    private interface Write {
        int apply(Connection con) throws SQLException;
    }

    /** Where the store gets its connections; a unit test swaps in a mock pool. */
    @FunctionalInterface
    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private static volatile ConnectionSource connectionSource = DatabaseConnection::getConnection;

    /** Test seam: points the store at a mock pool, or back at the real one with {@code null}. */
    static void useConnectionSourceForTest(ConnectionSource source) {
        connectionSource = source == null ? DatabaseConnection::getConnection : source;
    }

    /**
     * Test seam: marks (or clears) a bot as one whose stored items could not be read, which is what a
     * failed {@code loadHoldings} in {@link #restoreIdentity} does in the world. Such a bot must fail its
     * write rather than write empty tabs over its stored items.
     */
    static void markHoldingsUnreadableForTest(int botId, boolean unreadable) {
        if (unreadable) {
            holdingsUnavailable.add(botId);
        } else {
            holdingsUnavailable.remove(botId);
        }
    }

    /**
     * The durable exit for a bot leaving the world: its state goes into the store. If it cannot be
     * written, the bot is settled onto the map it is standing on instead, so what it accumulated is never
     * silently destroyed.
     *
     * <p>A bot that has given value away is the exception, since settling cannot return a debit: the value is
     * already in someone else's hands, and the stored row still claims it, so the delta may only ever be
     * recorded. Such a bot is retried and, if even that cannot be written, its unreadable stored holdings are
     * overwritten with what it really holds (see {@link #writeHoldings(Character, Connection)}).
     *
     * <p>Handing the deltas over to {@link BotHoldings#discardIfUnchanged(Character, int)} has one precondition:
     * the row just written has to be what holds their value. A bot can change while its own write is in flight - casino
     * payouts and bot-owned shops run off the tick thread - so a delta can arrive after the state was captured
     * for that row. {@link #save(Character)} reports the stamp it saw at capture time, so a stamp that differs
     * afterwards is exactly a delta the row does not record: it stays, and takes the same route as a failed
     * write, rather than being settled onto the map on top of the row that already holds it.
     */
    public static void saveThenSettle(Character bot) {
        try {
            int captured = save(bot);
            if (captured == NO_STAMP || BotHoldings.discardIfUnchanged(bot, captured)) {
                // The ledger exists to hand value back to the world if a bot dies holding it. Now that the
                // value is durable, keeping the deltas would let a later settle hand out the same value twice.
                // The comparison and the removal are one step, so a delta that arrived after the capture is kept
                // and takes the changed route below instead of being dropped with a row that does not hold it.
                return;
            }
            log.warn("Bot {} ({}) changed while its row was being written, so that row does not record it",
                    bot.getId(), bot.getName());
        } catch (Exception e) {
            log.warn("Could not save bot {} ({})", bot.getId(), bot.getName(), e);
        }
        if (BotHoldings.hasUnpersistedDebit(bot) && recordDebit(bot)) {
            return;
        }
        // Nothing here can be written for this bot, and the value its deltas stand for has nowhere else to go:
        // what it took in is handed to its map, while a debit - value already in someone else's hands - stays
        // recorded as the row claims it (see the javadoc above).
        log.warn("Could not save bot {} ({}), settling its holdings onto the map instead", bot.getId(),
                bot.getName());
        BotHoldings.settle(bot);
    }

    /**
     * Records a debit that the ordinary save could not write, because settling cannot do it: the value the
     * debit stands for is already in someone else's hands, so forgetting the delta leaves the stored row
     * claiming it, and the next boot restores it on top of what the recipient holds - the same value minted
     * twice. A bot in that state is retried rather than dropped, and only when even the retry cannot be written
     * is its stored claim destroyed instead of being left to be restored.
     *
     * @return true when some durable record of the debit now exists, so the ledger may be dropped
     */
    private static boolean recordDebit(Character bot) {
        try {
            log.warn("Bot {} ({}) could not be saved and holds value its stored row still claims; retrying the"
                    + " write rather than settling the debit onto the map", bot.getId(), bot.getName());
            int captured = save(bot);
            if (captured == NO_STAMP || BotHoldings.discardIfUnchanged(bot, captured)) {
                return true;   // the debit is durable now
            }
            log.warn("The debit of bot {} ({}) changed while its retry was writing it, so that row does not"
                    + " record it", bot.getId(), bot.getName());
        } catch (Exception retryFailed) {
            log.warn("The debit of bot {} ({}) could not be written on the retry either", bot.getId(),
                    bot.getName(), retryFailed);
        }
        try {
            int destroyed = destroyStoredHoldings(bot);
            if (destroyed == NO_STAMP || BotHoldings.discardIfUnchanged(bot, destroyed)) {
                log.warn("The debit of bot {} ({}) could not be written, so its stored claim was taken down"
                        + " instead: what its row claimed and the bot no longer holds is destroyed rather than"
                        + " restored", bot.getId(), bot.getName());
                return true;   // nothing durable claims the value any more
            }
            // The destroy wrote the row as the bot stood at one moment, and something changed after it. That
            // delta is in no row; settling it now would hand out what the row already holds, so the value is
            // lost with the bot - the direction this path loses in.
            log.warn("The debit of bot {} ({}) changed after its stored claim was taken down, so that row does"
                    + " not record it", bot.getId(), bot.getName());
            return true;
        } catch (Exception destroyFailed) {
            log.warn("The debit of bot {} ({}) could not be written, not even by clearing its stored holdings:"
                    + " the stored row still claims value the bot already gave away", bot.getId(), bot.getName(),
                    destroyFailed);
            return false;
        }
    }

    /**
     * The last durable resort for a debit that will not write: write the row from the bot as it stands, so a meso
     * debit is on record, and - only when the debit is in items - write the bot's live holdings over the stored
     * item rows, because a bot's items live in their own rows and not in its row. Nothing else can take back a
     * stale item claim, and leaving that claim alone is what a restart would restore while the recipient holds
     * the payout.
     *
     * <p>What the item write does is destroy the stored baseline the bot's own row claimed, which is the
     * direction to lose in: it is the bot's own untouched value, while a value minted this way belongs to nobody
     * and cannot be taken back by any later write. The bot's live holdings are written, so what it really holds
     * survives. A meso-only debit leaves the stored item rows alone entirely: the row itself records it, and no
     * item claim is stale.
     */
    private static int destroyStoredHoldings(Character bot) throws SQLException {
        int botId = botId(bot);
        if (botId < 1) {
            return NO_STAMP;   // no stored row: nothing a restart could restore, and nothing to compare a stamp to
        }
        boolean itemDebit = BotHoldings.hasUnpersistedItemDebit(bot);
        return writeTransaction(bot, con -> {
            // This capture is bracketed the same way a save's is: the row it leaves behind has to describe one
            // moment. Whether a delta that arrives during it is kept or dropped is decided by the caller against
            // the stamp this returns, not here.
            int capturedAt = BotHoldings.ledgerStamp(bot);
            try (PreparedStatement ps = con.prepareStatement(UPDATE_ROW)) {
                bindMutable(ps, of(bot), 1);
                ps.setInt(19, botId);
                ps.executeUpdate();   // 0 means the row is gone already, which is just as good here
            }
            List<Pair<Item, InventoryType>> items = itemDebit ? dumpInventory(bot) : List.of();
            if (BotHoldings.ledgerStamp(bot) != capturedAt) {
                return UNSTABLE_CAPTURE;
            }
            if (itemDebit) {
                ItemFactory.INVENTORY.saveItems(items, bot.getId(), con);
            }
            return capturedAt;
        });
    }

    /**
     * Saves every bot in the collection, reporting the ones that could not be written. The ledger is kept for a
     * bot whose value changed while its row was being written: the next sweep writes that change, which a
     * discard would have dropped while the row above it does not hold it.
     */
    public static int saveAll(Collection<Character> characters) {
        return saveAll(characters, false);
    }

    /**
     * @param lastWrite true when no later write can happen, as at server shutdown. A bot whose row cannot be
     *        written then takes the debit route - retried, and its stale stored claim destroyed if the retry
     *        fails - instead of being left with a row that a restart would restore on top of the value the bot
     *        already gave away. Mid-run the deltas are simply kept, because a later sweep retries them.
     */
    private static int saveAll(Collection<Character> characters, boolean lastWrite) {
        int saved = 0;
        for (Character chr : characters) {
            if (chr == null || !BotHelpers.isBot(chr)) {
                continue;
            }
            try {
                int captured = save(chr);
                saved++;
                if (captured == NO_STAMP || BotHoldings.discardIfUnchanged(chr, captured)) {
                    // Same rule as saveThenSettle: once the value is durable, the ledger must not hand it to
                    // the world a second time. The comparison and the removal are one step, so a delta that
                    // arrived after the capture stays and takes the changed route below.
                    continue;
                }
                if (!lastWrite) {
                    log.warn("Bot {} ({}) changed while its row was being written, so its deltas are kept for"
                            + " the next write", chr.getId(), chr.getName());
                    continue;
                }
                // There is no next write: this is the process's last. A delta the row missed - a debit the
                // recipient still holds, a credit the bot earned - dies with the ledger, so it is written or
                // taken down instead of being left for a restart to get wrong.
                log.warn("Bot {} ({}) changed while the last row of the process was being written, so that row"
                        + " does not record it", chr.getId(), chr.getName());
                recordDebit(chr);
                continue;
            } catch (Exception e) {
                log.warn("Could not save bot {} ({})", chr.getId(), chr.getName(), e);
            }
            if (lastWrite && BotHoldings.hasUnpersistedDebit(chr)) {
                recordDebit(chr);
            }
        }
        return saved;
    }


    /**
     * The shutdown sweep: every bot is settled and saved before the worlds go away. An open trade window
     * is closed first - it holds items and mesos out of both wallets, and the player on the other side is
     * saved later in the shutdown sequence, so an open window would be written out as a loss. Recruit
     * windows, movement goals and pacing are in-memory commitments whose owners are gone with the
     * process: they are dropped, not settled, because nothing of theirs is held out of a wallet.
     */
    public static int settleAndSaveAll(Collection<Character> characters) {
        for (Character chr : characters) {
            if (chr == null || !BotHelpers.isBot(chr)) {
                continue;
            }
            closeOpenWindow(chr);
        }
        // The last write of the process: a bot whose row will not write must not be left claiming value it has
        // already given away, because no later sweep can correct it.
        return saveAll(characters, true);
    }

    /** Closes a bot's trade window through the trade's own cancel path, which refunds both sides. */
    static void closeOpenWindow(Character bot) {
        if (bot.getTrade() == null) {
            return;
        }
        try {
            Trade.cancelTrade(bot, Trade.TradeResult.PARTNER_CANCEL);
        } catch (Exception e) {
            log.warn("Could not close the trade window of bot {} ({})", bot.getId(), bot.getName(), e);
        }
    }

    private static Map<Integer, Persisted> roster() {
        synchronized (ROSTER_LOCK) {
            if (!rosterLoaded) {
                try (Connection con = connectionSource.get();
                     PreparedStatement ps = con.prepareStatement(SELECT_ROSTER);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Persisted row = read(rs);
                        roster.put(row.botId, row);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("Could not read the bot store", e);
                }
                rosterLoaded = true;
                log.info("Bot store holds {} bots", roster.size());
            }
            return roster;
        }
    }

    /** Every stored row, as of the last read or write in this run (the GM read-out, {@link BotStoreReport}). */
    static Collection<Persisted> storedRows() {
        return roster().values();
    }

    /** Whether this bot's stored items could not be read this run, so it is not writing its inventory. */
    static boolean isHoldingsUnavailable(int charId) {
        return holdingsUnavailable.contains(charId);
    }

    /** Remembers a just-written bot so later adoptions in this run see it without another read. */
    private static void remember(int botId, Character bot) {
        roster.put(botId, of(bot));
    }

    private static int botId(Character bot) {
        return bot.getId() - BOT_ID_BASE;
    }

    /**
     * Writes the bot's tabs through the shared item path. A bot whose stored items could not be read this
     * run fails its whole write instead: the item tables are rewritten per tab, so writing now would
     * replace the stored items with the empty tabs the bot is carrying, and the callers treat "the write
     * succeeded" as "the value is durable" and drop the bot's ledger. Failing here sends the ledger to
     * {@link BotHoldings#settle(Character)} instead, so what the bot earned is handed to the map rather
     * than destroyed.
     *
     * <p>The exception is a bot that has given value away (see
     * {@link BotHoldings#hasUnpersistedDebit(Character)}): for it the same refusal is the minting direction,
     * because the stored holdings cannot be corrected while the value they claim is already in someone else's
     * hands, so a write that destroys the unreadable baseline is the right failure.
     */
    private static int writeHoldings(Character bot, Connection con, int capturedAt) throws SQLException {
        if (holdingsUnavailable.contains(bot.getId())) {
            if (!BotHoldings.hasUnpersistedDebit(bot)) {
                throw new SQLException("Not writing the inventory of bot " + bot.getId() + " (" + bot.getName()
                        + "): its stored holdings could not be loaded, so the write is failed and what it holds"
                        + " is settled onto its map");
            }
            // The bot holds less than its stored row claims - a payout, a purchase, an item destroyed while
            // held - and its stored holdings cannot be read, so every write would fail and the row would
            // restore that value on the next boot, on top of what the other side already holds. Overwriting
            // the unreadable baseline destroys it, and destroying is the direction to err in here: the
            // alternative mints value that no later write can take back, while what is lost this way is only
            // what this bot's own untouched baseline claimed.
            log.warn("Writing the holdings of bot {} ({}) over stored holdings that could not be read: it holds"
                    + " value its stored row would otherwise restore", bot.getId(), bot.getName());
        }
        List<Pair<Item, InventoryType>> items = dumpInventory(bot);
        // The capture is complete. If the stamp still matches the one taken before the row's scalars, nothing
        // changed across the whole capture, so this stamp brackets the state in the row: a delta ledgered at or
        // before it may be in the row, a later one is certainly not, and a caller comparing the bot's stamp
        // after the commit against this one can tell the two apart. If it does not match, the delta landed in
        // between - after the scalars were read, before the tabs were - and the capture describes no one
        // moment. That is not something a caller can be handed: a stamp read after the capture began would let
        // it treat such a delta as recorded, and a debit treated that way is forgiven while its row keeps
        // claiming the value the counterparty durably holds (a mint). The capture is therefore refused instead
        // and the write is retried from scratch - not kept-and-settled, which duplicates a credit the row does
        // hold - until either a capture brackets one moment or the attempt budget runs out.
        if (BotHoldings.ledgerStamp(bot) != capturedAt) {
            return UNSTABLE_CAPTURE;
        }
        ItemFactory.INVENTORY.saveItems(items, bot.getId(), con);
        return capturedAt;
    }

    private static Persisted of(Character bot) {
        long now = System.currentTimeMillis();
        return new Persisted(bot.getId() - BOT_ID_BASE, bot.getName(), bot.getTier().name(), bot.getLevel(),
                Math.abs(bot.getExp()), bot.getMeso(), bot.getJob().getId(), bot.getSkinColor().getId(),
                bot.getGender(), bot.getHair(), bot.getFace(), bot.getMapId(), bot.getPosition().x,
                bot.getPosition().y, bot.getInventory(InventoryType.EQUIP).getSlotLimit(),
                bot.getInventory(InventoryType.USE).getSlotLimit(),
                bot.getInventory(InventoryType.SETUP).getSlotLimit(),
                bot.getInventory(InventoryType.ETC).getSlotLimit(), now, now);
    }

    static Persisted read(ResultSet rs) throws SQLException {
        return new Persisted(rs.getInt("BotId"), rs.getString("Name"), rs.getString("Tier"),
                rs.getInt("Level"), rs.getInt("Exp"), rs.getInt("Meso"), rs.getInt("Job"), rs.getInt("Skin"),
                rs.getInt("Gender"), rs.getInt("Hair"), rs.getInt("Face"), rs.getInt("MapId"),
                rs.getInt("PosX"), rs.getInt("PosY"), rs.getInt("EquipSlots"), rs.getInt("UseSlots"),
                rs.getInt("SetupSlots"), rs.getInt("EtcSlots"), rs.getLong("CreatedAt"), rs.getLong("SavedAt"));
    }

    /** Binds a row's 18 mutable columns, starting at {@code first}. {@code CreatedAt} is never rewritten. */
    static void bindMutable(PreparedStatement ps, Persisted row, int first) throws SQLException {
        ps.setString(first, row.name);
        ps.setString(first + 1, row.tier);
        ps.setInt(first + 2, row.level);
        ps.setInt(first + 3, row.exp);
        ps.setInt(first + 4, row.meso);
        ps.setInt(first + 5, row.job);
        ps.setInt(first + 6, row.skin);
        ps.setInt(first + 7, row.gender);
        ps.setInt(first + 8, row.hair);
        ps.setInt(first + 9, row.face);
        ps.setInt(first + 10, row.mapId);
        ps.setInt(first + 11, row.posX);
        ps.setInt(first + 12, row.posY);
        ps.setInt(first + 13, row.equipSlots);
        ps.setInt(first + 14, row.useSlots);
        ps.setInt(first + 15, row.setupSlots);
        ps.setInt(first + 16, row.etcSlots);
        ps.setLong(first + 17, row.savedAt);
    }

    /**
     * Snapshots every stored tab: the state each item is in while this call holds that tab's lock, not the
     * items themselves. {@code Inventory.list()} is a live view of the tab, not a copy, and a casino payout
     * removes its chips from the payer's inventory on a thread of its own while the payer keeps ticking, so
     * reading it unlocked can both commit an item the payout then removes - which a restart would restore as an
     * extra one - and throw {@link java.util.ConcurrentModificationException} half way through, which in the
     * despawn path flips a bot onto the failed-write route.
     *
     * <p>The items are detached here rather than at statement time for the same reason: {@code ItemFactory}
     * binds position, quantity, owner, pet, flag, expiry and giver when the INSERT runs, which is after the lock
     * is gone, and a payout that removed a held item in between would otherwise be written as it stood before
     * the removal. The copies carry exactly the fields the write binds ({@link #copyForWrite(Item)}), so a row
     * describes one moment of the bot's holdings.
     *
     * <p>What this does not cover: a change made to the holdings after the copies were taken is not in the row.
     * That is what the ledger stamp is for - a change that came through this accounting path bumps it, and a
     * writer that sees a different stamp keeps the deltas instead of discarding them with a row that does not
     * record them (see {@link BotHoldings#ledgerStamp(Character)}). The lock is released before the statement
     * runs, so it never spans the DB.
     */
    private static List<Pair<Item, InventoryType>> dumpInventory(Character bot) {
        List<Pair<Item, InventoryType>> items = new ArrayList<>();
        for (InventoryType type : STORED_TYPES) {
            Inventory inv = bot.getInventory(type);
            inv.lockInventory();
            try {
                for (Item item : inv.list()) {
                    items.add(new Pair<>(copyForWrite(item), type));
                }
            } finally {
                inv.unlockInventory();
            }
        }
        return items;
    }

    /**
     * Detaches one held item from its inventory, so the write cannot be changed by a later mutation of the live
     * object. This is the inventory code's own detach ({@code Item.copy()}, which {@code Equip} overrides to
     * carry its stats) plus the one field it leaves behind that the write binds: the giver.
     */
    private static Item copyForWrite(Item item) {
        Item copy = item.copy();
        copy.setGiftFrom(item.getGiftFrom());
        // Equip.copy() does not carry the ring id the equipment insert binds, and it has other callers whose
        // meaning must not change, so the one field this write needs is added here.
        if (copy instanceof Equip copiedEquip && item instanceof Equip heldEquip) {
            copiedEquip.setRingId(heldEquip.getRingId());
        }
        return copy;
    }
}
