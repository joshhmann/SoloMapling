package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

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

/**
 * The roster (bot-persistence ticket 04): the population bots that are players rather than boot-plan
 * positions. A member has a {@link BotStore} row like any bot - identity, progression, holdings - and a
 * {@code botroster} row with what makes it a player: whether it still plays, its personality and persona, when
 * it usually plays, its home, and what it was doing when it logged out.
 *
 * <p>The roster is the identity: a member is loaded by its slot alone, with no wave, task or ordinal. Bots that
 * migration 026 enrolled keep their boot-plan slot (their items are stored under that character id); members
 * created later take slots from {@link BotStore#FIRST_ROSTER_SLOT} up.
 */
public final class BotRoster {

    private static final Logger log = LoggerFactory.getLogger(BotRoster.class);

    /** The boot-plan waves whose bots are roster members: training cohorts (8) and town presence (9). */
    public static final Set<Integer> POPULATION_WAVES = Set.of(8, 9);

    public enum Status {
        /** Plays: the director may log it in. */
        ACTIVE,
        /** Quit: kept, never scheduled, with a small chance of returning (ticket 10). */
        DORMANT
    }

    /** One member's roster row, as stored. The JSON columns are opaque here; their owners parse them. */
    public record Member(int botId, Status status, String personality, String persona, int playWindowStart,
                         int playWindowLen, int sessionMinutes, int homeMapId, String activity,
                         String activityState, long lastLoginAt, long lastLogoutAt, long enrolledAt) {

        public int charId() {
            return BotStore.BOT_ID_BASE + botId;
        }

        static Member enrolled(int botId, long now) {
            return new Member(botId, Status.ACTIVE, null, null, 0, 0, 0, 0, "", null, 0, 0, now);
        }

        public Member withStatus(Status status) {
            return new Member(botId, status, personality, persona, playWindowStart, playWindowLen, sessionMinutes,
                    homeMapId, activity, activityState, lastLoginAt, lastLogoutAt, enrolledAt);
        }

        Member withLogin(long at) {
            return new Member(botId, status, personality, persona, playWindowStart, playWindowLen, sessionMinutes,
                    homeMapId, activity, activityState, at, lastLogoutAt, enrolledAt);
        }

        /** This member with what it is doing now, and the logout time when it is leaving the world. */
        Member withSession(Activity now, long logoutAt) {
            return new Member(botId, status, personality, persona, playWindowStart, playWindowLen, sessionMinutes,
                    now.homeMapId() > 0 ? now.homeMapId() : homeMapId, now.name(), now.stateJson(), lastLoginAt,
                    logoutAt > 0 ? logoutAt : lastLogoutAt, enrolledAt);
        }

        /** What this member was doing when its row was last written, or null when nothing was recorded. */
        public Activity lastActivity() {
            if (activity == null || activity.isEmpty()) {
                return null;
            }
            return Activity.of(activity, homeMapId, activityState);
        }
    }

    /**
     * What a bot is doing, as far as resuming it needs (bot-persistence ticket 06): an activity name, its home
     * map, and a small state map (for a grinder, the map it trains on). Movement, FSM phase and pacing are not
     * here - they are rebuilt on login, like on any promotion.
     */
    public record Activity(String name, int homeMapId, Map<String, Object> state) {

        public static Activity of(String name, int homeMapId, String stateJson) {
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            if (stateJson != null && !stateJson.isBlank()) {
                try {
                    if (soloMapling.server.Json.parse(stateJson) instanceof Map<?, ?> parsed) {
                        parsed.forEach((k, v) -> state.put(String.valueOf(k), v));
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring unreadable activity state {}", stateJson);
                }
            }
            return new Activity(name, homeMapId, state);
        }

        /** An integer field of the state, or {@code fallback} when it is missing or not a number. */
        public int intState(String key, int fallback) {
            return state.get(key) instanceof Number n ? n.intValue() : fallback;
        }

        String stateJson() {
            return state == null || state.isEmpty() ? null : soloMapling.server.Json.write(state);
        }
    }

    static final String SELECT_ALL = "SELECT * FROM botroster";
    static final String UPSERT = "INSERT INTO botroster (BotId, Status, Personality, Persona, PlayWindowStart, "
            + "PlayWindowLen, SessionMinutes, HomeMapId, Activity, ActivityState, LastLoginAt, LastLogoutAt, "
            + "EnrolledAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
            + "Status = VALUES(Status), Personality = VALUES(Personality), Persona = VALUES(Persona), "
            + "PlayWindowStart = VALUES(PlayWindowStart), PlayWindowLen = VALUES(PlayWindowLen), "
            + "SessionMinutes = VALUES(SessionMinutes), HomeMapId = VALUES(HomeMapId), "
            + "Activity = VALUES(Activity), ActivityState = VALUES(ActivityState), "
            + "LastLoginAt = VALUES(LastLoginAt), LastLogoutAt = VALUES(LastLogoutAt)";

    private static final Object LOAD_LOCK = new Object();
    private static final Map<Integer, Member> members = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static volatile BotStore.ConnectionSource connectionSource;
    private static int highestRosterSlot = BotStore.FIRST_ROSTER_SLOT - 1;

    private BotRoster() {
    }

    /** Whether this slot belongs to a roster member. */
    public static boolean isMember(int botId) {
        return all().containsKey(botId);
    }

    /** This slot's roster row, or null when it is not a member. */
    public static Member member(int botId) {
        return all().get(botId);
    }

    /** Every member, active or dormant. */
    public static Collection<Member> members() {
        return all().values();
    }

    /** The members the director may log in. */
    public static List<Member> active() {
        List<Member> active = new ArrayList<>();
        for (Member member : members()) {
            if (member.status() == Status.ACTIVE) {
                active.add(member);
            }
        }
        return active;
    }

    /**
     * A slot for a brand-new member, above the whole boot plan and above every member already enrolled there.
     * The new member owns no rows until it is {@link #save saved}; its {@link BotStore} row is written by the
     * store's usual first creation.
     */
    public static int nextNewSlot() {
        all();
        synchronized (LOAD_LOCK) {
            return ++highestRosterSlot;
        }
    }

    /** Resume records armed at login, consumed by the bot's first tick (its FSM's init). */
    private static final Map<Integer, Activity> pendingResume = new ConcurrentHashMap<>();

    /**
     * A roster member has just been adopted into the world: stamp its login and arm its last activity so its
     * bot type can pick up where it stopped. The login is written with the member's next session record.
     */
    public static void recordLogin(int charId, long now, int waveMapId, int placedMapId, java.awt.Point placedAt) {
        Member member = member(charId - BotStore.BOT_ID_BASE);
        if (member == null) {
            return;
        }
        members.put(member.botId(), member.withLogin(now));
        loginPlaces.put(charId, new LoginPlace(placedMapId, placedAt.x, placedAt.y, now));
        Activity last = member.lastActivity();
        if (last == null) {
            // Enrolled by the migration, never written by a session yet: home is the town its wave spawns it in,
            // and it was training where it stands.
            last = new Activity("", waveMapId, Map.of(TRAIN_STATE, placedMapId));
        }
        pendingResume.put(charId, last);
    }

    /** Where a member was placed when it logged in this run, for the GM read-out and the proof harness. */
    public record LoginPlace(int mapId, int x, int y, long at) {
    }

    private static final Map<Integer, LoginPlace> loginPlaces = new ConcurrentHashMap<>();

    /** Where this bot logged in this run, or null when it is not a member or has not logged in. */
    public static LoginPlace loginPlace(int charId) {
        return loginPlaces.get(charId);
    }

    /** The activity-state key of the map a grinder trains on. */
    public static final String TRAIN_STATE = "train";

    /** The activity this bot logged out in, once: the second call returns null. */
    public static Activity takeResume(int charId) {
        return pendingResume.remove(charId);
    }

    /**
     * Writes what each roster member among {@code bots} is doing now (and, when they are leaving the world, the
     * logout time) in one batch. Non-members are skipped. Called next to every bot store write - the hourly
     * autosave, a despawn and the shutdown sweep - so a restart resumes each member from its last write.
     *
     * @return how many member rows were written
     */
    public static int recordSessions(Collection<client.Character> bots, long now, boolean loggingOut) {
        List<Member> updated = new ArrayList<>();
        for (client.Character bot : bots) {
            if (bot == null) {
                continue;
            }
            Member member = member(bot.getId() - BotStore.BOT_ID_BASE);
            if (member == null) {
                continue;
            }
            updated.add(member.withSession(activityOf(bot), loggingOut ? now : 0));
        }
        if (updated.isEmpty()) {
            return 0;
        }
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(UPSERT)) {
            for (Member member : updated) {
                bind(ps, member);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("Could not record the sessions of {} roster bots", updated.size(), e);
            return 0;
        }
        updated.forEach(BotRoster::remember);
        return updated.size();
    }

    private static Activity activityOf(client.Character bot) {
        BotSM brain = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(bot.getId());
        Activity activity = brain == null ? null : brain.activitySnapshot();
        return activity != null ? activity : new Activity("idle", 0, Map.of());
    }

    /** Writes a member's row, enrolling it if it is new. */
    public static void save(Member member) throws SQLException {
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(UPSERT)) {
            bind(ps, member);
            ps.executeUpdate();
        }
        remember(member);
    }

    /** Enrols a slot as an active member (a new player, ticket 10), unless it already is one. */
    public static Member enroll(int botId, long now) throws SQLException {
        Member existing = member(botId);
        if (existing != null) {
            return existing;
        }
        Member member = Member.enrolled(botId, now);
        save(member);
        return member;
    }

    private static void remember(Member member) {
        members.put(member.botId(), member);
        if (BotStore.isRosterSlot(member.botId())) {
            synchronized (LOAD_LOCK) {
                highestRosterSlot = Math.max(highestRosterSlot, member.botId());
            }
        }
    }

    private static Map<Integer, Member> all() {
        if (!loaded) {
            synchronized (LOAD_LOCK) {
                if (!loaded) {
                    try (Connection con = connections().get();
                         PreparedStatement ps = con.prepareStatement(SELECT_ALL);
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Member member = read(rs);
                            members.put(member.botId(), member);
                            if (BotStore.isRosterSlot(member.botId())) {
                                highestRosterSlot = Math.max(highestRosterSlot, member.botId());
                            }
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("Could not read the bot roster", e);
                    }
                    loaded = true;
                    log.info("Bot roster holds {} members", members.size());
                }
            }
        }
        return members;
    }

    static Member read(ResultSet rs) throws SQLException {
        return new Member(rs.getInt("BotId"), Status.valueOf(rs.getString("Status")), rs.getString("Personality"),
                rs.getString("Persona"), rs.getInt("PlayWindowStart"), rs.getInt("PlayWindowLen"),
                rs.getInt("SessionMinutes"), rs.getInt("HomeMapId"), rs.getString("Activity"),
                rs.getString("ActivityState"), rs.getLong("LastLoginAt"), rs.getLong("LastLogoutAt"),
                rs.getLong("EnrolledAt"));
    }

    static void bind(PreparedStatement ps, Member member) throws SQLException {
        ps.setInt(1, member.botId());
        ps.setString(2, member.status().name());
        ps.setString(3, member.personality());
        ps.setString(4, member.persona());
        ps.setInt(5, member.playWindowStart());
        ps.setInt(6, member.playWindowLen());
        ps.setInt(7, member.sessionMinutes());
        ps.setInt(8, member.homeMapId());
        ps.setString(9, member.activity() == null ? "" : member.activity());
        ps.setString(10, member.activityState());
        ps.setLong(11, member.lastLoginAt());
        ps.setLong(12, member.lastLogoutAt());
        ps.setLong(13, member.enrolledAt());
    }

    private static BotStore.ConnectionSource connections() {
        BotStore.ConnectionSource source = connectionSource;
        return source != null ? source : DatabaseConnection::getConnection;
    }

    /** Test seam: points the roster at a mock pool (null restores the real one) and forgets what it loaded. */
    static void useConnectionSourceForTest(BotStore.ConnectionSource source) {
        synchronized (LOAD_LOCK) {
            connectionSource = source;
            members.clear();
            pendingResume.clear();
            loginPlaces.clear();
            loaded = false;
            highestRosterSlot = BotStore.FIRST_ROSTER_SLOT - 1;
        }
    }
}
