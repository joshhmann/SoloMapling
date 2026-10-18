# 12: Casino payouts come from the pot (never conjure a payout, never a conjured value item)

**What to build:** Settle the casino policies that `../decisions/06-casino-and-ambient-merchant-boundaries.md`
recorded and had left deferred — now implemented. The change landed in two phases:

- **Phase 1 — pay from the pot.** A winning hand is paid out of the bets the table collected, so a short
  house pays less instead of minting chips; the rest of the pot stays in the dealer's ledger and returns to
  the world on settle. A bot seat's bet and payout stay display-only props (a bot seat moves no value at all,
  its hand is cosmetic), and the blackjack 1.5x bonus is house-funded out of the dealer's own ledgered
  holdings.
- **Phase 2 — per-drop-instance provenance.** A conjured prop is never a value item. Drop provenance is a
  per-*drop-instance* flag, because an item id cannot decide it: the OPQ pieces are both real reactor drops
  and conjured copies of the same ids. Bot loot removes a flagged drop without crediting it, and real value a
  bot hands back (the player's own scrolled weapon) goes through an exact-instance hand-over instead of a
  fabricated copy.

**Blocked by:** 10 (the value-path audit that found the leak, findings 1-3). Ticket 10 is `done`, so this is
unblocked.

**Status:** done (2026-10-17)

- [x] A winning hand is paid from the collected bets, capped by the house's own stock, never a fabricated copy.
- [x] A conjured prop is removed by bot loot but credited nowhere; a conjured drop can never reach a bot ledger.
- [x] The 1.5x blackjack bonus comes out of the dealer's ledgered holdings; a short house stops the spray.
- [x] Provenance is per drop instance (`MapItem.conjured`), set where the drop is created, and it rides every
      fabrication site (items and meso in `DropCommands`, `MapleMap` reactor and meso spawns, `CustomReactor`).
- [x] A real instance a bot must hand back leaves through the exact-instance debit (`BotHoldings.payOutItem`),
      not an aliased copy.
- [x] The debit is ledgered and the units destocked *before* the payout drops are created, so a fault between
      the two destroys value rather than minting it.
- [x] Unit tests pin each case; full suite green in the repo and the real-WZ sandbox; the ticket-08 proof passes.

## What was built (as landed)

Commit `1dfc85a4589d7451820fbc541302f16fef2d3390` (`Mark fabricated bot drops as props and pay casino pots
from the house`), 13 files, +752/-37.

### Phase 1 — the pot payout

| File | Change |
| --- | --- |
| `src/main/java/soloMapling/ArtificialPlayer/BotHoldings.java` | new `payOut(...)` (`:240`) is the ledgered item-debit-and-hand-over the casino tables pay from. `creditDrop` (`:112`) and `canCreditDrop` (`:127`) refuse a conjured drop. `settle` is unchanged (negative deltas were already ignored). |
| `src/main/java/soloMapling/ArtificialPlayer/BotTypes/Blackjack/BlackjackDealerBot.java` | new `static boolean payUnit(...)` (`:566`); `payWinner` (`:576`) keeps its spray, 1.5x arithmetic and pacing but stops the spray and logs one line when `payUnit` returns false (`:588`). |
| `src/main/java/soloMapling/ArtificialPlayer/BotTypes/DiceBot.java` | `payoutWinnersBets` uses `BotHoldings.payOut` under the same rule instead of conjuring a copy per bet unit, and returns when the house holds none (`:207`, the `payOut` call at `:214`). |

### Phase 2 — provenance and exact-instance hand-over

| File | Change |
| --- | --- |
| `src/main/java/server/maps/MapItem.java` | per-drop-instance provenance: field `protected boolean conjured` (`:43`), `public final boolean isConjured()` (`:183`), `public void setConjured(boolean conjured)` (`:187`). |
| `src/main/java/server/maps/MapleMap.java` | every drop entry point gained a trailing `boolean conjured` overload that calls `mdrop.setConjured(conjured)`: `dropFromReactor` (`:815`), the private `spawnDrop` (`:1085`), `spawnMesoDrop` (`:1117`), `spawnItemDrop` (boolean-ffa form, `:2188`), `spawnItemDrop` (byte form, `:2198`, now returns the `MapItem` it created or `null` when the field limit blocks the drop), `spawnItemDropNoExpire` (`:2237`). The pre-existing signatures are kept and delegate with `conjured = false`. |
| `src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java` | everything this class fabricates (`BotLogic.generateCleanItem*`, meso from a literal) is a prop: every drop site passes `conjured = true`, including the three meso conjures (`botDropMeso` `:55`, `botThrowMeso` `:105`, `botThrowToOwnerMeso` `:167`). The single exception is the caller-supplied overload `botThrowEquip(Character, Equip, Point)` (`:108`), which passes `false` because the caller supplies a real instance. `pickUpAndCredit` (`:280`) removes a conjured drop exactly like a normal pickup (same `pickItemDrop` broadcast, same lock discipline) but credits nothing (`:292`), so a prop never reaches a bot ledger and does not sit on the floor making bots walk to it forever. |
| `src/main/java/soloMapling/MapVFX/CustomReactor.java` | fabricated reactor drops pass `true`: `dropItemAtReactor` (`:84`), `notDelayedReactorDrops` (`:137`), `delayedReactorDrops` (`:186`), and the dead `delayedReactorDropsStandard` (`:214`). What a reactor drops, where, how many and with what delay is unchanged. |
| `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TutorialBot.java` | `returnUpgradedWeapon` (`:563`) hands the player's own scrolled weapon back through `BotHoldings.payOutItem` (`:566`) instead of `DropCommands.botThrowEquip`; `scrollPlayersDroppedWeapon` (`:542`) handles the cursed-scroll null return (`:543`) by calling `botScrollFail` (`:548`) instead of NPEing. |

New API signatures (exact):

- `MapItem`: `public final boolean isConjured()`; `public void setConjured(boolean conjured)`.
- `MapleMap` (all trailing `boolean conjured`; the old signatures delegate `false`):
  `public void dropFromReactor(Character chr, Reactor reactor, Item drop, Point dropPos, short questid, short delay, boolean conjured)`;
  `public final void spawnMesoDrop(int meso, Point position, MapObject dropper, Character owner, boolean playerDrop, byte droptype, short delay, boolean conjured)`;
  `public final void spawnItemDrop(MapObject dropper, Character owner, Item item, Point pos, boolean ffaDrop, boolean playerDrop, boolean conjured)`;
  `public final MapItem spawnItemDrop(MapObject dropper, Character owner, Item item, Point pos, byte dropType, boolean playerDrop, boolean conjured)`;
  `public final MapItem spawnItemDropNoExpire(MapObject dropper, Character owner, Item item, Point pos, boolean ffaDrop, boolean playerDrop, boolean conjured)`.
- `BotHoldings`: `public static int payOut(Character bot, int itemId, int quantity, Character recipient, Point position)`
  — removes up to `quantity` units from the bot's actual inventory, ledgers the negative delta, and drops each
  unit owner-locked and expiring-never for the recipient; returns the units actually paid, `0` when the bot
  holds none.
- `BotHoldings`: `public static boolean payOutItem(Character bot, Item item, Character recipient, Point position)`
  — removes that exact held instance from the bot, ledgers the debit, and drops it owner-locked and
  expiring-never for the recipient; the real-value counterpart to `payOut`, which only moves by id/quantity.
- `BlackjackDealerBot`: `static boolean payUnit(Character dealer, Character winner, boolean botSeat, int itemId, Point at)`
  — a bot seat gets a conjured prop (`true`); a real player is paid out of the house's stock through
  `BotHoldings.payOut`, so a short house returns `false` and the dealer pays less.

**Why an id allowlist cannot decide provenance.** `OPQBot` loots *real* reactor pieces and also conjures
copies of the same ids (`OPQBot.java:486,:705` real, `:510-511,:732-733` fabricated; reactor drops arrive via
`MapleMap.dropFromReactor`, `:806`). The same item id is therefore real value in one drop and a prop in
another, so the mark has to live on the drop instance and be set where the drop is created — which is what
`MapItem.conjured` does. Real player pickups are unaffected: the player pickup path
(`InventoryManipulator.addFromDrop`, `src/main/java/client/InventoryManipulator.java`) never consults the flag,
so a player can still pick a prop up; only bot loot refuses to credit one (`BotHoldings.canCreditDrop`).

**The `TutorialBot` aliasing defect this closed.** Before phase 2 the scrolled weapon was handed back by
conjuring a fresh copy through `DropCommands.botThrowEquip` while the bot kept the original in its inventory,
so one weapon could leave the world twice: a bot sweep deleted the floor drop (credited nowhere) and a later
`settle` re-dropped the ledger delta. `payOutItem` moves the one instance out of the inventory and the ledger
in a single step, so the copy is impossible.

`DropCommands.botThrowEquip(Character, Equip, Point)` (`src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java:108`)
is retained as the documented caller-supplied, non-conjuring seam. It has **no production caller** in the
tree: the only invocations of that overload are its own declaration and one mock call
(`src/test/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommandsProvenanceTest.java:82`,
`DropCommands.botThrowEquip(bot, mock(Equip.class), AT)`). Everywhere else resolves to the fabricating int
overload (`DropCommands.java:198`, `DropCommandsProvenanceTest.java:71`); a commented-out line at
`src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:1025` is the only other mention.

Tests:

- `src/test/java/soloMapling/ArtificialPlayer/BotHoldingsTest.java` +5: `conjuredDropIsNeverCredited` (`:201`),
  `payOutHandsOverOnlyWhatTheBotHolds` (`:219`, the debit is already in the ledger, so a later `settle` hands
  nothing back), `payOutPaysNothingWhenTheBotHoldsNone` (`:257`), `payOutItemHandsTheExactHeldInstanceBack`
  (`:268`), `payOutItemRefusesAnItemTheBotDoesNotHold` (`:305`).
- `src/test/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommandsPickupTest.java` +1:
  `aConjuredDropIsRemovedWithoutCreditingAnything`.
- `src/test/java/soloMapling/ArtificialPlayer/BotTypes/Blackjack/BlackjackPayoutTest.java` (new, 3):
  `aBotSeatIsPaidAPropAndMovesNoValue` (`:68`), `aRealPlayerIsPaidOutOfTheHousesStock` (`:85`),
  `anEmptyHousePaysNothingInsteadOfMintingChips` (`:102`).
- `src/test/java/soloMapling/MapVFX/CustomReactorProvenanceTest.java` (new, 5): `fabricatedReactorItemDropIsAProp`
  (`:63`), `aFabricatedReactorItemDropCarriesThePropFlagOnTheMap` (`:76`), `aRealReactorItemDropStaysCreditable`
  (`:90`), `aFabricatedReactorMesoDropCarriesThePropFlagOnTheMap` (`:104`), `aRealReactorMesoDropStaysCreditable`
  (`:116`).
- `src/test/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommandsProvenanceTest.java` (new, 6):
  `aFabricatedItemDropIsAProp` (`:57`), `aFabricatedEquipDropIsAProp` (`:67`), `aCallerSuppliedEquipStaysCreditable`
  (`:77`), `aFabricatedMesoDropIsAProp` (`:88`), `aFabricatedOwnerOnlyDropIsAProp` (`:101`),
  `aFabricatedItemIsHandedBackUnchanged` (`:113`).

20 new tests total.

## Verification

- Repo suite (`/root/SoloMapling`, no `wz/`), `mvn -o test`: `Tests run: 2044, Failures: 1, Errors: 4` —
  exactly the five known WZ-missing failures. The delta over the ticket-11 baseline (2024) is this ticket's
  20 tests.
- Sandbox suite (`/tmp/solomapling-e2e`, real `wz/` + MySQL), `mvn -o test`: `Tests run: 2044, Failures: 0,
  Errors: 0, Skipped: 0`, `BUILD SUCCESS` (~33 s). `mvn -o -q package -DskipTests` → `target/Cosmic.jar`
  60 963 943 B, md5 `aca048affb24a5c37168280e46c21e21`.
- Ticket-08 proof harness, `bash /tmp/botproof_driver.sh`: exit 0, `PROOF-RUN COMPLETE`, `PROOF-VERIFY PASS`,
  12 `PASS` bullets and zero `FAIL`, `fNodeListCache` = 0 in both logs, no `Exception`/`NullPointer`/`at soloMapling`
  lines, and teardown ordered (`[BotShutdown] bot activity quiesced (6 schedulers cancelled)` then
  `[BotShutdown] macro wheel drained in 5020ms (residue 17 ticks)` in the run log; `(5 schedulers cancelled)` /
  `drained in 4858ms (residue 0 ticks)` in the verify log).

Commit: `1dfc85a4589d7451820fbc541302f16fef2d3390`
