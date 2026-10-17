package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import server.CashShop;
import server.ItemInformationProvider;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The accounting path for everything a bot owns.
 * <p>
 * Bots are real {@link Character}s, but the single shared bot {@code Client} has no player attached to it. Every
 * {@code InventoryManipulator} entry point starts from {@code client.getPlayer()} and therefore cannot credit
 * a bot, so anything a bot gains or gives away goes through here with its {@link Character} passed explicitly.
 * <p>
 * Every change is recorded in a per-bot ledger of what this path added (itemId to quantity, plus a meso
 * delta). The ledger is the fallback for value that would otherwise be destroyed: {@link #settle(Character)}
 * drops it where the bot stands, which is what a bot's holdings do when the bot cannot be persisted
 * ({@link BotStore#saveThenSettle}), while {@link #discard(Character)} forgets it once the bot's state is
 * durable and the value must not be handed out a second time.
 */
public final class BotHoldings {

    private BotHoldings() {
    }

    // Weakly keyed so a bot that is dropped without ever settling (an exception mid-despawn, a discarded test
    // character) does not pin its Character in a static map forever.
    private static final Map<Character, Ledger> ledgers = Collections.synchronizedMap(new WeakHashMap<>());

    // Stack size used when WZ item data is unavailable (headless runs). 100 is the same default the item data
    // reader itself falls back to for a non-equip item without an explicit slotMax.
    private static final short DEFAULT_SLOT_MAX = 100;

    // One bot's ledger is written from more than one thread: its own tick thread (loot, offscreen accrual), the
    // packet thread of a player it is trading with, and the thread of a shopper in its merchant or shop. The
    // synchronizedMap above protects the lookup only, so the deltas themselves are synchronized here.
    private static final class Ledger {
        private final Map<Integer, Integer> itemDeltas = new LinkedHashMap<>();
        private int mesoDelta;

        synchronized void addItem(int itemId, int quantity) {
            itemDeltas.merge(itemId, quantity, Integer::sum);
        }

        synchronized void addMeso(int amount) {
            mesoDelta += amount;
        }

        synchronized Map<Integer, Integer> items() {
            return new LinkedHashMap<>(itemDeltas);
        }

        synchronized int meso() {
            return mesoDelta;
        }
    }

    /** Drops every ledger this test JVM recorded, so one test cannot inherit another's deltas. */
    static void clearForTest() {
        ledgers.clear();
    }

    private static Ledger ledger(Character bot) {
        return ledgers.computeIfAbsent(bot, ignored -> new Ledger());
    }

    private static short slotMaxFor(int itemId) {
        ItemInformationProvider itemData = itemData();
        if (itemData == null) {
            return DEFAULT_SLOT_MAX;
        }
        short slotMax = itemData.getSlotMax(itemId);
        return slotMax > 0 ? slotMax : DEFAULT_SLOT_MAX;
    }

    private static boolean isPickupRestricted(int itemId) {
        ItemInformationProvider itemData = itemData();
        return itemData != null && itemData.isPickupRestricted(itemId);
    }

    // Item data is WZ-backed and its provider also loads a DB row counter on startup, so it only exists on a
    // live server. The accounting path still has to work wherever a bot does — and a bot standing next to a
    // player whose server has no item data must not lose the loot — so an unavailable provider means "no item
    // data": the item data reader's own 100 slot-max default, and nothing known to be one-of-a-kind.
    private static ItemInformationProvider itemData() {
        try {
            return ItemInformationProvider.getInstance();
        } catch (LinkageError | RuntimeException unavailableItemData) {
            return null;
        }
    }

    /**
     * Credits a looted drop to the bot the way {@code Character.pickupItem} does for a player: mesos, NX cash
     * for an NX card, otherwise the dropped item itself.
     *
     * @return false when the bot cannot take the drop (no inventory space, no room in the meso counter, no cash
     * shop). The caller must leave the drop on the floor in that case instead of deleting it.
     */
    public static boolean creditDrop(Character bot, MapItem drop) {
        if (drop == null || drop.isPickedUp()) {
            return false;
        }
        if (drop.getMeso() > 0) {
            return grantMeso(bot, drop.getMeso());
        }
        if (ItemId.isNxCard(drop.getItemId())) {
            return grantNxCard(bot, drop.getItemId());
        }
        Item dropItem = drop.getItem();
        return dropItem != null && grantItem(bot, dropItem);
    }

    /** Non-mutating counterpart of {@link #creditDrop}: true when the bot could take this drop right now. */
    public static boolean canCreditDrop(Character bot, MapItem drop) {
        if (drop == null || drop.isPickedUp()) {
            return false;
        }
        if (drop.getMeso() > 0) {
            return bot.canHoldMeso(drop.getMeso());
        }
        if (ItemId.isNxCard(drop.getItemId())) {
            return bot.getCashShop() != null;
        }
        Item dropItem = drop.getItem();
        return dropItem != null && canHold(bot, dropItem);
    }

    /** True when {@code item} currently fits in the bot's inventory, filling existing stacks before new slots. */
    public static boolean canHold(Character bot, Item item) {
        Inventory inv = bot.getInventory(item.getInventoryType());
        inv.lockInventory();
        try {
            return canHoldLocked(bot, inv, item);
        } finally {
            inv.unlockInventory();
        }
    }

    /**
     * Credits {@code item} to the bot: existing stacks are topped up to the item's slot max and the remainder
     * spills into free slots, the same distribution {@code InventoryManipulator} gives a player, minus the
     * update packets.
     *
     * @return false when the whole quantity does not fit; nothing is granted and {@code item} is left untouched.
     */
    public static boolean grantItem(Character bot, Item item) {
        Inventory inv = bot.getInventory(item.getInventoryType());
        inv.lockInventory();
        try {
            if (!canHoldLocked(bot, inv, item)) {
                return false;
            }
            applyGrant(inv, item);
            ledger(bot).addItem(item.getItemId(), (int) item.getQuantity());
            return true;
        } finally {
            inv.unlockInventory();
        }
    }

    /** Credits meso to the bot; false when the amount is not grantable without overflowing the meso counter. */
    public static boolean grantMeso(Character bot, int amount) {
        if (amount <= 0 || !bot.canHoldMeso(amount)) {
            return false;
        }
        bot.gainMeso(amount, false, false, false);
        ledger(bot).addMeso(amount);
        return true;
    }

    /**
     * Notes mesos that left the bot's wallet for a bot-owned reason outside {@link #spendMeso}: a trade offer
     * debits the wallet through {@code Trade.setMeso}. Recording it keeps the ledger equal to what the bot
     * really holds, so {@link #settle(Character)} cannot hand mesos back that were already paid away.
     */
    public static void noteMesoSpent(Character bot, int amount) {
        if (amount > 0) {
            ledger(bot).addMeso(-amount);
        }
    }

    /** Reverses {@link #noteMesoSpent} when a trade offer comes back, as {@code Trade.cancel} refunds the wallet. */
    public static void noteMesoRefund(Character bot, int amount) {
        if (amount > 0) {
            ledger(bot).addMeso(amount);
        }
    }

    /**
     * Notes mesos that reached the wallet for a bot-owned sale: a bot that owns a {@code PlayerShop} is paid by
     * {@code owner.gainMeso} inside {@code PlayerShop.buy} and {@code PlayerShop.botBuy}, which credit the owner
     * directly because for a player that wallet is already durable. Recording it keeps the ledger equal to what
     * the bot really holds, so {@link #settle(Character)} hands the sale proceeds back to the world instead of
     * losing them - and the player-side credit path stays exactly what it was.
     *
     * <p>No-op for a real player: the shops these call sites sit in are owned by players too, and their wallet
     * needs no ledger.
     */
    public static void noteMesoEarned(Character character, int amount) {
        if (amount > 0 && BotHelpers.isBot(character)) {
            ledger(character).addMeso(amount);
        }
    }

    /** Debits meso from the bot; false when it cannot afford the amount, leaving its mesos untouched. */
    public static boolean spendMeso(Character bot, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("spendMeso amount cannot be negative: " + amount);
        }
        if (bot.getMeso() < amount) {
            return false;
        }
        bot.gainMeso(-amount, false, false, false);
        ledger(bot).addMeso(-amount);
        return true;
    }

    /**
     * Forgets a bot's deltas without dropping anything: the value they stand for is already durable, so a
     * later settle must not hand the same value to the world a second time.
     */
    public static void discard(Character bot) {
        ledgers.remove(bot);
    }

    /**
     * Hands everything this accounting path added back to the world: the net item and meso deltas are dropped
     * where the bot stands, so a leaving bot does not take its holdings with it. Negative deltas are value the
     * bot already gave away (shop purchases, meso handed to a trade partner) and stay as they were spent.
     * <p>
     * Callers must settle while the bot is still on the map it is leaving; a bot with no map keeps its ledger
     * until it has somewhere to settle onto.
     */
    public static void settle(Character bot) {
        Ledger ledger = ledgers.get(bot);
        if (ledger == null) {
            return;
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        ledgers.remove(bot);

        for (Map.Entry<Integer, Integer> itemDelta : ledger.items().entrySet()) {
            int itemId = itemDelta.getKey();
            int quantity = itemDelta.getValue();
            short slotMax = slotMaxFor(itemId);
            while (quantity > 0) {
                Item drop = new Item(itemId, (short) 0, (short) Math.min(quantity, slotMax), -1);
                quantity -= drop.getQuantity();
                map.spawnItemDrop(bot, bot, drop, bot.getPosition(), true, true);
            }
        }

        int mesoDelta = ledger.meso();
        if (mesoDelta > 0) {
            map.spawnMesoDrop(mesoDelta, bot.getPosition(), bot, bot, true, (byte) 2, (short) 0);
        }
    }

    private static boolean grantNxCard(Character bot, int nxCardId) {
        CashShop cashShop = bot.getCashShop();
        if (cashShop == null) {
            return false;   // no cash shop to credit: leave the card on the floor rather than delete it
        }
        cashShop.gainCash(CashShop.NX_CREDIT, nxCardId == ItemId.NX_CARD_100 ? 100 : 250);
        return true;
    }

    private static boolean canHoldLocked(Character bot, Inventory inv, Item item) {
        short quantity = item.getQuantity();
        if (quantity <= 0) {
            return false;
        }
        int itemId = item.getItemId();
        if (isPickupRestricted(itemId) && bot.haveItemWithId(itemId, true)) {
            return false;   // one-of-a-kind item the bot already owns
        }
        if (item.getInventoryType() == InventoryType.EQUIP) {
            return quantity == 1 && !inv.isFull();   // equips never stack
        }
        if (!stacks(itemId, item)) {
            return inv.getNumFreeSlot() > 0;   // rechargeables and pets take a slot of their own
        }

        short slotMax = slotMaxFor(itemId);
        int remaining = quantity;
        for (Item existing : inv.listById(itemId)) {
            if (remaining <= 0) {
                break;
            }
            if (!sameStack(item, existing)) {
                continue;
            }
            remaining -= Math.max(0, slotMax - existing.getQuantity());
        }

        int neededSlots = 0;
        while (remaining > 0) {
            remaining -= slotMax;
            neededSlots++;
        }
        return neededSlots <= inv.getNumFreeSlot();
    }

    private static void applyGrant(Inventory inv, Item item) {
        int itemId = item.getItemId();
        if (item.getInventoryType() == InventoryType.EQUIP) {
            inv.addItem(item);
            return;
        }

        if (!stacks(itemId, item)) {
            Item newItem = new Item(itemId, (short) 0, item.getQuantity(), item.getPetId());
            newItem.setExpiration(item.getExpiration());
            copyFlag(newItem, item);
            inv.addItem(newItem);
            return;
        }

        short slotMax = slotMaxFor(itemId);
        int remaining = item.getQuantity();
        for (Item existing : inv.listById(itemId)) {
            if (remaining <= 0) {
                break;
            }
            if (!sameStack(item, existing)) {
                continue;
            }
            int filled = Math.min(slotMax - existing.getQuantity(), remaining);
            if (filled <= 0) {
                continue;
            }
            existing.setQuantity((short) (existing.getQuantity() + filled));
            remaining -= filled;
            item.setPosition(existing.getPosition());
        }

        while (remaining > 0) {
            short stack = (short) Math.min(remaining, slotMax);
            remaining -= stack;

            Item newItem = new Item(itemId, (short) 0, stack, item.getPetId());
            newItem.setExpiration(item.getExpiration());
            newItem.setOwner(item.getOwner());
            copyFlag(newItem, item);
            inv.addItem(newItem);   // the capacity check above guarantees a free slot here
            item.setPosition(newItem.getPosition());
        }
    }

    // A stack is only a stack candidate on the same terms InventoryManipulator uses: same flag, same owner, and
    // a type that stacks at all.
    private static boolean stacks(int itemId, Item item) {
        return !ItemConstants.isRechargeable(itemId) && item.getPetId() == -1;
    }

    // Item.setFlag routes its value through the item data (it stamps account-sharing items), and that data is
    // not present in every environment a bot runs in: only pay for the call when there is a flag to carry over.
    private static void copyFlag(Item target, Item source) {
        short flag = source.getFlag();
        if (flag != 0) {
            target.setFlag(flag);
        }
    }

    private static boolean sameStack(Item item, Item existing) {
        return item.getFlag() == existing.getFlag() && item.getOwner().equals(existing.getOwner());
    }
}
