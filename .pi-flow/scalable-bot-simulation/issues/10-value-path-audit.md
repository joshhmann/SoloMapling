# 10: Remaining value-path audit (who can credit a bot)

**What to build:** Close the value paths `spec.md` left untraced. Trace each one to a verdict with anchors, fix any path that can move value into or out of a bot without the ticket-01 accounting path, and record the paths that are deliberately ambient instead of silently accepting them.

**Blocked by:** 01 (the accounting path this audits), 07 (the unreachable-state rules used as evidence).

**Status:** done (2026-10-17)

- [x] Each of the four untraced paths (party-split credit, OPQ floor spawns, minigames, pet loot) has a verdict with source anchors and a reachability argument.
- [x] Every value move in the tree that can reach a bot `Character` goes through `BotHoldings` or an explicitly ledgered equivalent; the one exception found is fixed here and every remaining one is named with its reason.
- [x] The unledgered credit found (a bot-owned `PlayerShop`'s proceeds) is ledgered, and the player-side behaviour of that path is unchanged.
- [x] Full suite green in the repo and in the real-WZ sandbox, and the ticket-08 proof still passes.
- [x] Findings that need a policy decision (casino payout minting, conjured display items, bot merchant income) are recorded with measured magnitudes and anchors instead of being accepted silently. **Update (2026-10-17):** no longer pending — the policy question was decided in `../decisions/06-casino-and-ambient-merchant-boundaries.md` and is implemented by ticket 12 (commit `1dfc85a4589d7451820fbc541302f16fef2d3390`); see the resolution pointers on findings 1-3 below.

## The rule that decides every verdict

Bots never send a packet, and nothing under `soloMapling/` dispatches into a packet handler (`grep -rn "handlePacket\|AbstractPacketHandler" src/main/java/soloMapling` returns nothing). So:

- **Unreachable for a bot:** any path whose only entry is a packet handler, or an NPC/quest script the client starts. This is what retires the NPC shop, quest-meso and pet-loot paths.
- **Reachable for a bot:** methods bot code calls directly, *and* any code that takes a bot `Character` as the **counterparty** of a player action. The one leak found lives here.
- The ledger is the record (ticket 01): `BotHoldings.settle` hands a bot's deltas back to the world when it is not persisted; `discard` forgets them once durable. A credit that never reaches the ledger is value the bot's despawn destroys instead of returning - and, read the other way, a debit that never reaches it would mint value on settle.

## Verdict table

| Path | Anchor | Verdict |
|---|---|---|
| party split | `server/life/Monster.java:603` `distributeExperience` → `:729` `giveExpToCharacter` → `attacker.gainExp(_personalExp, _partyExp, !isBot, false, white)`; `:757-760` excepts bots from equip EXP | **clean.** EXP only - not a finite traded resource, and intended (`"Bots gain exp and level up server-side like anyone else"`). No meso/item hand-off: `splitMeso\|MESO_SPLIT` has no hit in `server/maps/MapleMap.java`, `server/life/Monster.java` or `server/World.java`. Party's other effect is drop *ownership* (`server/maps/MapleMap.java:747` `droptype ... chr.getParty() != null ? 1 : 0`), and ownership still ends in `creditDrop`. |
| OPQ floor spawns | real pieces: `MapleMap.java:806` `dropFromReactor(Character chr, ...)` (owner = the breaker) → bot loots at `OPQBot.java:486`, `:705` → `DropCommands.java:241` `lootItemListOnFloor` → `:280` `pickUpAndCredit` → `:299` `BotHoldings.creditDrop` | **real drops clean** (ordinary owned floor drops, credited on pickup). Bots never kill OPQ mobs: `BotAttack.basicSwing` is a pure visual packet and the OPQ package holds no `Monster` reference, so stage-mob kill credit is unreachable. OPQ grants **zero meso** anywhere (`scripts/event/OrbisPQ.js:77-87` sets EXP stages only, no `setEventClearStageMeso`). The conjured hand-over copy is the finding below. |
| minigames | `server/minigame/RockPaperScissor.java:27,89`; blackjack package `soloMapling/ArtificialPlayer/BotTypes/Blackjack/` | RPS **unreachable** (`c` is the RPS_ACTION sender's client; it is the only file in `server/minigame/`; `MiniGame` has no `gainMeso` at all). Blackjack is **reachable and is the finding below**. |
| pet loot | `net/server/channel/handlers/PetLootHandler.java` (packet handler) | **unreachable**, twice over: no packet entry, and no bot ever owns a pet (`grep -rn "addPet\|setPets\|new Pet" src/main/java/soloMapling` → one read-only hit, `DialogueContextResolver.java:184` `c.getPets()`). |
| NPC shop / quest meso | `server/Shop.java:104,109,154,216,239`; `server/quest/actions/MesoAction.java:56-61` | **unreachable.** Every `Shop` entry is packet-driven (`NPCShopHandler.java:53,60,66`, `NPCTalkHandler.java:84`, `UseCashItemHandler.java:528`, `NPCConversationManager.java:383`, `GmShopCommand.java:37`) and quest state is unreachable by ticket-07 rule 1. |
| event/instance rewards | `EventInstanceManager.java:202-225` `giveEventPlayersMeso`, `:1026-1068` `giveEventReward`, `:940-946` `dropExclusiveItems` | **unreachable**, plus bot-hostile; see the notes below. |
| bot pickups | `DropCommands.java:280` `pickUpAndCredit` | **clean** - `BotHoldings.creditDrop` decides before the drop is removed, and a refused credit leaves it on the floor. |
| **bot-owned `PlayerShop` proceeds** | `server/maps/PlayerShop.java:326` and `:737` `owner.gainMeso(price, true)` | **leak - fixed here.** A bot can own a stall (`FreeMarket/ArtificialFreeMarket.java:416` `new PlayerShop(fakechar, ...)`) and can shop at one, so both the player-driven `buy` and the bot-driven `botBuy` credit a bot wallet outside the ledger. Loss-only (value destroyed, never minted), but it broke the "the ledger equals what the bot really holds" invariant. |
| bot-owned `HiredMerchant` proceeds | `server/maps/HiredMerchant.java:330-342` (banked in the `merchanstore` row) | **finding** - a bot owner never sees its sales; recorded below. |
| no unledgered bot **debit** | whole tree | **none exists.** Every bot purchase/trade debit goes through `spendMeso`/`noteMesoSpent`, so `settle` cannot over-report and mint value into the world; the dangerous direction of this invariant was already closed. |

## What was built (as landed)

| Anchor | Change |
|---|---|
| `soloMapling/ArtificialPlayer/BotHoldings.java:258` | New `noteMesoEarned(Character, int)`: adds the amount to the bot's ledger when the amount is positive **and** `BotHelpers.isBot(character)` holds; no-op for a real player. Mirrors `noteMesoSpent`/`noteMesoRefund` (a wallet move that happened outside `grantMeso`), so the player-facing credit stays byte-identical. |
| `server/maps/PlayerShop.java:326-328` (`buy`) | `BotHoldings.noteMesoEarned(owner, price)` after `owner.gainMeso(price, true)` - player buys from a bot-owned stall. |
| `server/maps/PlayerShop.java:737-739` (`botBuy`) | Same call after the same credit - a bot buys from a bot-owned stall. |
| `src/test/java/soloMapling/ArtificialPlayer/PlayerShopBotSaleTest.java` | 4 cases: a bot-owned stall's proceeds reach the map on settle (through `botBuy`); a player-owned stall's do not (`verifyNoInteractions`); the shared wallet-credit seam is ledgered for a bot and not for a player; a non-positive amount changes nothing. |

The fee arithmetic is the wallet's: the ledger records `price - Trade.getFee(price)`, exactly what `owner.gainMeso` received, so a settled bot returns what it was really paid.

**Coverage note.** `PlayerShop.buy` is not driven end to end in the unit suite: its visitor path (`visitShop` → `addVisitor` → `PacketCreator.getPlayerShopNewVisitor` → `addCharLook`) reads item data and its DB row counter, which the repo suite has no fixture for, and `canBuy` → `InventoryManipulator.checkSpace` calls `ItemInformationProvider.getInstance()` unguarded. The credit line both purchase paths share is therefore pinned directly by the seam test above, and the `botBuy` path (the one bot code actually uses) is covered end to end.

## Findings that were decided

These are the dated record of what this audit found in the pre-ticket-12 code: recorded, not fixed at the time - each was either ambient attraction behaviour or needed a policy the ADR had not then set. Findings 1-3 have since been decided in `../decisions/06-casino-and-ambient-merchant-boundaries.md` and implemented by ticket 12 (`1dfc85a4589d7451820fbc541302f16fef2d3390`); finding 4 was closed by the same decision's merchant ruling (`9f7aca06`); finding 5 stays recorded, not hardened. Each of findings 1-3 carries a dated resolution pointer at its end.

1. **The casino pays winners by conjuring chips.** `BlackjackDealerBot.java:576` `payWinner` loops a winner's bets and calls `DropCommands.botThrowItemToOwner` (`DropCommands.java:155`) per unit, which builds a *new* item via `BotLogic.generateCleanItem` (`BotLogic.java:142`) and places it on the floor. `BET_STAMPS` (`BlackjackDealerBot.java:46`) includes the meso-denominated chips `4002000-4002003`, and chip↔meso is lossless through the shop override (`CasinoChipConfig.getChipPrice`). Measured with a read-only WZ price probe: `4002000` = 10 000, `4002001` = 50 000, `4002002` = 250 000, `4002003` = 1 000 000 mesos (each equal to WZ `info/price` and to `CasinoChipConfig`). A winning hand pays `bet units x that item's face value` (`BlackjackDealerBot.java:582`), so the size of the mint is exactly the size of the bet. **Correction (grilling session, 2026-10-17):** a *bot* seat never bets a chip - its bet is a conjured `5 x 4031560` (Dark Lord stamp; `AI_BET_ITEM = 4031560`, `AI_BET_QTY = 5` at `BlackjackDealerBot.java:49-50`, emitted by `DropCommands.botDropItemQtyOwnerOnly` at `:666` with no meso and no inventory gate), and `4031560` appears nowhere else in the repo, so its WZ `info/price` is unverifiable here. The million-meso figure belongs to a *player* seat: a player may drop any `BET_STAMPS` chip (`:46`), `4002003` = 1 000 000 (`CasinoChipConfig.java:31`) with a par sell override (`Shop.java:209-210`), and a win then conjures one fresh copy *per unit* for the winner while the winner's own drop stays on the floor owned by them (`DropCommands.java:142-146`) - a player therefore doubles real-value chips per winning hand (x1.5 count on blackjack, `(int)(quantity * 1.5)` at `BlackjackDealerBot.java:582`) and cashes out at par, while each losing pot is looted into the dealer bot's ledger (`BlackjackDealerBot.java:605` -> `BotHoldings.creditDrop`) with no counter-entry anywhere, i.e. the dealer is a sink that also mints. Two riders: bots are seated every boot (`EnvironmentManager.java:159` boot wave 6, `:884-928`, 5 tables x 2-5 AI players), and `ITEM_LIMIT_ON_MAP = 200` (`config.yaml:328`) evicts the oldest drop on the map (`MapleMap.java:2267-2283`), so a placed bet can be deleted mid-hand. Fixing it means paying from a funded pot (a house bank), which is a design decision, not a leak repair.
   **Update (2026-10-17):** resolved. The decision is `../decisions/06-casino-and-ambient-merchant-boundaries.md` and ticket 12 (`1dfc85a4589d7451820fbc541302f16fef2d3390`) implemented it: `BlackjackDealerBot.payUnit` now pays a bot seat a conjured prop (never creditable) and pays a real player out of the house's own stock through `BotHoldings.payOut`, which debits the dealer's ledger as it hands the pot over - so a short house pays less instead of minting, and the funded pot this finding called for is what landed. The pre-code state described above is kept as the audit's record.
2. **Conjured display items can land in durable bot holdings.** `DropCommands` conjures via `BotLogic.generateCleanItem*` for window/floor display; wherever a bot then loots one, `pickUpAndCredit` credits it like real value: the blackjack dealer's pot and card sweep (`BlackjackDealerBot.java:605,624,647`), the AI winner's own payout (`:605`), the OPQ hand-over copies (`OPQBot.java:510-511,732-733`). Every such item is 1-meso junk (`4001063`, `4001056-4001062`, `4031560` all priced 1, quest-flagged) and the amounts are trivial, but the mechanism contradicts ticket 01's precedent that a bot's generated offer is window-only value that "cannot be minted". Cheap containment if wanted: mark conjured drops at creation and have bot loot remove-without-credit.
   **Update (2026-10-17):** resolved. Exactly that containment shipped: `../decisions/06-casino-and-ambient-merchant-boundaries.md` decided a conjured prop is never a value item, and ticket 12 (`1dfc85a4589d7451820fbc541302f16fef2d3390`) implemented it as a per-drop-instance `MapItem.conjured` flag set where the drop is created, with `DropCommands.pickUpAndCredit` removing a conjured drop and crediting nothing (`BotHoldings.canCreditDrop`/`creditDrop` refuse one), so a conjured display item can no longer reach a bot ledger.
3. **OPQ hand-over duplicates the pieces.** The bot loots the real reactor drop (`OPQBot.java:486`, `:705`) and then conjures a copy of each piece at the NPC for the hand-in (`:510-511` `botThrowItemQty`, `:732-733` `botThrowItem`). The bot keeps the real one and the world gets a fabricated one; the duplicates are 1-meso junk and purely cosmetic (the hand-in itself is the real player leader talking to `CHAMBERLAIN_EAK`, `OPQOrchestrator.java:351-371`), but the item count inflates by every run. Same containment as finding 2.
   **Update (2026-10-17):** resolved as to value. By the same `../decisions/06-casino-and-ambient-merchant-boundaries.md` rule (a conjured prop is never a value item) and ticket 12 (`1dfc85a4589d7451820fbc541302f16fef2d3390`), the conjured OPQ copies at `OPQBot.java:510-511`/`:732-733` are now fabricated props, so no bot credits them; the duplication itself remains cosmetic and was not removed.
4. **A bot-owned `HiredMerchant`'s income is sunk.** `HiredMerchant.java:330-342` banks sale proceeds into the `merchanstore` row; only `withdrawMesos` (`:237` → `Character.withdrawMerchantMesos`) pays an owner, and its callers are player-only (`PlayerInteractionHandler.java:702,737`, `FredrickProcessor.java:290`). A bot never withdraws, so its merchant income is destroyed while its stock was generated. The ADR leaves "whether ambient merchants and scripted attractions are in scope for migration" undecided - this is that question, with one concrete instance.
5. **Latent robustness, not a leak:** `EventInstanceManager.unregisterPlayer` (`EventInstanceManager.java:389-406`) calls `dropExclusiveItems` (`EventInstanceManager.java:940-946`) outside its try, and `giveEventReward`/`giveEventPlayersMeso` (`EventInstanceManager.java:1026-1068`, `:202-225`) start from `Client.getPlayer()`. No OPQ script calls any of them (the leader's grants go through `cm.gainItem` in `scripts/npc/2013001.js:96,106,197`, which needs a real client), so nothing reaches a bot today. Recorded because a future event that registers bots would NPE there rather than fail cleanly.

## Update (2026-10-17): two more paths, both BOUNDED — no value path

Reviewing the `BotHoldings`/`BotStore` durability set (`../issues/13-ledger-durability.md`) raised two spots
that do write a bot's inventory outside the ledger. Each was traced and each is bounded: the write reaches no
value a bot can be paid, sold or settled for, so no ledger entry is owed and no restart can mint one.

- **`BotCustomization.EquipItem` writes a bot's `EQUIPPED` tab and nothing reads it as value.** `EquipItem`
  (`soloMapling/ArtificialPlayer/BotCustomization.java:83`, called from `:47`) fabricates a *clean* equip —
  `Equip source = (Equip) BotLogic.generateCleanItemEquip(itemId)` (`:87`), i.e.
  `BotLogic.generateCleanItemEquip` (`soloMapling/ArtificialPlayer/BotLogic.java:128`) -> `generateCleanEquip`
  (`:138`, via `ItemInformationProvider.getEquipById`) — and puts it into `InventoryType.EQUIPPED`
  (`BotCustomization.java:93 addItemFromDB`, the `-1` tab). There is no ledger call in the method. Nothing
  reads that tab as value: `ItemConstants.getInventoryType(int)` (`constants/inventory/ItemConstants.java:181`)
  derives the tab from `(byte) (itemId / 1000000)` and maps any equip id to `EQUIP(1)`, never `EQUIPPED(-1)`
  (`client/inventory/InventoryType.java:29` and `:35`); shop stock is the fabricated premade list
  (`FreeMarket/ArtificialFreeMarket.java:413 BotPlayerStorePermit` -> `:416 new PlayerShop(...)` -> `:424
  ps.setItems(premadeShop)`), trade items are generated, and `Character.sellAllItemsFromName`
  (`client/Character.java:9301`) / `sellAllItemsFromPosition` (`:9320`) have no callers anywhere under
  `src/main/java/`. A fabricated equip in the worn tab is display state, like a fabricated floor drop is a
  prop — with the difference that no code ever converts it back. **BOUNDED.**
- **`Character.closePlayerShop`'s owner branch is bot-reachable and does NPE, but its stock is fabricated.**
  `closePlayerShop` (`client/Character.java:5683`) hands the shop's items back through
  `InventoryManipulator.addFromDrop(this.getClient(), ...)` (`:5697`, `:5699`), and
  `InventoryManipulator.addFromDrop(Client, Item, boolean, int)` (`client/inventory/manipulator/InventoryManipulator.java:181`)
  dereferences the client's player at `:182` (`Character chr = c.getPlayer();`) — null for every bot, because
  `BotClient` (`client/BotClient.java:10`) never calls `setPlayer` (the only `setPlayer` callers are
  `PlayerLoggedinHandler.java:175,201,217` and `Client.java:1123,1130`). It *is* reachable: `permit` attaches a
  shop to any bot (`client/command/commands/gm4/ArtificialFreeMarketCommand.java:98 BotPlayerStorePermit(fakechar)`),
  including a warp-capable FSM type, and every bot map change runs `changeMapInternal` (`Character.java:1745`)
  -> `closePlayerInteractions` (`:5662`, which calls `closePlayerShop` at `:5665`) — bots warp at
  `BotTypes/TrainingBot.java:556,600,656` and `BotCommandsPack/WarpCommands.java:58,65,115`. But the stock it
  would hand back is the same fabricated premade list as above, so the NPE costs a bot nothing and moves no
  value. **BOUNDED, no value path.**
- **`HiredMerchant.closeShop` has the same shape with its gate unconfirmed — recorded, not closed.**
  `server/maps/HiredMerchant.java:430 closeShop(Client, boolean)` also calls
  `InventoryManipulator.addFromDrop(c, ...)` (`:444`) behind `removeOwner(c.getPlayer())` (`:436`), and bots do
  own merchants (`ArtificialFreeMarket.java:329 newchar.setHiredMerchant(merchant)`). Whether a bot can reach
  it is not confirmed — `forceClose` (`:377`) reaches `closeOwnerMerchant(owner)` (`:390`) only when
  `owner != null && owner.isLoggedinWorld() && this == owner.getHiredMerchant()`, and the packet handlers
  (`PlayerInteractionHandler.java:741,798,809`) are unreachable for bots. Left **unconfirmed rather than
  closed**, since the reachability argument is not established either way. Its stock is fabricated too, so if
  it is reachable it would fall into the same bounded class.

The durability set itself is recorded in `../issues/13-ledger-durability.md`; nothing in this ticket's
verdicts, verdict table or closing actions changes.

## Verification

- `PlayerShopBotSaleTest` 4/4 green (`target/surefire-reports/TEST-soloMapling.ArtificialPlayer.PlayerShopBotSaleTest.xml`); sandbox row: `Tests run: 4, Failures: 0, Errors: 0, Time elapsed: 1.539 s -- in soloMapling.ArtificialPlayer.PlayerShopBotSaleTest`.
- Repo suite (no `wz/`): `Tests run: 2020, Failures: 1, Errors: 4` - the same 5 pre-existing WZ-missing failures as the ticket-09 baseline (2010 tests, same 5); the delta is this ticket's 4 tests plus ticket 09's 6.
- Sandbox suite (`/tmp/solomapling-e2e`, real WZ): `Tests run: 2020, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` (34.8 s wall), including `BotShutdownTest` 6/6.
- Ticket-08 proof re-run in the sandbox (`/tmp/botproof_driver.sh`, `DRIVER_EXIT=0`, 335 s): run phase `PROOF-RUN COMPLETE`, verify phase `PROOF-VERIFY PASS` with every assertion green on bot `566926` `Celare` (level 67, exp 18061, meso 536409). The shop scenario exercised the changed seam from both sides: `shop of bot 290959 (map 910000017) item 4010001 price=1706 fee=0 -> bought=true, buyer meso 538515 -> 536809, seller meso 0 -> 1706, bundles 8 -> 7, sold 0 -> 1` - the seller is a bot whose take now goes through `BotHoldings.noteMesoEarned` and is what the row restores (`PASS the open trade window was settled before the row was written - wallet before the offers=536409, wallet with the offers open=536159, stored meso=536409`).
- Ticket 09's property is intact in the same run: `[BotShutdown] bot activity quiesced (6 schedulers cancelled)`, then `[BotShutdown] macro wheel drained in 5000ms (residue 19 ticks)`, no exception line after either, and the verify phase has 0 of 0 `Exception|ERROR` lines.
- One pre-existing WZ-race line shows up mid-run (not in the teardown window, not on the changed path): `NullPointerException: Cannot assign field "fChildIndex" because "this.fNodeListCache" is null` at `XMLDomMapleData.getChildByPath:81` <- `ItemInformationProvider.getStringData:277` <- `BotHelpers.convertItemIdToName:55` <- `FMBot.purchaseShopItems:318` <- `BotSM` tick. The identical shape pre-dates this ticket: `/tmp/boot_boot5.out` (2026-10-17 01:16, ticket-06 era) and its sibling variant at `/tmp/boot2.out` (00:31). It is a shared-DOM race in `XMLDomMapleData` (Xerces `ParentNode.fNodeListCache` is a lazily built, unsynchronized cache on a DOM shared by every bot tick), recorded here for a future ticket rather than folded into this one.
- Landed in `/root/SoloMapling` as commit `d051b01b` ("Ledger a bot-owned stall's sale proceeds", 3 files, +160) on top of `4ee68950`; `graphify update .` rebuilt the graph to 24240 nodes / 60560 edges.
- Evidence kept in `/tmp/ticket10-evidence/` (`sandbox-suite.out`, `sandbox-package.out`, `proof.out`, `botproof_run.out`, `botproof_verify.out`).

## Open questions

- ~~Findings 1-4 need an owner decision; none of them blocks ticket 01's invariant, because every one of them either moves value *through* the accounting path (2, 3), creates it in a place the ADR has not ruled on (1), or destroys it (4).~~ **Update (2026-10-17):** no longer open. Findings 1-3 were decided in `../decisions/06-casino-and-ambient-merchant-boundaries.md` and implemented by ticket 12 (`1dfc85a4589d7451820fbc541302f16fef2d3390`); finding 4 is closed by the same decision's merchant ruling (a bot merchant's income is a sink by design, commit `9f7aca06`); finding 5 remains recorded, not hardened. None of them ever blocked ticket 01's invariant.
- Nothing in this audit was found to mint value *out* of the world on settle: no unledgered bot debit exists anywhere, so the settle side of the invariant holds as built.

## Policy decisions recorded (grilling session, 2026-10-17)

The owner reviewed findings 1-5 in a grilling pass. Findings 1-3 (the casino and conjure provenance) became `../decisions/06-casino-and-ambient-merchant-boundaries.md` and are now **implemented** by ticket 12 (`/root/SoloMapling` commit `1dfc85a4589d7451820fbc541302f16fef2d3390`), rather than left deferred: the three answers are recorded here because the policy was settled in that pass, not re-litigated:

- **Pay from the pot; never conjure a payout** (finding 1). The collected bets are a real pot: hand those to the winner instead of fabricating copies with `BotLogic.generateCleanItem*`. Note this is player-facing, not bot-facing: a winning player currently keeps their own stake (it stays on the floor owned by them, `DropCommands.java:142-146`) *and* receives a conjured copy per unit, i.e. doubles real-value chips per winning hand and cashes them out at par through the lossless chip sell (`Shop.java:209-210`).
- **The 1.5x blackjack bonus is house-funded** (`BlackjackDealerBot.java:582`). The extra 0.5x units come out of the dealer bot's own looted holdings, same item id, taken through a new ledgered item-debit beside `noteMesoSpent` (`BotHoldings` had `creditDrop`/`grantItem`/`spendMeso` but no item-spend API; **Update (2026-10-17):** that API now exists as `BotHoldings.payOut` (by id/quantity) and `BotHoldings.payOutItem` (an exact held instance), both added by ticket 12 `1dfc85a4589d7451820fbc541302f16fef2d3390`). The house pays what it holds and mints nothing; losing pots replenish it; the winner's own 1.0x is always safe.
- **A bot's bet stays a prop (never creditable)** (finding 2, and finding 3 by the same rule). Keep the conjured `5 x 4031560` display bet, but make conjured drops non-creditable and non-lootable by bots, so bot seats are value-free and every bot hand is cosmetic.
- **Provenance must be per-instance, not an id list.** The OPQ pieces are *both* real reactor drops (`OPQBot.java:486`, `:705`) and conjured copies of the same ids (`:510-511`, `:732-733`), so an id allowlist would wrongly block the legitimate pickup. The mark has to be set where the drop is created (the conjure helpers in `DropCommands`/`BotLogic.generateCleanItem*`); bot loot of a flagged drop removes it without crediting. Player looting is untouched.

Finding 4 facts, verified against the live database (container `solomapling-verify-db`, schema `cosmic_ticket06boot2`) rather than inferred: `characters` holds 2 rows and **0** of them join to `botstore` (0 by `id = 20000 + BotId`, 0 by name), and **0** rows have `MerchantMesos > 0`. A bot-owned merchant's proceeds are therefore **discarded, not banked**: `HiredMerchant.botBuy` sends a bot owner *deliberately* into the DB branch (`owner != null && !isBot(owner)`, `:867`), and `addMerchantMesos` (`client/Character.java:8929`) writes `characters.MerchantMesos` for a row that does not exist - 0 rows affected, no error, no log - while the in-memory `merchantmeso` it also sets is never persisted by `BotStore` (`UPDATE_ROW` carries no merchant column). The only path that moves this value into a wallet is `withdrawMerchantMesos()` (`client/Character.java:8957`), reachable only from player-side handlers.

## Closing actions (2026-10-17)

- The policies above are written up as `../decisions/06-casino-and-ambient-merchant-boundaries.md`, which also records the merchant ruling (a bot merchant's income is a sink by design) and the event-instance call (recorded, not hardened).
- Finding 4 is closed by removing the dead owner-bank branch from `server/maps/HiredMerchant.java` (`buy` and `botBuy`): a bot owner no longer issues a no-op `UPDATE characters SET MerchantMesos` per sale. Nothing else about merchant behaviour changes. Landed as `/root/SoloMapling` commit `9f7aca06`; repo suite after it: 2024 tests, the same five pre-existing WZ-missing failures.
- The pre-existing WZ race this ticket recorded in `## Verification` (the `fNodeListCache` NPE) is fixed by ticket 11, `11-wz-read-concurrency.md` (`/root/SoloMapling` commit `ee5aeb4e`): 0 occurrences in the harness boot, raw suite green on real WZ.
