package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import server.Trade;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotGrindSystem.OffscreenRewardBudget;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.LodCounts;
import soloMapling.Environment.EnvironmentManager;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Ticket 08 (scalable-bot-simulation): the end-to-end integration proof.
 * <p>
 * One runnable demonstration that a persistent, finite-resource bot population earns, spends, persists
 * across a restart, reconciles on promotion and stays inside its per-map budget - with the sparse and
 * crowded costs reported separately. It is deliberately NOT a JUnit test: it boots the real server
 * in-process ({@link Server#main}), against the real WZ data and the real database, because every seam it
 * exercises (maps, shops, the offscreen budget, the persistence store) needs that whole stack.
 * <p>
 * Run it inside a sandbox checkout that has {@code wz/} and a live database, from the checkout root:
 * <pre>
 *   java -Xmx3g -cp target/Cosmic.jar:target/test-classes soloMapling.ArtificialPlayer.BotPopulationProof run
 *   java -Xmx3g -cp target/Cosmic.jar:target/test-classes soloMapling.ArtificialPlayer.BotPopulationProof verify
 * </pre>
 * The {@code run} phase prints its findings, writes the tracked bot's identity/progression/holdings to
 * {@code /tmp/botproof/state.txt} and then exits: the exit runs the server's shutdown hook, which is the
 * settlement rule under test (every bot is settled and saved before the worlds go away). The {@code verify}
 * phase boots again, finds the same bot by its plan slot and compares what the store restored against the
 * recorded state.
 * <p>
 * The phase deliberately leaves one trade window open at the exit: closing it is part of the settlement
 * rule, and the offered mesos coming back to the wallet is the observable that proves it ran.
 */
public final class BotPopulationProof {

    // Henesys Hunting Ground 1 - the map EnvironmentManager spawns its attack-test bots on.
    private static final int ATTACK_TEST_MAP = 104040000;
    // A real consumable, so ItemInformationProvider/ItemFactory can save it back to the store.
    private static final int LOOT_ITEM_ID = 2000000;
    private static final int TRADE_MESO = 400;
    private static final int OPEN_WINDOW_MESO = 250;
    private static final int PARTY_CAP_CHECK = 6;
    private static final int BUDGET_DRAWERS = 25;
    // The map ticket 02 measured its per-window yield on (389 mesos at one occupant), used as the first
    // candidate for the bot-free budget measurement.
    private static final int BUDGET_REFERENCE_MAP = 100_000_002;
    private static final Path OUT_DIR = Path.of("/tmp/botproof");
    private static final Path STATE_FILE = OUT_DIR.resolve("state.txt");
    private static final Path VERIFY_FILE = OUT_DIR.resolve("verify.txt");

    private static final List<InventoryType> TABS = List.of(InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH);

    private static final List<String> FINDINGS = new ArrayList<>();

    private BotPopulationProof() {
    }

    public static void main(String[] args) throws Exception {
        String phase = args.length > 0 ? args[0] : "run";
        Files.createDirectories(OUT_DIR);
        say("phase " + phase + " pid=" + ProcessHandle.current().pid());
        Server.main(new String[0]);
        say("server main returned; waiting for the population to settle");
        waitForPopulation();
        if (phase.equals("verify")) {
            verify();
        } else {
            run();
        }
    }

    // ── the run phase ────────────────────────────────────────────────────────────────────────────────

    private static void run() throws Exception {
        List<String> primed = BotPerformanceMonitor.report();
        say("perf report primed (bucket lines=" + primed.size() + ")");

        // 1. Sparse window: nobody is looking at any map, so every bot pays only its background tick.
        Thread.sleep(6_000);
        dump("perf-sparse", BotPerformanceMonitor.report());

        // 2. The tracked bot: a planned (durable) bot that can pay for a shop item and hold what it buys.
        Character tracked = chooseTrackedBot();
        if (tracked == null) {
            say("FATAL no planned bot with mesos and room was found");
            System.exit(3);
        }
        say("tracked bot id=" + tracked.getId() + " slot=" + (tracked.getId() - BotStore.BOT_ID_BASE)
                + " name=" + tracked.getName() + " level=" + tracked.getLevel()
                + " job=" + tracked.getJob().getId() + " meso=" + tracked.getMeso()
                + " map=" + tracked.getMapId());
        Map<String, String> state = new LinkedHashMap<>();
        state.put("id", String.valueOf(tracked.getId()));
        state.put("slot", String.valueOf(tracked.getId() - BotStore.BOT_ID_BASE));
        state.put("name", tracked.getName());
        state.put("job", String.valueOf(tracked.getJob().getId()));
        state.put("level", String.valueOf(tracked.getLevel()));
        state.put("exp", String.valueOf(tracked.getExp()));

        // 3. Criterion 1a - loot: a real drop on the bot's own map, credited through the production path.
        int trackedMap = tracked.getMapId();
        say("tracked map " + trackedMap + " observed=" + GCMovement.isMapObserved(trackedMap)
                + " (the economy runs offscreen here)");
        lootOnce(tracked, state, "loot");

        // 3b. Criterion 1 wants the same economy across an observed/offscreen cycle: hold the tracked bot's
        //     own map in the observed tier for a few seconds, then let the force lapse.
        long observedUntil = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < observedUntil) {
            GCMovement.markObservedNow(trackedMap);
            Thread.sleep(400);
        }
        say("tracked map " + trackedMap + " observed=" + GCMovement.isMapObserved(trackedMap)
                + " (forced observed)");

        // 4. Criterion 1b - purchase: a real bot shop, bought with the bot's own mesos.
        attemptPurchase(tracked, state);

        // 5. Criterion 1c - trade: a real two-sided meso trade between two bots on the same map.
        tradeOnce(tracked, state);

        // 5b. Let the observation lapse and repeat the loot, so the same path is shown with no observer.
        Thread.sleep(3_000);
        say("tracked map " + tracked.getMapId() + " observed=" + GCMovement.isMapObserved(tracked.getMapId())
                + " (after the force lapsed)");
        lootOnce(tracked, state, "lootOffscreen");

        // 6. Criterion 5 - party: real party creation and the 6-member cap, through the production API.
        partyLeg(tracked, state);

        // 7. Criterion 4 - crowded window: attack-test bots plus a forced observation, so the active
        //    combat/physics/broadcast terms are separated from the background tick.
        EnvironmentManager.spawnAttackTestBots(2);
        say("spawned attack-test bots on map " + ATTACK_TEST_MAP + "; holding it observed for 8s");
        long until = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < until) {
            GCMovement.markObservedNow(ATTACK_TEST_MAP);
            Thread.sleep(400);
        }
        say("lod of " + ATTACK_TEST_MAP + ": observed=" + GCMovement.isMapObserved(ATTACK_TEST_MAP)
                + " full=" + LodCounts.fullMaps() + " halo=" + LodCounts.haloMaps()
                + " active=" + LodCounts.activeMaps() + " dynamic=" + LodCounts.dynamicBots()
                + " tracker=" + LodCounts.trackerRunning());
        dump("perf-crowded", BotPerformanceMonitor.report());
        Thread.sleep(6_000);
        say("lod after the observation lapsed: observed=" + GCMovement.isMapObserved(ATTACK_TEST_MAP));
        dump("perf-after-demotion", BotPerformanceMonitor.report());

        // 8. Criterion 3 - the offscreen budget of the busiest map, one window at a time.
        budgetLeg(tracked, state);

        // 9. Criterion 2 - leave a commitment open, then exit: the shutdown hook must settle it.
        openWindowAtShutdown(tracked, state);

        state.put("inventory", inventoryFingerprint(tracked));
        writeState(state);
        say("PROOF-RUN COMPLETE - exiting so the shutdown settlement runs");
        System.out.flush();
        Thread.sleep(200);
        // Same shutdown hook SIGTERM runs: every bot is settled and saved before the worlds go away.
        System.exit(0);
    }

    // ── the verify phase ─────────────────────────────────────────────────────────────────────────────

    private static void verify() throws Exception {
        Map<String, String> state = readState();
        if (!state.containsKey("id") || !state.containsKey("inventory") || !state.containsKey("mesoBeforeOffers")) {
            List<String> incomplete = new ArrayList<>();
            record(incomplete, "the run phase left a complete state file to verify", false,
                    "keys=" + state.keySet());
            writeVerify(incomplete);
            say("PROOF-VERIFY FAIL");
            System.out.flush();
            System.exit(6);
        }
        int id = Integer.parseInt(state.get("id"));
        List<String> results = new ArrayList<>();

        // bot-persistence ticket 08: the director decides who plays, so a roster member is not guaranteed to be
        // among this boot's logins - the tracked bot logs back in through the director's own login path, like a
        // player coming back.
        int trackedSlot = id - BotStore.BOT_ID_BASE;
        if (BotRoster.isMember(trackedSlot) && !CharacterStorage.botLoggedIn(id)) {
            say("tracked bot is offline after the director's boot fill; logging it in: "
                    + BotPopulationDirector.logIn(trackedSlot));
        }
        Character live = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (live == null && System.currentTimeMillis() < deadline) {
            for (Character bot : liveBots()) {
                if (bot.getId() == id) {
                    live = bot;
                    break;
                }
            }
            if (live == null) {
                Thread.sleep(500);
            }
        }
        if (live == null) {
            record(results, "the bot is back in the population", false, "id " + id + " never appeared");
            writeVerify(results);
            System.exit(4);
        }
        // Freeze the subject so the comparison is against the restored state, not a running bot.
        GCMovement.disable(live);

        record(results, "the bot is back in the population", true, "id=" + id + " name=" + live.getName());
        record(results, "the store adopted it this run", BotStore.isAdoptedInThisRun(id),
                "isAdoptedInThisRun=" + BotStore.isAdoptedInThisRun(id));
        record(results, "identity survived (name)", state.get("name").equals(live.getName()),
                "recorded=" + state.get("name") + " live=" + live.getName());
        record(results, "identity survived (job)", state.get("job").equals(String.valueOf(live.getJob().getId())),
                "recorded=" + state.get("job") + " live=" + live.getJob().getId());

        // The durable side: what the store itself restored, independent of any live tick after the boot.
        BotStore.Persisted row = BotStore.persistedFor(id);
        record(results, "the store has a row for its slot", row != null, "row=" + (row != null));
        int recordedLevel = Integer.parseInt(state.get("level"));
        int recordedExp = Integer.parseInt(state.get("exp"));
        int recordedMeso = Integer.parseInt(state.get("mesoBeforeOffers"));
        String recordedInventory = state.get("inventory");
        if (row != null) {
            Character mirror = Character.getDefault(new BotClient(0, 0));
            mirror.setID(id);
            row.applyTo(mirror);
            row.loadHoldings(mirror);
            record(results, "progression in the row is not behind the recorded state",
                    mirror.getLevel() > recordedLevel
                            || (mirror.getLevel() == recordedLevel && mirror.getExp() >= recordedExp),
                    "recorded level=" + recordedLevel + " exp=" + recordedExp
                            + ", stored level=" + mirror.getLevel() + " exp=" + mirror.getExp());
            record(results, "the open trade window was settled before the row was written",
                    mirror.getMeso() >= recordedMeso,
                    "wallet before the offers=" + recordedMeso + ", wallet with the offers open="
                            + state.get("mesoAfterOffers") + ", stored meso=" + mirror.getMeso());
            String missing = missingStacks(mirror, recordedInventory);
            record(results, "every recorded item stack came back", missing.isEmpty(),
                    missing.isEmpty() ? "recorded " + recordedInventory : "missing " + missing);
            record(results, "the live bot holds everything the store restored",
                    live.getMeso() >= mirror.getMeso() && missingStacks(live, inventoryFingerprint(mirror)).isEmpty(),
                    "row meso=" + mirror.getMeso() + " live meso=" + live.getMeso()
                            + ", row holdings=" + inventoryFingerprint(mirror)
                            + ", live holdings=" + inventoryFingerprint(live));
        } else {
            record(results, "every recorded item stack came back", false, "no row to compare against");
        }
        record(results, "the live bot is not behind the recorded state",
                live.getLevel() > recordedLevel
                        || (live.getLevel() == recordedLevel && live.getExp() >= recordedExp),
                "recorded level=" + recordedLevel + " exp=" + recordedExp
                        + ", live level=" + live.getLevel() + " exp=" + live.getExp());
        record(results, "the live wallet still holds what it held before the offers",
                live.getMeso() >= recordedMeso,
                "wallet before the offers=" + recordedMeso + ", live wallet=" + live.getMeso());
        resumeChecks(results, id, row);
        writeVerify(results);
        boolean pass = results.stream().allMatch(line -> line.startsWith("PASS"));
        say("PROOF-VERIFY " + (pass ? "PASS" : "FAIL"));
        System.out.flush();
        Thread.sleep(200);
        System.exit(pass ? 0 : 5);
    }

    /**
     * bot-persistence ticket 06: the tracked bot is a roster member (wave 8), so it must log in where its row
     * says it logged out - not at its wave's town - and pick its activity back up. The row was written by the
     * run phase's shutdown sweep, after bot activity was quiesced.
     */
    private static void resumeChecks(List<String> results, int id, BotStore.Persisted row) {
        int slot = id - BotStore.BOT_ID_BASE;
        BotRoster.Member member = BotRoster.member(slot);
        record(results, "the bot is a roster member", member != null, "slot=" + slot);
        if (member == null || row == null) {
            return;
        }
        BotRoster.LoginPlace place = BotRoster.loginPlace(id);
        MapleMap storedMap = Server.getInstance().getChannel(0, 1).getMapFactory().getMap(row.mapId);
        int expectedMap = storedMap != null && storedMap.getForcedReturnId() != constants.id.MapId.NONE
                ? storedMap.getForcedReturnId() : row.mapId;
        boolean sameMap = place != null && place.mapId() == expectedMap;
        boolean samePoint = place != null && (expectedMap != row.mapId || (place.x() == row.posX && place.y() == row.posY));
        record(results, "it logged in where its row says it logged out", sameMap && samePoint,
                "row map=" + row.mapId + " (" + row.posX + ", " + row.posY + "), expected map=" + expectedMap
                        + ", logged in at " + (place == null ? "nowhere" : place.mapId() + " (" + place.x() + ", "
                        + place.y() + ")"));
        BotRoster.Activity last = member.lastActivity();
        record(results, "its row recorded what it was doing at logout", last != null && !last.name().isEmpty()
                        && member.lastLogoutAt() > 0,
                "activity=" + (last == null ? "none" : last.name() + " home=" + last.homeMapId() + " " + last.state())
                        + " lastLogoutAt=" + member.lastLogoutAt());
        // A bot's FSM first ticks after the spawn choreography plus a random stagger; its snapshot only reflects
        // the resume once that init has run (home is 0 until then), so wait for it rather than judge too early.
        BotSM brain = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(id);
        BotRoster.Activity now = brain == null ? null : brain.activitySnapshot();
        long initDeadline = System.currentTimeMillis() + 30_000;
        while (brain != null && now != null && now.homeMapId() <= 0 && System.currentTimeMillis() < initDeadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            now = brain.activitySnapshot();
        }
        if (last != null && "grind".equals(last.name()) && last.intState(BotRoster.TRAIN_STATE, -1) == row.mapId
                && expectedMap == row.mapId) {
            record(results, "a grinder that logged out on its train map kept grinding there",
                    now != null && now.intState(BotRoster.TRAIN_STATE, -1) == row.mapId
                            && now.homeMapId() == last.homeMapId(),
                    "logged out training on " + row.mapId + " home " + last.homeMapId() + ", now "
                            + (now == null ? "no brain" : now.name() + " home=" + now.homeMapId() + " " + now.state()));
        }
    }

    // ── steps ────────────────────────────────────────────────────────────────────────────────────────

    private static void lootOnce(Character bot, Map<String, String> state, String tag) throws InterruptedException {
        MapleMap map = bot.getMap();
        Item item = new Item(LOOT_ITEM_ID, (short) 0, (short) 1);
        int before = bot.getInventory(item.getInventoryType()).countById(LOOT_ITEM_ID);
        Point pos = bot.getPosition();
        map.spawnItemDrop(bot, bot, item, pos, true, false);
        Thread.sleep(200);
        MapItem drop = findDrop(map, pos, LOOT_ITEM_ID, bot.getId());
        if (drop == null) {
            FINDINGS.add(tag + ": THE DROP NEVER APPEARED on map " + map.getId());
            return;
        }
        if (!BotHoldings.canCreditDrop(bot, drop)) {
            FINDINGS.add(tag + ": the bot could not hold its own drop on map " + map.getId());
            return;
        }
        DropCommands.botLootSingleDrop(bot, drop);
        int after = bot.getInventory(item.getInventoryType()).countById(LOOT_ITEM_ID);
        FINDINGS.add(tag + ": item " + LOOT_ITEM_ID + " on map " + map.getId() + " observed="
                + GCMovement.isMapObserved(map.getId()) + " held " + before
                + " -> " + after + ", drop picked up=" + drop.isPickedUp());
        state.put(tag + "ItemId", String.valueOf(LOOT_ITEM_ID));
        state.put(tag + "Before", String.valueOf(before));
        state.put(tag + "After", String.valueOf(after));
    }

    private static void attemptPurchase(Character buyer, Map<String, String> state) {
        // Bot shops live on the map, not necessarily on a bot the registry knows: the FreeMarket shops are
        // spawned as map objects, so look at both the bot-held stores and the stores loaded on the maps.
        List<PlayerShop> shops = new ArrayList<>();
        for (Character candidate : liveBots()) {
            PlayerShop candidateShop = candidate.getPlayerShop();
            if (candidateShop != null && !shops.contains(candidateShop)) {
                shops.add(candidateShop);
            }
        }
        int heldByBots = shops.size();
        Channel channel = Server.getInstance().getChannel(buyer.getWorld(), buyer.getClient().getChannel());
        if (channel == null) {
            channel = Server.getInstance().getChannel(0, 1);
        }
        int openOnMaps = 0;
        if (channel != null) {
            for (MapleMap map : channel.getMapFactory().getMaps().values()) {
                for (MapObject object : map.getPlayerStores()) {
                    if (object instanceof PlayerShop shop) {
                        if (shop.isOpen()) {
                            openOnMaps++;
                        }
                        if (!shops.contains(shop)) {
                            shops.add(shop);
                        }
                    }
                }
            }
        }

        PlayerShop shop = null;
        PlayerShopItem item = null;
        int slot = -1;
        int price = Integer.MAX_VALUE;
        for (PlayerShop candidateShop : shops) {
            if (!candidateShop.isOpen() || candidateShop.getOwner() == null) {
                continue;
            }
            List<PlayerShopItem> items = candidateShop.getItems();
            for (int i = 0; i < items.size(); i++) {
                PlayerShopItem candidateItem = items.get(i);
                if (candidateItem == null || !candidateItem.isExist() || candidateItem.getItem() == null
                        || candidateItem.getBundles() < 1 || candidateItem.getPrice() <= 0) {
                    continue;
                }
                if (candidateItem.getPrice() >= price || candidateItem.getPrice() > buyer.getMeso()) {
                    continue;
                }
                Item copy = candidateItem.getItem().copy();
                if (!BotHoldings.canHold(buyer, copy)) {
                    continue;
                }
                shop = candidateShop;
                item = candidateItem;
                slot = i;
                price = candidateItem.getPrice();
            }
        }
        if (shop == null) {
            FINDINGS.add("purchase: no open bot shop had an item the buyer could afford and hold - saw "
                    + heldByBots + " shops held by bots and " + openOnMaps + " open shops on the loaded maps, "
                    + "buyer meso=" + buyer.getMeso());
            return;
        }
        Character seller = shop.getOwner();
        Item copy = item.getItem().copy();
        int fee = Trade.getFee(price);
        int buyerBefore = buyer.getMeso();
        int sellerBefore = seller.getMeso();
        int buyerHeld = buyer.getInventory(copy.getInventoryType()).countById(copy.getItemId());
        int bundlesBefore = item.getBundles();
        int soldBefore = shop.getSold().size();
        boolean bought = shop.botBuy(buyer, item, slot, (short) 1);
        int buyerHeldAfter = buyer.getInventory(copy.getInventoryType()).countById(copy.getItemId());
        FINDINGS.add("purchase: shop of bot " + seller.getId() + " (map " + shop.getMapId() + ") item "
                + item.getItem().getItemId() + " price=" + price + " fee=" + fee
                + " -> bought=" + bought
                + ", buyer meso " + buyerBefore + " -> " + buyer.getMeso()
                + ", seller meso " + sellerBefore + " -> " + seller.getMeso()
                + ", bundles " + bundlesBefore + " -> " + item.getBundles()
                + ", sold " + soldBefore + " -> " + shop.getSold().size()
                + ", buyer item count " + buyerHeld + " -> " + buyerHeldAfter
                + ", buyer credits " + (buyerBefore - buyer.getMeso()) + " debits "
                + (seller.getMeso() - sellerBefore + fee));
        state.put("buyerId", String.valueOf(buyer.getId()));
        state.put("sellerId", String.valueOf(seller.getId()));
        state.put("purchaseItemId", String.valueOf(item.getItem().getItemId()));
        state.put("purchasePrice", String.valueOf(price));
        state.put("purchaseFee", String.valueOf(fee));
        state.put("purchaseBundlesAfter", String.valueOf(item.getBundles()));
        state.put("purchaseSoldAfter", String.valueOf(shop.getSold().size()));
    }

    private static void tradeOnce(Character first, Map<String, String> state) {
        Character second = null;
        for (Character candidate : first.getMap().getCharacters()) {
            if (candidate.getId() != first.getId() && candidate.getTrade() == null
                    && candidate.getLevel() >= 10) {
                second = candidate;
                break;
            }
        }
        if (second == null) {
            FINDINGS.add("trade: no second bot was available on map " + first.getMapId());
            return;
        }
        int firstBefore = first.getMeso();
        int secondBefore = second.getMeso();
        BotTradeCommands.sendTradeRequestToPlayer(first, second);
        BotTradeCommands.acceptTradeInvite(second);
        BotTradeCommands.setMeso(first, TRADE_MESO);
        int offered = first.getMeso();
        BotTradeCommands.confirmTrade(first);
        BotTradeCommands.confirmTrade(second);
        FINDINGS.add("trade: bot " + first.getId() + " -> bot " + second.getId() + " " + TRADE_MESO
                + " mesos, first wallet " + firstBefore + " -> " + offered + " (offer) -> " + first.getMeso()
                + ", second wallet " + secondBefore + " -> " + second.getMeso()
                + ", windows open: first=" + (first.getTrade() != null) + " second=" + (second.getTrade() != null));
        state.put("tradePartnerId", String.valueOf(second.getId()));
        state.put("tradeMeso", String.valueOf(TRADE_MESO));
        state.put("tradeFirstMesoBefore", String.valueOf(firstBefore));
        state.put("tradeFirstMesoAfter", String.valueOf(first.getMeso()));
        state.put("tradeSecondMesoBefore", String.valueOf(secondBefore));
        state.put("tradeSecondMesoAfter", String.valueOf(second.getMeso()));
    }

    private static void partyLeg(Character leader, Map<String, String> state) {
        boolean created = Party.createParty(leader, false);
        Party party = leader.getParty();
        if (!created || party == null) {
            FINDINGS.add("party: createParty returned " + created + " and party=" + party);
            return;
        }
        World world = leader.getWorldServer();
        int joined = party.getMembers().size();
        boolean refused = false;
        for (Character candidate : leader.getMap().getCharacters()) {
            if (party.getMembers().size() >= PARTY_CAP_CHECK) {
                break;
            }
            if (candidate.getId() == leader.getId() || candidate.getParty() != null) {
                continue;
            }
            if (world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(candidate))) {
                joined++;
            }
        }
        Character seventh = null;
        for (Character candidate : leader.getMap().getCharacters()) {
            if (candidate.getParty() == null && candidate.getId() != leader.getId()) {
                seventh = candidate;
                break;
            }
        }
        if (seventh != null) {
            refused = !world.updateParty(party.getId(), PartyOperation.JOIN, new PartyCharacter(seventh));
        }
        FINDINGS.add("party: created=" + created + " party id=" + party.getId() + " members=" + joined
                + " -> " + party.getMembers().size() + ", seventh member refused=" + refused);
        state.put("partyId", String.valueOf(party.getId()));
        state.put("partySize", String.valueOf(party.getMembers().size()));
        state.put("partySeventhRefused", String.valueOf(refused));
    }

    /** One round of BUDGET_DRAWERS draws in the same window: {mesos, items, drawers that were paid}. */
    private static long[] drawRound(int mapId, int occupants, long nowMs, World world) {
        long mesos = 0;
        int items = 0;
        int paid = 0;
        for (int i = 0; i < BUDGET_DRAWERS; i++) {
            OffscreenRewardBudget.Reward reward = OffscreenRewardBudget.draw(mapId, occupants, 60_000, nowMs, world);
            if (!reward.isEmpty()) {
                paid++;
                mesos += reward.mesos();
                for (Item item : reward.items()) {
                    items += item.getQuantity();
                }
            }
        }
        return new long[] {mesos, items, paid};
    }

    private static String roundText(long[] round) {
        return round[0] + " mesos / " + round[1] + " items to " + round[2] + " of " + BUDGET_DRAWERS;
    }

    private static void budgetLeg(Character bot, Map<String, String> state) throws InterruptedException {
        Map<Integer, Integer> occupancy = new LinkedHashMap<>();
        for (Character candidate : liveBots()) {
            if (candidate.getMap() != null) {
                occupancy.merge(candidate.getMapId(), 1, Integer::sum);
            }
        }
        int busiest = occupancy.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        int busiestOccupants = occupancy.getOrDefault(busiest, 0);
        World world = bot.getWorldServer();
        long now = System.currentTimeMillis();

        // A. A map the population itself keeps drawing on: whatever extra drawers get here is what the
        //    resident bots left in this window, not what the map pays for it.
        long[] drainedFirst = drawRound(busiest, busiestOccupants, now, world);
        long[] drainedSecond = drawRound(busiest, busiestOccupants, now, world);
        FINDINGS.add("budget: the busiest map " + busiest + " has " + busiestOccupants + " resident bots; "
                + BUDGET_DRAWERS + " extra drawers in one window got " + roundText(drainedFirst)
                + ", asking again for the same window " + roundText(drainedSecond));

        // B. A map with no bots on it: nothing else draws its meter, so one window's yield is observable
        //    and the per-map-per-window clamp can be shown on a real map.
        MapleMap pristine = null;
        long[] pristineFirst = null;
        for (MapleMap candidate : budgetCandidates(bot, occupancy)) {
            long[] round = drawRound(candidate.getId(), 1, now, world);
            if (round[2] > 0) {
                pristine = candidate;
                pristineFirst = round;
                break;
            }
        }
        if (pristine == null) {
            FINDINGS.add("budget: no bot-free map with a spawn yield was available, so one window's yield "
                    + "and the per-window clamp were not demonstrated on a real map");
        } else {
            long[] pristineSecond = drawRound(pristine.getId(), 1, now, world);
            FINDINGS.add("budget: bot-free map " + pristine.getId() + ", " + BUDGET_DRAWERS
                    + " asking in one window got " + roundText(pristineFirst)
                    + ", asking again for the same window " + roundText(pristineSecond));
            record(FINDINGS, "budget: one window paid the map's yield once, however many bots asked",
                    pristineSecond[2] == 0,
                    "first round " + roundText(pristineFirst) + ", second round " + roundText(pristineSecond));

            // C. One window of real map time later the map has accrued again: the budget follows map time.
            Thread.sleep(60_000);
            long[] pristineLater = drawRound(pristine.getId(), 1, System.currentTimeMillis(), world);
            FINDINGS.add("budget: the same bot-free map one real window later got " + roundText(pristineLater));
            state.put("budgetMap", String.valueOf(pristine.getId()));
            state.put("budgetFirstWindowMesos", String.valueOf(pristineFirst[0]));
            state.put("budgetSecondWindowMesos", String.valueOf(pristineSecond[0]));
            state.put("budgetNextWindowMesos", String.valueOf(pristineLater[0]));
        }
        state.put("budgetBusiestMap", String.valueOf(busiest));
        state.put("budgetBusiestOccupants", String.valueOf(busiestOccupants));
    }

    /**
     * Maps for the bot-free budget measurement: the map ticket 02 measured its numbers on first, then any
     * loaded map that has neither characters on it nor a resident bot population.
     */
    private static List<MapleMap> budgetCandidates(Character bot, Map<Integer, Integer> occupancy) {
        Channel channel = Server.getInstance().getChannel(bot.getWorld(), bot.getClient().getChannel());
        if (channel == null) {
            channel = Server.getInstance().getChannel(0, 1);
        }
        List<MapleMap> candidates = new ArrayList<>();
        if (channel == null) {
            return candidates;
        }
        if (occupancy.getOrDefault(BUDGET_REFERENCE_MAP, 0) == 0) {
            try {
                candidates.add(channel.getMapFactory().getMap(BUDGET_REFERENCE_MAP));
            } catch (RuntimeException e) {
                say("budget: map " + BUDGET_REFERENCE_MAP + " could not be loaded: " + e);
            }
        }
        for (Map.Entry<Integer, MapleMap> entry : channel.getMapFactory().getMaps().entrySet()) {
            MapleMap map = entry.getValue();
            if (map.getCharacters().isEmpty() && occupancy.getOrDefault(entry.getKey(), 0) == 0
                    && !candidates.contains(map)) {
                candidates.add(map);
                if (candidates.size() >= 8) {
                    break;
                }
            }
        }
        return candidates;
    }

    private static void openWindowAtShutdown(Character bot, Map<String, String> state) {
        Character partner = null;
        for (Character candidate : bot.getMap().getCharacters()) {
            if (candidate.getId() != bot.getId() && candidate.getTrade() == null) {
                partner = candidate;
                break;
            }
        }
        if (partner == null) {
            FINDINGS.add("open window: no partner was available on map " + bot.getMapId());
            return;
        }
        int before = bot.getMeso();
        BotTradeCommands.sendTradeRequestToPlayer(bot, partner);
        BotTradeCommands.acceptTradeInvite(partner);
        BotTradeCommands.setMeso(bot, OPEN_WINDOW_MESO);
        int after = bot.getMeso();
        FINDINGS.add("open window: bot " + bot.getId() + " offers " + OPEN_WINDOW_MESO + " mesos to bot "
                + partner.getId() + " and the process exits with the window open: wallet " + before
                + " -> " + after + ", window open=" + (bot.getTrade() != null));
        state.put("openWindowPartnerId", String.valueOf(partner.getId()));
        state.put("openWindowMeso", String.valueOf(OPEN_WINDOW_MESO));
        state.put("mesoBeforeOffers", String.valueOf(before));
        state.put("mesoAfterOffers", String.valueOf(after));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Character chooseTrackedBot() {
        // A roster member when one qualifies (bot-persistence tickets 06/08 check its resume), else any planned bot.
        Character member = chooseTrackedBot(true);
        return member != null ? member : chooseTrackedBot(false);
    }

    private static Character chooseTrackedBot(boolean rosterOnly) {
        return liveBots().stream()
                .filter(bot -> bot != null && bot.getMap() != null && BotStore.isPlanned(bot.getId() - BotStore.BOT_ID_BASE))
                .filter(bot -> !rosterOnly || BotRoster.isMember(bot.getId() - BotStore.BOT_ID_BASE))
                .filter(bot -> bot.getLevel() >= 10 && bot.getTrade() == null && bot.getMeso() >= 5_000)
                .filter(bot -> BotHoldings.canHold(bot, new Item(LOOT_ITEM_ID, (short) 0, (short) 1)))
                .max(Comparator.comparingInt(Character::getMeso))
                .orElse(null);
    }

    private static List<Character> liveBots() {
        List<Character> bots = new ArrayList<>();
        for (BotSM state : CharacterStorage.getAllBots().values()) {
            Character character = state.getChr();
            if (character != null) {
                bots.add(character);
            }
        }
        return bots;
    }

    private static MapItem findDrop(MapleMap map, Point pos, int itemId, int ownerId) {
        for (var object : map.getMapObjectsInRange(pos, 40_000.0, List.of(MapObjectType.ITEM))) {
            MapItem drop = (MapItem) object;
            if (drop.getItemId() == itemId && drop.getOwnerId() == ownerId && !drop.isPickedUp()) {
                return drop;
            }
        }
        return null;
    }

    private static void waitForPopulation() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        int last = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int size = CharacterStorage.getAllBots().size();
            if (size > 100 && size == last) {
                if (++stable >= 6) {
                    say("population settled at " + size + " bots");
                    return;
                }
            } else {
                stable = 0;
            }
            last = size;
            Thread.sleep(500);
        }
        say("WARNING the population never settled (last=" + last + ")");
    }

    private static String inventoryFingerprint(Character bot) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (InventoryType type : TABS) {
            for (Item item : bot.getInventory(type).list()) {
                if (item != null) {
                    counts.merge(item.getItemId(), (int) item.getQuantity(), Integer::sum);
                }
            }
        }
        StringBuilder text = new StringBuilder();
        counts.forEach((itemId, count) -> text.append(itemId).append(':').append(count).append(','));
        return text.toString();
    }

    private static int countIn(Character bot, int itemId) {
        int total = 0;
        for (InventoryType type : TABS) {
            total += bot.getInventory(type).countById(itemId);
        }
        return total;
    }

    /** Every {@code itemId:count} of a fingerprint that the character does not hold at least as much of. */
    private static String missingStacks(Character bot, String fingerprint) {
        StringBuilder missing = new StringBuilder();
        for (String entry : fingerprint.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":");
            int itemId = Integer.parseInt(parts[0]);
            int recordedCount = Integer.parseInt(parts[1]);
            int present = countIn(bot, itemId);
            if (present < recordedCount) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(itemId).append(" recorded=").append(recordedCount)
                        .append(" present=").append(present);
            }
        }
        return missing.toString();
    }

    private static void dump(String tag, List<String> report) {
        for (String line : report) {
            say(tag + " | " + line);
        }
        FINDINGS.add(tag + ": " + report.size() + " bucket lines (see the run log)");
    }

    private static void record(List<String> results, String what, boolean ok, String detail) {
        results.add((ok ? "PASS " : "FAIL ") + what + " - " + detail);
        say((ok ? "PASS " : "FAIL ") + what + " - " + detail);
    }

    private static void writeState(Map<String, String> state) throws Exception {
        StringBuilder text = new StringBuilder();
        state.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        StringBuilder findings = new StringBuilder();
        FINDINGS.forEach(line -> findings.append(line).append('\n'));
        Files.writeString(STATE_FILE, text.append("\n").append(findings).toString());
        say("state written to " + STATE_FILE);
    }

    private static Map<String, String> readState() throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(STATE_FILE)) {
            properties.load(in);
        }
        Map<String, String> state = new LinkedHashMap<>();
        properties.forEach((key, value) -> state.put(String.valueOf(key), String.valueOf(value)));
        return state;
    }

    private static void writeVerify(List<String> results) throws Exception {
        StringBuilder text = new StringBuilder();
        results.forEach(line -> text.append(line).append('\n'));
        Files.writeString(VERIFY_FILE, text.toString());
        say("verify written to " + VERIFY_FILE);
    }

    private static void say(String message) {
        System.out.println("[PROOF] " + message);
        System.out.flush();
    }
}
