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

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * ({@link BotStore#saveThenSettle}), while {@link #discardIfUnchanged(Character, int)} forgets it once the
 * bot's state is durable and the value must not be handed out a second time - the deltas it drops are only the
 * ones the write recorded, and it keeps the ones that arrived after that write, because those are in no row.
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
        private int stamp;

        synchronized void addItem(int itemId, int quantity) {
            itemDeltas.merge(itemId, quantity, Integer::sum);
            stamp++;
        }

        synchronized void addMeso(int amount) {
            mesoDelta += amount;
            stamp++;
        }

        synchronized Map<Integer, Integer> items() {
            return new LinkedHashMap<>(itemDeltas);
        }

        synchronized int meso() {
            return mesoDelta;
        }

        // How many times the deltas here have changed. A writer reads it before it captures a bot's state and
        // again once the row it built from that state is committed: a different value means the bot gave value
        // away (or took value in) while the write was in flight, so that change is not in the row and the deltas
        // must not be discarded with a row that does not record them.
        synchronized int stamp() {
            return stamp;
        }

        // True when the bot has given away more than it took in: value that left the world's accounts here
        // while the stored row still claims it.
        synchronized boolean hasDebit() {
            return mesoDelta < 0 || hasItemDebit();
        }

        // Whether it is the stored item claims, rather than the stored meso, that the deltas invalidate.
        synchronized boolean hasItemDebit() {
            for (int delta : itemDeltas.values()) {
                if (delta < 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Drops every ledger this test JVM recorded, so one test cannot inherit another's deltas. */
    static void clearForTest() {
        synchronized (ledgers) {   // the same monitor every other access takes
            ledgers.clear();
        }
    }

    /**
     * Every delta goes through here: the lookup and the mutation happen under the map's own monitor, so a delta
     * can never be applied to a ledger that {@link #discardIfUnchanged(Character, int)} has just dropped (the
     * delta would be invisible to whoever asked, which is how a mint or a destroy starts). The Ledger's own
     * monitor is taken inside this one - map monitor first, Ledger second, never the reverse.
     */
    private static void mutate(Character bot, Mutation mutation) {
        synchronized (ledgers) {
            mutation.apply(ledgers.computeIfAbsent(bot, ignored -> new Ledger()));
        }
    }

    /** One delta applied to one bot's ledger; see {@link #mutate(Character, Mutation)} for the locking contract. */
    @FunctionalInterface
    private interface Mutation {
        void apply(Ledger ledger);
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
        if (drop == null || drop.isPickedUp() || drop.isConjured()) {
            return false;   // a prop stands for no value taken from the world: the ledger must never take it
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
        if (drop == null || drop.isPickedUp() || drop.isConjured()) {
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
            mutate(bot, ledger -> ledger.addItem(item.getItemId(), (int) item.getQuantity()));
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
        mutate(bot, ledger -> ledger.addMeso(amount));
        return true;
    }

    /**
     * Notes mesos that left the bot's wallet for a bot-owned reason outside {@link #spendMeso}: a trade offer
     * debits the wallet through {@code Trade.setMeso}. Recording it keeps the ledger equal to what the bot
     * really holds, so {@link #settle(Character)} cannot hand mesos back that were already paid away.
     */
    public static void noteMesoSpent(Character bot, int amount) {
        if (amount > 0) {
            mutate(bot, ledger -> ledger.addMeso(-amount));
        }
    }

    /** Reverses {@link #noteMesoSpent} when a trade offer comes back, as {@code Trade.cancel} refunds the wallet. */
    public static void noteMesoRefund(Character bot, int amount) {
        if (amount > 0) {
            mutate(bot, ledger -> ledger.addMeso(amount));
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
            mutate(character, ledger -> ledger.addMeso(amount));
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
        mutate(bot, ledger -> ledger.addMeso(-amount));
        return true;
    }

    /**
     * Hands units of an item the bot is holding out to someone else: each unit leaves the bot's inventory and its
     * ledger and lands on the floor of the bot's map owned by the recipient. This is how a casino table pays a
     * winning hand out of the bets it collected - the house pays what it holds and mints nothing - so the amount
     * paid is capped by the bot's stock. The debit is ledgered and the units are destocked before the drops are
     * created, so a failure between the two destroys value rather than minting it.
     *
     * <p>It pays by item id, so each unit becomes a plain item of that id: a unit whose value lives in the object
     * itself - an equipment with its stats, a pet - loses that. {@link #payOutItem(Character, Item, Character,
     * Point)} is the call for handing over a particular instance.
     *
     * @return units actually paid; 0 when the bot holds none of the item, has no map, or quantity is not positive
     */
    public static int payOut(Character bot, int itemId, int quantity, Character recipient, Point position) {
        if (quantity <= 0) {
            return 0;
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return 0;
        }
        Inventory inv = bot.getInventory(ItemConstants.getInventoryType(itemId));
        List<Item> taken = new ArrayList<>();
        int paid = 0;
        inv.lockInventory();
        try {
            for (Item stack : inv.listById(itemId)) {
                if (paid >= quantity) {
                    break;
                }
                int take = Math.min(stack.getQuantity(), quantity - paid);
                if (take <= 0) {
                    continue;
                }
                // removeItem can empty this stack out of the inventory, but the object stays the identity the
                // units taken from it belong to: same owner, same flag, same giver, same expiry.
                taken.add(stack);
                inv.removeItem(stack.getPosition(), (short) take, false);
                paid += take;
            }
        } finally {
            inv.unlockInventory();
        }
        if (paid == 0) {
            return 0;   // the house holds none of this denomination: it pays less instead of minting chips
        }
        int units = paid;   // the destock loop's counter is not effectively final, a lambda needs one that is
        mutate(bot, ledger -> ledger.addItem(itemId, -units));
        int landed = 0;
        for (int i = 0; i < paid; i++) {
            MapItem drop = map.spawnItemDropNoExpire(bot, recipient, new Item(itemId, (short) 0, (short) 1, -1),
                    position, false, false, false);
            if (drop != null) {
                drop.setPermanentOwner(true);
                landed++;
            }
        }
        if (landed < paid) {
            // The drops came back null, which is what a map with FieldLimit.DROP_LIMIT does: it broadcasts the
            // disappearing-drop animation and registers nothing (MapleMap.disappearingItemDrop), so those units
            // never reach the recipient. A map property is not the house's loss to take: the units that never
            // landed go back into the payer's inventory, so what it holds is exactly what it has, and only the
            // units the recipient really got count as paid out. What restock could not put back is gone, and the
            // ledger keeps it debited: a unit that is not in the inventory must never be credited to it.
            int restored = restock(inv, taken, itemId, paid - landed);
            if (restored > 0) {
                mutate(bot, ledger -> ledger.addItem(itemId, restored));
            }
        }
        return landed;
    }

    /**
     * Hands one specific held instance to someone else: that exact item leaves the bot's inventory and ledger and
     * lands on the floor of the bot's map owned by the recipient. Used where the instance itself carries value the
     * recipient expects - TutorialBot hands back the real weapon it looted and scrolled, whose stats and owner
     * name live on that object - so the drop is the instance, not a copy re-created from its id. This keeps the
     * bot from holding aliases of what it just gave away, which {@link #settle(Character)} would otherwise hand
     * to the world a second time.
     *
     * <p>The removal is ledgered before the drop is created, so a failure between the two destroys value rather
     * than minting it.
     *
     * @return true when the instance left the bot and landed on the floor; false when the bot no longer holds
     *         that instance or has no map, leaving the item untouched
     */
    public static boolean payOutItem(Character bot, Item item, Character recipient, Point position) {
        if (item == null) {
            return false;
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return false;
        }
        Inventory inv = bot.getInventory(item.getInventoryType());
        Item held = null;
        inv.lockInventory();
        try {
            for (Item candidate : inv.list()) {
                if (candidate == item) {
                    held = candidate;
                    break;
                }
            }
            if (held == null) {
                return false;   // the bot already gave this instance away (or never held it)
            }
            inv.removeSlot(held.getPosition());
        } finally {
            inv.unlockInventory();
        }
        Item gone = held;   // the scan's local is reassigned, a lambda needs one that is not
        mutate(bot, ledger -> ledger.addItem(gone.getItemId(), -gone.getQuantity()));
        MapItem drop = map.spawnItemDropNoExpire(bot, recipient, held, position, false, false, false);
        if (drop == null) {
            // The map refused the drop: FieldLimit.DROP_LIMIT registers nothing and only plays the
            // disappearing-drop animation (MapleMap.disappearingItemDrop), so this instance never reached the
            // recipient. It is still the bot's, so it goes back into the inventory - the same object, so an
            // equip keeps its stats - and the ledger debit for it is undone. If there is no room for it the
            // instance is gone for good and the debit stays: a payout that cannot be completed destroys the
            // value rather than handing it out twice.
            if (putBack(inv, held)) {
                mutate(bot, ledger -> ledger.addItem(gone.getItemId(), gone.getQuantity()));
            }
            return false;
        }
        drop.setPermanentOwner(true);
        return true;
    }

    /**
     * Puts units that never landed back into the payer's inventory, carrying the identity of the stacks the
     * payout took them from - owner, flag, giver, expiry - so they rejoin those stacks instead of becoming new
     * ones. Callers invoke it right after those units left this same inventory, which is what makes room for
     * them, but another thread can have taken that room since (casino payouts run off the bot's tick thread),
     * so this reports the units it really put back: a caller may only credit those, never the units it asked
     * for. What could not be put back is gone, which is the direction to lose in - the other direction is
     * value the ledger credits to an inventory that does not hold it, which {@link #settle(Character)} then
     * hands to the world on top of the value that is already there.
     *
     * @param taken    the stacks the payout took units from, in the order it took them
     * @param quantity how many units came back from the map
     * @return how many of those units are in the inventory again
     */
    private static int restock(Inventory inv, List<Item> taken, int itemId, int quantity) {
        if (quantity <= 0 || taken.isEmpty()) {
            return 0;
        }
        inv.lockInventory();
        try {
            if (inv.getType() == InventoryType.EQUIP) {
                // An equip's value is in the object, not in its id, and every equip is quantity 1
                // (canHoldLocked): hand the very instances that were taken back, one per slot, as many as
                // there is room for - never collapse several of them into one instance.
                int restored = 0;
                for (Item equip : taken) {
                    if (restored >= quantity) {
                        break;
                    }
                    equip.setQuantity((short) 1);
                    if (inv.addItem(equip) != -1) {
                        restored++;
                    }
                }
                return restored;
            }
            int remaining = quantity;
            int restored = 0;
            // The units went out of these stacks in the order they were taken, so they go back the same way,
            // the last stack first: every unit rejoins the stack whose identity it was taken from, while that
            // identity (owner, flag, giver) is still known rather than approximated from the item id.
            for (int i = taken.size() - 1; i >= 0 && remaining > 0; i--) {
                Item origin = taken.get(i);
                if (inv.getItem(origin.getPosition()) != origin) {
                    continue;   // that stack was emptied and left the inventory: its room is a free slot now
                }
                int room = slotMaxFor(itemId) - origin.getQuantity();
                int back = Math.min(room, remaining);
                if (back <= 0) {
                    continue;
                }
                origin.setQuantity((short) (origin.getQuantity() + back));
                remaining -= back;
                restored += back;
            }
            if (remaining > 0) {
                restored += spill(inv, taken.get(0), itemId, remaining);
            }
            return restored;
        } finally {
            inv.unlockInventory();
        }
    }

    /**
     * Puts units back into fresh stacks, for the ones whose own stack filled up or left the inventory. This is
     * {@link #applyGrant(Inventory, Item)}'s placement with a count: room is not knowable up front here (the
     * room the payout freed can have been taken since), so a stack {@link Inventory#addItem(Item)} refuses
     * simply stays out of the inventory and is reported as not restored.
     *
     * @return how many of the units are in the inventory
     */
    private static int spill(Inventory inv, Item template, int itemId, int quantity) {
        int slotMax = slotMaxFor(itemId);
        if (slotMax <= 0) {
            return 0;
        }
        if (!stacks(itemId, template)) {
            Item returned = new Item(itemId, (short) 0, (short) quantity, template.getPetId());
            returned.setExpiration(template.getExpiration());
            returned.setOwner(template.getOwner());
            returned.setGiftFrom(template.getGiftFrom());
            copyFlag(returned, template);
            return inv.addItem(returned) == -1 ? 0 : quantity;
        }
        int restored = 0;
        while (quantity > 0) {
            short stack = (short) Math.min(quantity, slotMax);
            Item returned = new Item(itemId, (short) 0, stack, template.getPetId());
            returned.setExpiration(template.getExpiration());
            returned.setOwner(template.getOwner());
            returned.setGiftFrom(template.getGiftFrom());
            copyFlag(returned, template);
            if (inv.addItem(returned) == -1) {
                break;   // no free slot: the rest stays out of the inventory
            }
            quantity -= stack;
            restored += stack;
        }
        return restored;
    }

    /**
     * Adds one held instance back into its inventory.
     *
     * @return false when the inventory has no room for it and the instance is lost
     */
    private static boolean putBack(Inventory inv, Item item) {
        inv.lockInventory();
        try {
            return inv.addItem(item) != -1;
        } finally {
            inv.unlockInventory();
        }
    }

    /**
     * Strikes one held instance out of the bot's inventory and ledger together, for value the world destroyed
     * while the bot held it: a cursed scroll is the case this exists for ({@code scrollEquipWithId} returns null
     * after destroying the equip), and anything else that removes an item the bot already accounted for belongs
     * here too. The ledger debit means the bot no longer holds what it was credited for; if the item came from
     * its durable row rather than this run's credits, {@link #hasUnpersistedDebit(Character)} reports it and
     * {@code BotStore} records the loss instead of a restart restoring it.
     *
     * <p>Distinct from {@link #payOutItem(Character, Item, Character, Point)}, which hands an instance to
     * someone else: nothing leaves the bot here, the value is gone.
     *
     * @return true when the bot held the instance and it is now out of both the inventory and the ledger
     */
    public static boolean destroyHeldItem(Character bot, Item item) {
        if (item == null) {
            return false;
        }
        Inventory inv = bot.getInventory(item.getInventoryType());
        Item held = null;
        inv.lockInventory();
        try {
            for (Item candidate : inv.list()) {
                if (candidate == item) {
                    held = candidate;
                    break;
                }
            }
            if (held == null) {
                return false;   // the bot does not hold this instance: nothing to strike out
            }
            inv.removeSlot(held.getPosition());
        } finally {
            inv.unlockInventory();
        }
        Item gone = held;   // the scan's local is reassigned, a lambda needs one that is not
        mutate(bot, ledger -> ledger.addItem(gone.getItemId(), -gone.getQuantity()));
        return true;
    }

    /**
     * True when this bot holds less than its stored row claims: a debit - a payout, a purchase, mesos handed to
     * a trade partner, an item destroyed while held - that no durable write has recorded yet.
     *
     * <p>Such a debit must never be forgotten. The row it came from still claims that value, so a restart would
     * restore it on top of what the other side already holds, minting the same value twice. {@code BotStore}
     * therefore treats this as "the bot is dirty": it retries the write instead of settling the deltas onto the
     * map, and it writes the holdings even when they could not be read, because leaving the stale row alone is
     * the minting direction while overwriting it is the destroying one.
     *
     * <p>With this, every read of a ledger (this predicate, {@link #hasUnpersistedItemDebit(Character)},
     * {@link #ledgerStamp(Character)}) and every write ({@link #mutate(Character, Mutation)},
     * {@link #discardIfUnchanged(Character, int)}, {@link #settle(Character)}) happens under the {@code ledgers}
     * monitor, so no read can miss a delta and no read-then-act pair is left for one to slip through: the same
     * interleaving is impossible by construction at the capture, the discard and the read, not merely unlikely.
     */
    public static boolean hasUnpersistedDebit(Character bot) {
        // The lookup and the read hold the same monitor a delta is applied under (see mutate), so a delta can
        // neither be missed by this read nor land in a ledger this read has already let go of.
        synchronized (ledgers) {
            Ledger ledger = ledgers.get(bot);
            return ledger != null && ledger.hasDebit();
        }
    }

    /**
     * True when what the bot holds less of is items, not mesos: only the stored item rows are stale then, so
     * only they have to be taken down by a write that cannot be completed. A meso-only debit is recorded by the
     * row itself and leaves the bot's stored items alone.
     */
    static boolean hasUnpersistedItemDebit(Character bot) {
        synchronized (ledgers) {   // same monitor as the deltas: see mutate
            Ledger ledger = ledgers.get(bot);
            return ledger != null && ledger.hasItemDebit();
        }
    }

    /**
     * How many times this bot's deltas have changed, 0 when it has no ledger at all. A writer reads this before
     * it captures the state to write and again once that row is committed: a different value means the bot gave
     * value away - or took value in - while the write was in flight, so the row it just wrote does not record
     * that change and the deltas must stay for a later write instead of being discarded with it.
     */
    static int ledgerStamp(Character bot) {
        synchronized (ledgers) {   // same monitor as the deltas: see mutate
            Ledger ledger = ledgers.get(bot);
            return ledger == null ? 0 : ledger.stamp();
        }
    }

    /**
     * Forgets a bot's deltas only while they are exactly the ones a write recorded - the value they stand for is
     * durable, so a later settle must not hand the same value to the world a second time - and keeps them when a
     * delta arrived after that write, because that delta is in no row and dropping it here would lose it.
     *
     * <p>The comparison and the removal happen under the same monitor the deltas are applied under, the map's
     * (see {@link #mutate(Character, Mutation)}), which is what makes them one step: a discard that only
     * synchronized on the Ledger could still remove the entry a mutator is about to write to, and that delta
     * would go nowhere. A caller with no captured stamp to compare against (a bot whose state has no stored row)
     * short-circuits this call rather than passing a stamp that could match by accident.
     *
     * @param captured the stamp a write read before capturing the bot's state; a ledger whose stamp still equals
     *                 it holds only deltas that write recorded.
     * @return false when the ledger was kept because it changed since {@code captured}: the deltas must be
     *         treated as unrecorded.
     */
    static boolean discardIfUnchanged(Character bot, int captured) {
        synchronized (ledgers) {
            Ledger ledger = ledgers.get(bot);
            if (ledger == null) {
                return true;   // no deltas left to keep
            }
            if (ledger.stamp() != captured) {
                return false;
            }
            ledgers.remove(bot);
            return true;
        }
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
        MapleMap map = bot.getMap();
        if (map == null) {
            return;   // nowhere to drop it: the ledger stays until the bot has a map to leave on
        }
        Ledger ledger;
        synchronized (ledgers) {
            // Taken out whole, for the same reason a discard compares under this monitor: a delta landing here
            // instead of in a ledger that is already leaving would be dropped with it and handed to nobody.
            ledger = ledgers.remove(bot);
        }
        if (ledger == null) {
            return;
        }

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
