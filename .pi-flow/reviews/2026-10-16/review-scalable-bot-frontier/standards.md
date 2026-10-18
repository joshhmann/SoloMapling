# Standards axis — scalable-bot frontier (tickets 01-03)

Axis: repository standards (repo idioms in the touched packages, code-review smell baseline; the repo has no
AGENTS.md / CLAUDE.md / CONTRIBUTING.md / .editorconfig).
Target: `/root/SoloMapling` working tree against `HEAD` = 47b61b5381118ebdc1064ddbd0b1c32f75778871.
Reviewer: `StandardsReview` (read-only). Findings below are the reviewer's, at the revisions it recorded
(`Trade.java` 61ae0200, `BotHoldings.java` d5b9306c, `BotPerformanceMonitor.java` 5e31e8f4,
`DropCommands.java` 219ddc16, `BotSM.java` c5b1fece, `OffscreenRewardBudget.java` ead5215c,
`BotTradeCommands.java` f2ba75a4); two mid-review items became moot when the author's own edits landed first
(the `swapItemToTradeWindow` grant-back loop and `BotPerformanceMonitor.enabled()`).

**Blocker — none found.**

## High

**1. Bot mesos paid out in a completed trade are never debited from the `BotHoldings` ledger, so `settle()`
spawns them again.**
`BotHoldings.java:173` documents the contract ("negative deltas are value the bot already gave away … and stay as
they were spent"), but the trade path never touched the ledger: `Trade.java:199-215` `setMeso` debits only the raw
wallet (`chr.gainMeso(-meso, false, true, false)` at line 208), reached from `BotTradeCommands.setMeso` (line
69-79). Only `HiredMerchant.botBuy` (`HiredMerchant.java:839`) and `PlayerShop.botBuy` (`PlayerShop.java:717`)
called `spendMeso`. `Trade.java:142` credits the partner's ledger while the payer's keeps the mesos, and
`BotHoldings.java:200-202` drops the whole positive `mesoDelta` at despawn.
Trigger: bot accrues 5,000; offers 3,000; wallet → 2,000, ledger still 5,000; despawn spawns 5,000. The 3,000 paid
away plus what the partner booked are minted; repeated bot trades inflate currency.
Suggested fix: ledger hooks called from the offer and cancel paths.

**2. `DropCommands.pickUpAndCredit` credited before any lock and discarded the removal outcome, so two bots could
both be credited for one drop.**
`DropCommands.java:264-274`: no `lockItem()`; the guarded check is a plain non-volatile field read
(`MapItem.java:176-178`; `lockItem`/`unlockItem` at `MapItem.java:196-202`). The callee states the precondition it
violated: `MapleMap.java:993` `pickItemDrop` "mdrop must be already locked and not-pickedup checked at this point",
and it unconditionally decrements `droppedItemCount`. The player path does what is missing
(`Character.java:2000` `mapitem.lockItem();`, re-check `:2002`, `unlockItem()` `:2108`). Bot ticks are not mutually
exclusive (`BotTickService` dispatches each due bot via `ExecutorServiceManager.runAsync`, per-bot CAS only).
Trigger: two bots sweeping the same drop window (`DropCommands.java:198/218/227/258`) inside the credit→removal
gap — both credited, one removal. Impact: duplicated item/meso value, which the ledger then re-drops at settle.
Fix: mirror the player path.

## Medium

**3. `nested=1` on the broadcast phases hides work recorded outside any move-\* phase.**
`BotPerformanceMonitor.java:91-93` registered `BROADCAST_MOVE`/`BROADCAST_RAW`/`BROADCAST_FANOUT` nested and
`report()` excludes nested phases from the bucket total (`:238-240`). That holds for in-phase sites
(`BotMovementManager.java:231/237/252/269`) but `broadcastMovement` is also reached with no move-\* phase open:
`GCMovement.face` (`GCMovement.java:468`) from `BotAttackDriver.java:429`, `EngageBeat.java:309`,
`BotChatter.java:253`, and `GCMovement.teleportTo` (`GCMovement.java:186`) from `CampStrategy.java:595`,
`TrainingBot.java:962`, plus `BotNavigationManager.java:592/647` (inside `nav-resolve`, i.e. BACKGROUND).
Fix: broadcast phases must count towards the active bucket total.

**4. `BotHoldings`'s ledger lock is decorative: the synchronized map protects lookup only.**
`BotHoldings.java:37` `Collections.synchronizedMap(new WeakHashMap<>())` does not guard the `Ledger` values, which
are mutated at `:140` (`itemDeltas.merge`), `:153` and `:166` (`mesoDelta ±`) and iterated in `settle` (`:189-197`).
Writers for one bot sit on different threads: its own tick virtual thread (`BotTickService.runAsync` →
`TrainingBot.accrueAbstractLoot`), the trading player's packet thread (`Trade.completeTrade` →
`creditReceivedItem`/`creditReceivedMeso`), and a shop visitor's thread
(`HiredMerchant.botBuy`/`PlayerShop.botBuy`).

## Low

**5. `BotHoldingsTest` shares static ledger state across tests.**
No `@BeforeEach`, unlike sibling `OffscreenRewardBudgetTest` (which resets via `clearMeters()`). Latent only today
(identity-keyed weak map, fresh `Character` per test).

## Nit

**6.** `BotPerformanceMonitor.recordPathfind(String caller, long nanos)` ignored `caller` — the name never reached
the report.
**7.** `BotSM.java:339-351` called `recordNudge(...)` outside try/finally while the `recordMacroTick` hook 12 lines
above used try/finally (a throwing `BotTickService.nudge` silently skipped the sample).

## Checked, no finding

- `OffscreenRewardBudget`: per-map meter cannot be double-accrued by N bots and is capped at one window
  (`synchronized (meter)` + `lastAccrualMs` advanced inside it ⇒ later draws in the same instant see `mapTime = 0`);
  `respawnIntervalMs` is milliseconds (`config.yaml:175 RESPAWN_INTERVAL: 4000`); `respawn < 0` slots skipped;
  chance denominator `999999.0` and `expectedRoll` mirror `BotAttackEffects.dropMobLoot`; `METERS` growth bounded by
  map count. Boss drop-rate divergence: a design choice, not a defect.
- Refusal paths and wiring: `HiredMerchant.botBuy` / `PlayerShop.botBuy` debit → credit → refund on failure and
  decrement stock only after a successful credit; the discarded boolean is the pre-existing void adapter contract
  (`FreeMarket/ShopKeeper.java:13-14`, `PlayerShopAdapter.java:50`, `HiredMerchantAdapter.java:44`).
  `Trade.setMesoBot` is fully gone.
- `record(String,long)`'s unregistered-phase exception cannot fire (all call-site names registered,
  `BotPerformanceMonitor.java:86-110`); the report is reachable from `EnvironmentCommand.java:526`.
- New tests avoid the `testutil/Mocks` stack deliberately and contain no tautological or wiring-only test;
  `BotTradeOfferTest`'s `bot.setID(21000)` assumption matches `BotHelpers.isArtificial(id) = id > 20000`.
