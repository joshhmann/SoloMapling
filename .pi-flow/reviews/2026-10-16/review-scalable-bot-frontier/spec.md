# Spec axis — scalable-bot frontier (tickets 01-03)

Axis: the three acceptance-criteria tickets (verbatim criteria), reviewed read-only against the working tree.
Reviewer: `SpecReview`. Line numbers are the working tree at review time; findings 1-3 below were fixed in the
same session (see synthesis.md for dispositions).

## Criterion table

### Ticket 01 — shared bot resource accounting

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Bot loot pickup adds the item/mesos to that bot's holdings instead of removing the drop with no credit. | MET | `DropCommands.java:262-271` (`pickUpAndCredit`: credit first, `pickItemDrop` only on success); `BotHoldings.java:85-97` (`creditDrop`: meso / NX card / item); walk gating in `GrindLoot.java:136,170` (`canCreditDrop`). |
| 2 | Bot shop and merchant purchase debits the buyer's mesos and grants the purchased item; insufficient funds refuse the purchase. | MET | `HiredMerchant.java:831-847`, `PlayerShop.java:711-733`: `spendMeso` first, refusal message + `return false`; `grantItem` for the goods, `grantMeso(price)` rollback on a full inventory. Both bot paths previously granted nothing to the buyer. |
| 3 | Bot-to-bot and bot-to-player trades settle on both sides rather than only the non-bot side. | PARTIAL | Receiving side works: `Trade.java:409-413` completes on both sides, items `Trade.java:130-134` (`returnToBotHoldings`), meso `:141-143`. The giving side was not settled: the bot's offer was a net-zero grant-then-take (`BotTradeCommands.java:227-228`) — see Blocker 1. |
| 4 | Bot despawn no longer silently discards in-memory holdings; deltas are persisted or explicitly settled. | MET | `BotGeneration.java:187` `BotHoldings.settle(fakechar)` before map/channel removal; `BotHoldings.java:218-242` drops positive net deltas where the bot stands. Not exhaustively enumerated: `client/Client.java:1253`. |
| 5 | Existing player-side behavior (seller credit, stock decrement, sold ledger) is unchanged. | MET | `HiredMerchant`/`PlayerShop` changes are insertions inside `botBuy` only (15/13 insertions, 0 deletions in those hunks); player `buy(...)` bodies and the `Trade` player branch (`InventoryManipulator.addFromDrop` in the `else`) untouched. |

### Ticket 02 — offscreen reward budget

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | A bot accruing offscreen rewards cannot exceed the map instance's expected yield over the elapsed window. | MET | `OffscreenRewardBudget.java:160-200` (`reserve`: meter capped at one window, draw capped at `yield*botWindow`), consumed at `TrainingBot.java:1176-1189`. |
| 2 | World rates and drop-table membership feed the budget as inputs rather than being replaced by a second rate source. | MET | `OffscreenRewardBudget.java:66-95` reads `MonsterInformationProvider.retrieveEffectiveDrop`, `world.getDropRate()/getMesoRate()`, `YamlConfig` `RESPAWN_INTERVAL`/`ITEM_LIMIT_ON_MAP`, `MapleMap.getCurrentSpawnRate` (made public at `MapleMap.java:3700-3705`). Caveat: the drop-chance/roll formula is duplicated (Low 5). |
| 3 | Output respects the per-map item limit and respawn/occupancy behavior already present. | MET | `OffscreenRewardBudget.java:214-227`: kills from per-slot `respawnMs` × `getCurrentSpawnRate(occupants)`, items clamped `min(qty*spawnRate, itemLimit)`; `respawnMs` from WZ `mobTime` at `MapMobIndex.java:63-67,118-135`. |
| 4 | No independent per-bot full-rate production path is introduced. | MET (offscreen accrual) | Shared per-map meter `OffscreenRewardBudget.METERS` + `reserve`, one draw per bot against one window. The trade-cancel mint (Blocker 1) violated the spirit of this criterion. |

### Ticket 03 — sparse/crowded/transition cost attribution

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Diagnostics report active-simulation, background, and transition cost separately for a running population. | MET | `BotPerformanceMonitor.java:212-260` (`report()`: per-bucket totals + per-phase lines, delta against the previous call). |
| 2 | Readings taken seconds apart distinguish sparse and crowded workloads. | MET (weakened) | `report()` subtracts each phase's `prev*` snapshot and advances baselines in the same call; `BotPerformanceMonitorTest.java:145` covers second-window semantics. Weakened because the background bucket's dominant sparse phases printed a hardcoded `0.00 ms/s` (High 2). |
| 3 | Within-active breakdown attributes cost across physics stepping, movement broadcast, combat scan/damage/loot, and promotion burst. | PARTIAL | physics `BotPerformanceMonitor.java:90` (nested under `move-*`), broadcast `:91-93`, combat `:94-96`, with call sites at `BotMovementManager.java:262/390/450/565/862/933/947/949`, `BotPhysicsEngine.java:955/1014/1242/1492`, `RoamStrategy.java:59`, `CampStrategy.java:326/390`, `StackStrategy.java:320/369`, `BotAttackDriver.java:95`, `GrindBrain.java:306/314`. "Promotion burst" exists but is bucketed TRANSITION (`:103`, recorded at `GCMovementDriver.java:335`), which is what criterion 1 requires; asserted as a deviation. |
| 4 | The report is reachable from the existing diagnostics surface without a new host or deployment. | MET | `EnvironmentCommand.java:526` emits each `BotPerformanceMonitor.report()` line in the existing `!env perf`; no new host. |

## Behaviour implemented incorrectly

**Blocker 1 — an uncompleted bot trade minted the offered item into the world (and duplicated real holdings).**
Offer items are always generated (`BotTradeCommands.java:141-171` via `generateCleanItemEquip` /
`generateCleanItemWithQty`), and `moveOfferOutOfHoldings` netted to zero
(`BotTradeCommands.java:227-228`: `return BotHoldings.grantItem(fakechar, tradeItem) && BotHoldings.takeItem(fakechar, tradeItem);`),
so the ledger delta for the offer was 0. `Trade.cancel` then handed the offer *back* for a bot
(`Trade.java:169-171` → `:220-226` → `BotHoldings.grantItem` → `BotHoldings.java:140` ledger merge), and
`BotGeneration.java:187` → `settle` spawned every positive delta (`BotHoldings.java:232-237`
`map.spawnItemDrop(...)`). Opening and cancelling (or declining) a bot trade — or a scam swap
(`BotTradeCommands.java:201-213`, grant-back at `:206`) — credited the bot with items it never held and dumped
them into the world at despawn: an unbounded item-printing loop, the opposite of the spec's finite-resource
contract and of ticket 02's criterion 4. If the offer had been a real held item, grant+take left it in the
inventory and cancel added a second copy.

**High 2 — background cost hardcoded to zero for the four sparse phases the ticket is about.**
`BotPerformanceMonitor.java:243-249` printed a literal format string for the `COUNTED_NAMES` loop
(`{"macro-tick","movement-tick","idle-broadcast","nudge"}` at `:106-111`), so `nanos=0` was not a measurement,
while `report()` added their sample counts to the BACKGROUND total (`:230-234`) contributing zero nanoseconds.
Ticket 03's purpose is "a scaling decision targets the measured dominant term" and the sparse-observer term is
exactly this work.

**Medium 3 — the instrumentation gate became permanently on, leaving the untimed fast path dead.**
`enabled()` returned true unconditionally, so `BotMovementManager.java:853-857`
`if (!BotPerformanceMonitor.enabled()) { doBroadcastMovement(entry); return; }` could never take the early branch.

**Low 4 — `HiredMerchant.botBuy` granted one bundle regardless of `quantity`.**
`HiredMerchant.java:832-844` copied `pItem.getItem()` into `newItem` without setting the quantity while pricing
`pItem.getPrice() * quantity`; `PlayerShop.java:711-713` was the reference. Latent only (sole caller passes
`(short) 1`, `FMBot.java:328`).

**Low 5 — the budget's meso rate ignored `bossDropRate`.**
`OffscreenRewardBudget.java:139` used `max(1, world.getDropRate())` while `BotAttackEffects.java:174` uses
`mob.isBoss() ? world.getBossDropRate() : world.getDropRate()` — a boss map under-estimates yield (conservative),
diverging from `accumulate`'s "mirrors … exactly" claim.

**Nit 6 — `METERS` keyed by `mapId` globally, not per map *instance*.**
`OffscreenRewardBudget.java:110` shares one meter across worlds/channels of the same map id, contradicting the
class comment. Under-produces, so not a criterion violation.

## Unrequested behaviour / scope creep

- `MapleMap.java:2790-2846`: `broadcastMessage(Character, Packet, boolean)` return type `void → int`, recipients
  counted in the private overload; `MapleMap.java:3700-3705`: `getCurrentSpawnRate(int)` made public. Shared core-map
  API changed for tickets 02/03 instrumentation (only bot code consumes the count).
- `ItemInformationProvider.java:362-395`: `getSlotMax` split into an itemId-only overload plus the player-aware one;
  player path value preserved.
- `BotPerformanceMonitor.java`: `enabled()` flipped always-true plus new public bucket/phase surface.
- `GrindBrain.java:299-306`: a `lootAtFeet` indirection whose only purpose is timing `loot.grabLootAtFeet`.
- No behaviour clearly outside tickets 01-03; tickets 04-08 not treated as missing.

## Requirements named in the tickets but absent

- Ticket 03 criterion 3 names "promotion burst" as part of the within-active breakdown while criterion 1 requires
  transition cost separately; the change delivers it in TRANSITION. Flagged as an explicit bucket deviation.
- No other requirement naming a diagnostics surface, refusal path or settlement step is missing; refusal paths
  exist (`HiredMerchant.java:839-841`, `PlayerShop.java:717-719`), settlement exists (`BotGeneration.java:187`),
  and no acceptance criterion references a persistence store (ticket 06, out of scope).
- Ticket 01 criterion 5's "sold ledger" is present and unchanged (`HiredMerchant.java:848-850`,
  `PlayerShop.java:737-741`).

## Not verified by this axis

- Whether `client/Client.java:1253` can remove a bot without a settle (dupe-check context only).
- No compile or suite run (the environment's sparse `wz/` failure mode was not exercised).
