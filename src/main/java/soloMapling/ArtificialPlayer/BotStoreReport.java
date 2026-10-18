package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.InventoryType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The GM read-out of the bot store ({@code !roster status}): what the store holds next to what is live, so
 * persistence can be checked in game without SQL. A field marked {@code *} differs between the live bot and
 * its row; that is normal between autosaves (it is progress the next write will record), and a difference
 * right after a save or a restart is the thing to look into.
 */
public final class BotStoreReport {

    private BotStoreReport() {
    }

    /** One line per fact about the whole store and the bots online now. */
    public static List<String> summary(Collection<Character> onlineBots) {
        Collection<BotStore.Persisted> rows = BotStore.storedRows();
        int planned = 0;
        for (BotStore.Persisted row : rows) {
            if (BotStore.isPlanned(row.botId)) {
                planned++;
            }
        }
        int adopted = 0;
        int unstored = 0;
        int unreadable = 0;
        for (Character bot : onlineBots) {
            if (BotStore.isAdoptedInThisRun(bot.getId())) {
                adopted++;
            }
            if (BotStore.persistedFor(bot.getId()) == null) {
                unstored++;
            }
            if (BotStore.isHoldingsUnavailable(bot.getId())) {
                unreadable++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("Bot store: %d rows (%d planned, %d on-demand)", rows.size(), planned,
                rows.size() - planned));
        lines.add(String.format("Online: %d bots - %d adopted from the store this run, %d minted fresh, %d not yet saved",
                onlineBots.size(), adopted, onlineBots.size() - adopted, unstored));
        if (unreadable > 0) {
            lines.add(String.format("WARNING: %d online bots could not load their stored items and are not writing them",
                    unreadable));
        }
        return lines;
    }

    /** The stored rows whose name matches, ignoring case (names are not unique). */
    public static List<Integer> storedIdsNamed(String name) {
        List<Integer> ids = new ArrayList<>();
        for (BotStore.Persisted row : BotStore.storedRows()) {
            if (row.name.equalsIgnoreCase(name)) {
                ids.add(row.charId());
            }
        }
        ids.sort(null);
        return ids;
    }

    /**
     * One bot, live and stored side by side. Either half may be missing: {@code live} is null for a stored bot
     * that is not online, and the stored half is absent for a bot this run has not written yet.
     */
    public static List<String> describe(int charId, Character live, long now) {
        return describe(charId, live, BotStore.persistedFor(charId), BotStore.isAdoptedInThisRun(charId),
                BotStore.isHoldingsUnavailable(charId), now);
    }

    static List<String> describe(int charId, Character live, BotStore.Persisted stored, boolean adopted,
                                 boolean holdingsUnavailable, long now) {
        List<String> lines = new ArrayList<>();
        String name = live != null ? live.getName() : stored != null ? stored.name : "?";
        int slot = charId - BotStore.BOT_ID_BASE;
        String origin;
        if (live == null) {
            origin = "offline";
        } else if (adopted) {
            origin = "adopted from the store this run";
        } else {
            origin = "minted fresh this run";
        }
        lines.add(String.format("%s (id %d, slot %d, %s) - %s", name, charId, slot,
                BotStore.isPlanned(slot) ? "planned" : "on-demand", origin));
        if (stored == null) {
            lines.add("No stored row: this bot has not been written yet.");
            if (live != null) {
                lines.add(String.format("live: Lv %d, exp %d, %,d meso, job %d, map %d (%d, %d)",
                        live.getLevel(), live.getExp(), live.getMeso(), live.getJob().getId(), live.getMapId(),
                        live.getPosition().x, live.getPosition().y));
            }
            return lines;
        }
        if (live == null) {
            lines.add(String.format("stored: Lv %d, exp %d, %,d meso, job %d, map %d (%d, %d)",
                    stored.level, stored.exp, stored.meso, stored.job, stored.mapId, stored.posX, stored.posY));
        } else {
            lines.add(field("Lv", live.getLevel(), stored.level) + "  " + field("exp", live.getExp(), stored.exp)
                    + "  " + field("job", live.getJob().getId(), stored.job));
            lines.add(field("meso", live.getMeso(), stored.meso) + "  " + field("map", live.getMapId(), stored.mapId)
                    + String.format("  pos live (%d, %d) / stored (%d, %d)", live.getPosition().x,
                    live.getPosition().y, stored.posX, stored.posY));
            lines.add(String.format("items live: equip %d, use %d, setup %d, etc %d, cash %d, worn %d",
                    count(live, InventoryType.EQUIP), count(live, InventoryType.USE),
                    count(live, InventoryType.SETUP), count(live, InventoryType.ETC),
                    count(live, InventoryType.CASH), count(live, InventoryType.EQUIPPED)));
            if (holdingsUnavailable) {
                lines.add("WARNING: stored items could not be read this run; this bot is not writing its items");
            }
        }
        lines.add(String.format("last saved %s, created %s", ago(stored.savedAt, now), ago(stored.createdAt, now)));
        return lines;
    }

    static String field(String label, long live, long stored) {
        if (live == stored) {
            return String.format(Locale.ROOT, "%s %,d", label, live);
        }
        return String.format(Locale.ROOT, "%s* %,d (stored %,d)", label, live, stored);
    }

    static String ago(long at, long now) {
        if (at <= 0) {
            return "never";
        }
        long seconds = Math.max(0, (now - at) / 1000);
        if (seconds < 120) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 120) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }

    private static int count(Character bot, InventoryType type) {
        return bot.getInventory(type).list().size();
    }
}
