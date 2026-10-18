# 13: The ledger's deltas are durable, or they are destroyed (never minted back)

**What to build:** Make the `BotHoldings` ledger and the `BotStore` row one record of what a bot holds, so
that no restart can mint value a bot already gave away and no write can quietly drop a delta it never really
captured. A bot value move is either journalled in the ledger or already in the row, and when the two
disagree the failure direction is always DESTROY, never MINT. This is the durability half of the pot rule
ticket 12 landed (`../issues/12-casino-pot-payout.md`): "a payout comes from the pot, never a faucet" is only
true while the ledger (`soloMapling/ArtificialPlayer/BotHoldings.java`) and the durability boundary
(`soloMapling/ArtificialPlayer/BotStore.java`) agree with each other, and review of that change found ten
ways for them not to. The set touches five main files and three test files; it is commit
`8d5fd93e420dc3f97a62930aa071d49adb7684a5` ("Keep a bot's ledger and its row one record of what it holds"),
8 files changed, 1506 insertions(+), 102 deletions(-), on top of `a7a0f91b`.

**Blocked by:** 01 (the accounting path this makes durable) and 12 (the pot payout that made its correctness
load-bearing). Both are `done`.

**Status:** done (2026-10-17) — committed as `8d5fd93e420dc3f97a62930aa071d49adb7684a5`, on top of `a7a0f91b`.

- [x] A refused drop credits only the units `restock`/`putBack` really put back, never the units the caller asked for.
- [x] A debit no durable write recorded is never forgotten: it is rewritten, and when even the retry cannot be written the stored claim is destroyed instead of left for a restart to restore.
- [x] A credit the row already holds is not also handed to the world by a settle.
- [x] A held instance the world destroyed leaves the inventory and the ledger together.
- [x] The held-item snapshot is copied under the tab's own lock, and each stored item is a copy rather than a live reference.
- [x] A write's capture brackets one moment: the ledger stamp is read before the capture and re-read after it, and a capture that spans two moments is refused and retried inside the existing attempt budget.
- [x] Every read of a ledger (the debit predicates and the ledger stamp) holds the monitor a delta is applied under for the whole read, so a delta that already landed cannot be missed.
- [x] The drop-provenance test asserts the map's own flag instead of pinning a mock.
- [x] Unit tests pin each case.

## Why a lost or double-applied delta is a faucet

The ledger exists to hand value back to the world when a bot leaves without a durable write: `BotHoldings.settle`
(`BotHoldings.java:650`) drops the bot's net-positive deltas where it stands, and `discardIfUnchanged`
(`BotHoldings.java:628`) forgets them once the row holds them. Read in both directions, that design makes
every mismatch a faucet:

- **A debit dropped although no row recorded it** leaves the stored row still claiming the value while the
  counterparty durably holds it. The next boot restores it on top: the same value, minted twice.
- **A credit kept although the row already holds it** is handed to the world a second time by `settle`.

Both directions end in MINT, so both halves of the pair have to agree on exactly one moment of the bot.

## The capture window

The write runs on a thread of its own — the autosave sweep (`net/server/task/CharacterAutosaverTask.java:61`
`runAsync(() -> BotStore.saveAll(bots))`) and the shutdown sweep (`net/server/Server.java:1960`
`BotStore.settleAndSaveAll(...)`) — while a delta can arrive from the bot's own tick thread or a counterparty's
thread. `BotHoldings`' ledger javadoc names the three: a bot's own tick, the packet thread of a player it is
trading with, and the thread of a shopper in its merchant or shop. The actors that debit or credit a bot from
outside its own write are:

- `server/maps/PlayerShop.java:711` `botBuy` — `:719 BotHoldings.spendMeso(fakechar, price)` debits the bot
  buying, `:739 BotHoldings.noteMesoEarned(owner, price)` credits a bot owner. Reached from
  `soloMapling/FreeMarket/PlayerShopAdapter.java:50` and `soloMapling/FreeMarket/HiredMerchantAdapter.java:45`.
  The player-side counterpart credits the bot owner at `PlayerShop.java:328`.
- `server/maps/HiredMerchant.java:834` `botBuy` — `:840 BotHoldings.spendMeso(fakechar, price)`,
  `:845 BotHoldings.grantItem(fakechar, newItem)` and the `:846` refund when the grant is refused.
- `server/Trade.java:202` `setMeso` — `:215 BotHoldings.noteMesoSpent(chr, meso)` (the offered mesos leave the
  bot's wallet and its ledger together), `:179 BotHoldings.noteMesoRefund(chr, meso)`,
  `:230 BotHoldings.grantItem(bot, item)`, `:241 BotHoldings.grantMeso(bot, mesos)`.
- the casino's own async payout — `soloMapling/ArtificialPlayer/BotTypes/Blackjack/BlackjackDealerBot.java:566`
  `payUnit`, whose `:571 BotHoldings.payOut(dealer, itemId, 1, winner, at)` runs from `payWinner`
  (`:576`) on `ExecutorServiceManager.runAsync` (`:578`), i.e. not on the tick; and
  `soloMapling/ArtificialPlayer/BotTypes/DiceBot.java:207` `payoutWinnersBets` with its `:214 payOut` call.

A `BotStore` write captures the row's scalars from `of(bot)` (`BotStore.java:843`, which binds
`bot.getMeso()` at `:846`) and the item tabs from `dumpInventory` (`:904`). Those are two reads that must come
from one moment. The ledger stamp is the witness: `ledgerStamp` (`BotHoldings.java:605`) is bumped by every
ledger mutation, so reading it before the scalars and again after the item copies is what tells a writer
whether its two halves describe the same bot.

## What was built (as landed)

**Commit:** `8d5fd93e420dc3f97a62930aa071d49adb7684a5` ("Keep a bot's ledger and its row one record of what it
holds") — the eight `src/` files below, 1506 insertions(+), 102 deletions(-), on top of `a7a0f91b`. `## Verification`
carries the measured suite counts and the eight md5s that identify the verified tree.

### S1 — units a map refused to drop were lost

`FieldLimit.DROP_LIMIT` maps do not register a drop at all: `MapleMap` broadcasts the disappearing-drop
animation and returns `null` (`MapleMap.java:1138` `disappearingItemDrop`, reached from the field-limit check
in `spawnItemDrop` `:2200` and `spawnItemDropNoExpire` `:2239`). `payOut` destocks and ledgers the units
before it creates the drops, so a refund that credited the units it *asked* for rather than the units that
really went back left the ledger claiming value the inventory does not hold — and a later `settle` would hand
that claim out again. `BotHoldings.restock(Inventory, List<Item>, int, int)` (`BotHoldings.java:421`) and
`BotHoldings.putBack(Inventory, Item)` (`:514`) now report the units they really put back, and both callers
credit only those: `payOut` credits `restored` (and only when it is positive), `payOutItem` credits back only
when `putBack` returned true. What could not be put back is gone — the direction to lose in.

### S2 — a restart resurrected a debited value

`hasUnpersistedDebit` (`BotHoldings.java:578`) and `hasUnpersistedItemDebit` (`:592`) name the condition: the
bot holds less than its stored row claims, because a payout, a purchase, mesos handed to a trade partner or a
destroyed held item has no durable record. `BotStore.saveThenSettle` (`BotStore.java:572`) now routes such a
bot into `recordDebit` (`:607`) instead of settling it, and `recordDebit` retries the write; only when even the
retry cannot be written does `destroyStoredHoldings` (`:656`) take the stored claim down. That write always
rewrites the bot's row (so a meso debit is on record) and rewrites the item rows only when the debit is in
items (`:661`) — what it destroys is the stored baseline the bot's own row claimed, which is the bot's own
untouched value, rather than minting value that belongs to nobody.

### S3 — the item snapshot raced the bot

`dumpInventory` (`BotStore.java:904`) copies each item under the tab's own lock
(`inv.lockInventory()` / `unlockInventory()`, `client/inventory/Inventory.java:666`/`:670`), and the two
inventory removals that feed it are one locked step each: `Inventory.removeItem(short, short, boolean)`
(`Inventory.java:298`) now reads the stack, rewrites its quantity and removes the emptied slot inside
`lock.lock()`, and `removeSlot(short)` (`:366`) does the same for a whole slot. Before, the removal ran
unlocked, so a save or a pickup could see a half-applied removal — `list()` hands out a live view of the
tab's map, not a snapshot.

### B2 — the stored items were live references

`BotStore.copyForWrite(Item)` (`BotStore.java:925`) detaches each stored item (`Item.copy()`, plus the two fields the write
itself binds: the giver and the equip ring id). Storing the live `Item` meant a later mutation of the bot's
own object silently changed what the row described.

### S4 — the provenance test pinned a mock

`src/test/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommandsProvenanceTest.java` asserted
provenance through a mocked `MapleMap`, so it pinned the mock rather than the map's own flag. It now builds
`spy(new MapleMap(900000001, 0, 1, 0, 1f))` (`:43`), stubs only `calcDropPos` (`:44`, the checkout is
WZ-less), and reads the flag off a drop it finds on the map itself via `getMapObjectsInRange` (`:50`) and
`MapItem.isConjured()` (`:68`, `:80`, `:93`, `:105`, `:117`, `:129`). It asserts both directions: a fabricated
drop is a prop, a caller-supplied instance is not, and the map is handed the very item the command made
(`assertSame`, `:130`).

### S5 — a cursed weapon left its value behind

`scrollEquipWithId` reports a cursed scroll by destroying the equip and returning `null`
(`ItemInformationProvider.java:1136`). `TutorialBot.scrollPlayersDroppedWeapon`
(`soloMapling/ArtificialPlayer/BotTypes/TutorialBot.java:542`) called `botScrollFail` and returned without
touching the inventory or the ledger, so the bot kept holding value the world had already destroyed.
`BotHoldings.destroyHeldItem` (`BotHoldings.java:536`) now removes the exact instance and ledgers the debit
(`TutorialBot.java:551`), and refuses an instance the bot does not hold.

### B1 — an over-credit could be minted by settle

The credit side of S1 has the same rule: the units credited back are the ones `restock`/`putBack` really put
back, never the ones the caller asked for, and a credit the row already holds is not handed to the world as
well. `payOut` credits `restored` only when it is positive, `payOutItem` credits back only a successful
`putBack`, and `saveThenSettle` discards deltas only through `discardIfUnchanged(bot, captured)`
(`BotHoldings.java:628`), which compares and removes under the same monitor the deltas are applied under.

### D1 — the stamp was not reported out of the write

A delta that landed while the row was being written is in no row, but the write never told its caller which
moment it had captured, so `saveThenSettle` treated every delta as recorded and discarded its only record:
value destroyed. `BotStore.save(Character)` (`BotStore.java:407`) now returns the ledger stamp it captured
(`NO_STAMP = -1` when there is no row to describe, `:421`) and every caller compares the bot's stamp after the
commit against it — `saveThenSettle` `:574`, `recordDebit` `:611`, `saveAll` `:705` and the last-write branch
`:721`/`:723`. Equal means the row is what happened up to the capture; different means the delta stays and
takes the unfiled path.

### R1 — the same window in the other direction, fixed in this set

D1's comparison alone is not enough. While `of(bot)` bound `bot.getMeso()` before the stamp was read, a debit
landing in that gap was *inside* the captured stamp but absent from the row, so the comparison said
"recorded": the delta was discarded, the row kept claiming the value, and the restart minted it back on top of
what the recipient durably holds. The capture is now stamp-stable instead. `writeRow` (`BotStore.java:447`)
reads the stamp at `:451` before the scalars (`:452`), `writeHoldings` (`:809`) re-reads it once the item
copies are done (`:836`), and a mismatch is returned as `UNSTABLE_CAPTURE = -2` (`:432`) rather than
committed: `writeTransaction` (`:469`) rolls that attempt back (`:481`) and takes the whole capture again,
inside the same `WRITE_ATTEMPTS = 4` budget (`:129`) and the same backoff as a contention retry. When the budget
runs out the write throws (`:505`) rather than commit a row that describes two moments: `"Could not capture the
state of bot ... it kept changing while its row was being written, so no row could be written from one moment
of it"`. The same bracket guards `destroyStoredHoldings` (`:656`, stamp read `:666`, re-checked `:673`),
because the ledger is dropped once that row is written and nothing later corrects it.

### R4 — the read that could not see a delta that had already landed

R1 closed the window between two *writes*; R4 is the same window around a *read*. The three predicates
`hasUnpersistedDebit` (`BotHoldings.java:578`), `hasUnpersistedItemDebit` (`:592`) and `ledgerStamp` (`:605`)
each did `ledgers.get(bot)` and then read the ledger **outside** the `ledgers` monitor, so a delta applied
between the lookup and the read was invisible to the read — even though it had already landed and was in the
map by the time the read returned. At `BotStore`'s last-write retry that false negative is the mint one
layer up: `saveAll`'s last-write branch (`BotStore.java:705`) reads `ledgerStamp`, the read misses the debit
that just landed, the comparison says "the row holds it", and the ledger is dropped although the row does not
record the debit — so a restart restores value the recipient already holds.

It is fixed by holding the same monitor for the lookup *and* the read: each predicate is now
`synchronized (ledgers)` around both lines (`BotHoldings.java:581`, `BotHoldings.java:593`,
`BotHoldings.java:606`), map monitor outer and `Ledger` inner,
never the reverse — the same order `mutate` (`:113`) takes, so no lock cycle exists. `clearForTest` (`:101`)
takes it too. The class-level consequence is what matters: after R4 **every** read of a ledger
(`hasUnpersistedDebit`, `hasUnpersistedItemDebit`, `ledgerStamp`) and **every** write (`mutate`,
`discardIfUnchanged`, `settle`) happens under the `ledgers` monitor, so the interleaving R1 fixed at the
capture, the one `discardIfUnchanged`'s compare-and-remove blocks at the discard, and the one R4 fixes at the
read are impossible by construction rather than improbable. That is
stated in the class javadoc at `BotHoldings.java:572-576`.

One deliberate exception is recorded there with its reason: `settle` (`:650`) reads its own already-**removed**
ledger's deltas (the loop over `ledger.items()` and `ledger.meso()` at `:665`/`:676`) outside the monitor. That
is safe because the removed object is unreachable from the map — a later mutation for the same bot gets a fresh
`Ledger` through `computeIfAbsent` (`:115`) rather than that one — every `Ledger` method is itself
`synchronized` (`:57`-`:90`), and no `Ledger` method touches the map.

The two tests added with R4 are in `BotHoldingsTest.java`:
- `aLedgerReadSeesTheDeltaThatJustLanded` (`:507`) is a value contract, not a red-capable pin: it shows a
  debit and the refund that cancels it are both part of what the read sees.
- `aLedgerReadHoldsTheMapMonitorAcrossItsRead` (`:526`) is the red-capable pin (the lock probe): it takes the
  ledger's own monitor on another thread and shows the read is already holding the map's monitor while it
  waits. Its javadoc (`:518-524`) says plainly that it pins the lock the read takes, not the race, because the
  schedule itself cannot be produced from a test without editing the product.

R4 is the read side of the same problem, and it is why the set's `discardIfUnchanged(Character, int)`
(`BotHoldings.java:628`) — which arrived with D1 and replaced the old stamp-less `discard(Character)` — compares
and removes under the monitor rather than around it. That method is the one the four comparison sites call:
`saveThenSettle` (`BotStore.java:575`), `recordDebit` (`:612`), the `destroyStoredHoldings` branch (`:623`) and
`saveAll` (`:707`); the class javadoc's `{@link #discard(Character)}` reference was corrected to
`{@link #discardIfUnchanged(Character, int)}` in the same set (`BotHoldings.java:32`), so
`grep -rn '#discard(' src/` finds no reference to the removed method anywhere.

## The rule: DESTROY, never MINT

Stated plainly, and it is what every choice above is an instance of:

> A bot value move is either journalled in the ledger or already in the row, and when the two disagree the
> failure direction is always DESTROY, never MINT.

So a capture that brackets two moments is refused rather than committed (committing it and keeping the deltas
duplicates a delta the row holds; committing it and dropping them loses one the row lacks); an unreadable
stored baseline is overwritten rather than kept, because the alternative restores value the other side already
holds; and a debit that cannot be written takes its stored claim down instead of being settled onto the map.

## Open follow-ups

- **A bot that keeps changing across its own capture cannot be written at all.** After `WRITE_ATTEMPTS = 4`
  (`BotStore.java:129`) the write throws and the caller keeps the deltas / takes the stored claim down. No
  measured rate: the harness has not produced a bot that changes that fast, so this is a recorded boundary,
  not an observed failure.
- **The `HiredMerchant.closeShop` gate is unconfirmed.** `server/maps/HiredMerchant.java:430` `closeShop` has
  the same shape as the two bounded paths recorded in `../issues/10-value-path-audit.md` (`removeOwner(c.getPlayer())`
  at `:436`, `InventoryManipulator.addFromDrop(c, ...)` at `:444`), and its `forceClose` gate
  (`:377` -> `:390`) is not confirmed open for a bot. Recorded as unconfirmed rather than closed there.
- Two further spots the same review flagged and found no value path in — `BotCustomization.EquipItem` writing
  a bot's `EQUIPPED` tab, and `Character.closePlayerShop`'s owner branch — are recorded as BOUNDED
  determinations, with their evidence, in `../issues/10-value-path-audit.md`.
- **Pre-existing, intermittent, out of scope: a `ConcurrentModificationException` from `getCrushRings`.** An
  earlier round of the same `BotPopulationProof` run logged a `ConcurrentModificationException` raised while a
  `Character`'s ring list was being iterated for a spawn packet — `PacketCreator.addRingLook` (`PacketCreator.java:2034` caller,
  `PacketCreator.java:2137` iterating `chr.getCrushRings()`), against the unsynchronized
  `Character.crushRings` list (`client/Character.java:348`). It is not this set's code and not this set's
  path (no value move, no ledger, no `BotStore`), so it is recorded rather than fixed. It did **not** reproduce
  in the final proof round that `## Verification` records — the driver's `=== run phase errors` count came back
  0 with no `Exception|NullPointer|at soloMapling` line in either log — so it is intermittent and observed in an
  earlier round, not in every run.
- **The set is committed.** `8d5fd93e420dc3f97a62930aa071d49adb7684a5` ("Keep a bot's ledger and its row one
  record of what it holds"), 8 files changed / 1506 insertions(+) / 102 deletions(-), on top of `a7a0f91b`;
  nothing has been pushed.

## Tests

`src/test/java/soloMapling/ArtificialPlayer/BotStoreTest.java` — 32 tests, 11 of them added by this set:

- `aDebitIsWrittenInsteadOfBeingLeftForARestartToRestore` (`:392`)
- `aDebitThatWillNotWriteTakesTheStoredClaimDownWithIt` (`:429`)
- `aSnapshotDescribesTheItemsAsTheyWereRatherThanAsTheyAre` (`:474`)
- `theStoredEquipmentKeepsTheRingIdTheInsertBinds` (`:520`)
- `aChangeTheRowMissedKeepsItsDeltasInsteadOfBeingForgotten` (`:567`; the change lands inside the item-insert
  statement and the write is one moment behind it)
- `theLastWriteOfTheProcessRetriesADebitItCouldNotWriteAtFirst` (`:622`)
- `aCreditCapturedIntoTheRowIsNotHandedToTheWorldAsWell` (`:651`)
- `aMesoDebitInTheCaptureWindowIsMintedBackByTheRow` (`:685`; the probe that spends 300 mesos from inside the
  `UPDATE_ROW` `executeUpdate()`, then requires the retried row to carry 700 and `hasUnpersistedDebit` to be
  false)
- `aMesoCreditInTheCaptureWindowIsCapturedRatherThanSettled` (`:749`)
- `theLastWriteOfTheProcessRetriesAChangeItMissed` (`:803`)
- `theHoldingsSnapshotIsTakenUnderTheTabsOwnLock` (`:921`)

`src/test/java/soloMapling/ArtificialPlayer/BotHoldingsTest.java` — 21 tests, 9 of them added by this set,
7 at the S1/B1/S5 work and 2 with R4:

- `payOutKeepsTheUnitsAMapRefusedToDrop` (`:340`)
- `unitsTheInventoryCannotTakeBackAreNotCreditedToTheLedger` (`:369`)
- `payOutItemPutsBackTheInstanceAMapRefusedToDrop` (`:405`)
- `aDestroyedHeldInstanceLeavesTheInventoryAndTheLedgerTogether` (`:434`)
- `aDiscardKeepsDeltasThatArrivedAfterTheStampItWasGiven` (`:454`)
- `aDiscardRemovesDeltasTheStampStillCovers` (`:481`)
- `destroyHeldItemRefusesAnInstanceTheBotDoesNotHold` (`:495`)
- `aLedgerReadSeesTheDeltaThatJustLanded` (`:507`, added with R4)
- `aLedgerReadHoldsTheMapMonitorAcrossItsRead` (`:526`, added with R4; the one test whose comment says it pins
  the lock the read takes rather than the race)

`src/test/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommandsProvenanceTest.java` — reworked,
no test added or removed (6 cases, now on a real `MapleMap` spy: `:60`, `:72`, `:84`, `:97`, `:109`, `:121`).

## Verification

Verified against the committed set `8d5fd93e420dc3f97a62930aa071d49adb7684a5` on top of `a7a0f91b` (the eight
src files of this set, by md5 — `Inventory.java` `66651e45…`, `BotHoldings.java` `7efe8621…`, `BotStore.java` `7ebf5dd4…`,
`DiceBot.java` `eb2285a6…`, `TutorialBot.java` `5e218950…`, `DropCommandsProvenanceTest.java` `42687afc…`,
`BotHoldingsTest.java` `53149c85…`, `BotStoreTest.java` `897c56f5…`). `git diff --stat -- src/`: 8 files
changed, 1506 insertions(+), 102 deletions(-).

- **Repo suite (no `wz/`):** `Tests run: 2064, Failures: 1, Errors: 4, Skipped: 0`. The five are exactly the
  known WZ-missing ones and nothing else: `GCWorldGraphTest.scansWorldAndRoutesAdjacentMaps`,
  `CoarseExecutorTest.factoryPlansARealRoute`, `CoarseExecutorTest.interpolatesARealBakedEdge`,
  `GCMoveBakeTest.bakesHenesys`, `GCMoveBakeTest.bakesEllinia_ladderHeavy`.
- **Real-WZ sandbox, on this set:** `Tests run: 2064, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`
  (34.18 s). Per class: `BotStoreTest` 32, `BotHoldingsTest` 21, `DropCommandsProvenanceTest` 6.
- **Jar:** `target/Cosmic.jar`, 60 969 541 bytes, md5 `70761bce83db46a21563885b7cf7c559`.
- **Proof harness:** driver exit 0, 321.03 s. Run log 136 lines, md5 `69dea976ab7baed68a5df5f87273817b`
  (`PROOF-RUN COMPLETE` at line 134; its single PASS bullet at line 132 is `first round 13622 mesos / 219 items
  to 11 of 25, second round 0 mesos / 0 items to 0 of 25`). Verify log 43 lines, md5
  `9907d064c8552587c2a2deba138f134d` (`PROOF-VERIFY PASS` at line 41, 11 PASS / 0 FAIL). Zero FAIL overall;
  zero `fNodeListCache` and zero `Exception|NullPointer|at soloMapling` lines in both logs; the driver's
  `=== run phase errors` count is 0.
- **The restoration block, exactly:** wallet before the offers 638284, with the 250-meso offer open 638034,
  stored meso after settlement 638284, live wallet after the restart 638284; the recorded / stored / live item
  list is one byte-identical 17-id string
  `1372010:1,2000000:10,2000002:2,2000003:2,2000006:5,2001002:1,2383012:2,4000014:2,4000023:2,4000027:42,4000030:6,4010000:2,4010001:1,4020008:1,4030012:152,4130005:1,4130009:1,`.
- Each behaviour above is pinned by the named test at the named line. The meso-debit case drives the spend from
  inside the `UPDATE_ROW` statement itself (`BotStoreTest.java:685`), and the credit case lets it land in the
  same window from the connection source (`:651`), which is how the window was demonstrated rather than
  argued.
- **Commit:** `8d5fd93e420dc3f97a62930aa071d49adb7684a5` ("Keep a bot's ledger and its row one record of what it holds").
  The eight md5s above identify the verified tree: a checkout of that commit reproduces them exactly, so a
  working tree can be checked against it.
