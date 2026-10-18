# 06: Cross-restart bot persistence store

**What to build:** Bot identity, progression, holdings, and commitments survive a server restart through a durable store with defined initial grants, inventory capacity, and settlement of in-flight state.

**Blocked by:** 01 Shared bot resource accounting path; 04 Durability boundary across observed and offscreen states.

**Status:** done (2026-10-16; revised 2026-10-17 for the review findings below).

- [x] A bot's level, exp, mesos, inventory, and equipment are present after restart without regeneration from a template.
- [x] Initial grants and inventory caps are explicit and applied consistently on first creation.
- [x] In-flight state at shutdown (trade, recruit, movement) is settled or safely dropped under one stated rule.
- [x] Restart does not duplicate or lose holdings compared with the pre-restart in-memory state.

Criteria 1 and 4 were only *partially* met on 2026-10-16: the store was keyed by arrival order while the
environment spawns a different number of bots every boot, so a restarted bot was regularly adopted by
another bot's row. The plan-slot key in this revision is what makes them met; the boot evidence below is
the proof.

## The one explicit rule

> A bot's state becomes durable at the two moments it stops being live: **despawn saves it**, and **server
> shutdown saves every bot** after settling its player-facing in-flight state. Anything the store does not
> hold - movement trails, coarse plans, combat presentation, contact damage, pacing, watchdog repair - is
> reconstructible by design, and anything that only exists as process memory on both sides of a boundary
> (a party registry, an invite window) is dropped at restart for players and bots alike, so no partial
> commitment can survive.

Implemented as `soloMapling.ArtificialPlayer.BotStore` (the store), `BotGeneration.createBot` (adopt or
first-create by creation slot), `BotGeneration.removeBotFromServer` (`saveThenSettle`), `Server.shutdownInternal`
(`settleAndSaveAll` before the world is torn down) and `CharacterAutosaverTask` (the hourly sweep).

## A bot's row is keyed by its place in the boot plan

The key is not the character id and not arrival order; it is the **plan slot**:

```
BotStore.planSlot(wave, task, ordinal) = SLOTS_PER_WAVE * (wave - 1) + SLOTS_PER_TASK * task
                                       + SLOTS_PER_GROUP * (ordinal / SLOTS_PER_GROUP)
                                       + (ordinal % SLOTS_PER_GROUP)
```

with `SLOTS_PER_GROUP = 128`, `SLOTS_PER_TASK = 2048`, `SLOTS_PER_WAVE = 65536` (32 tasks), `MAX_WAVE = 64`,
`BOT_ID_BASE = 20000`, `FIRST_SLOT = 100` and `FIRST_PLANNED_SLOT = 8292`. The `botstore.BotId` column stores
the slot and the bot's character id is `BOT_ID_BASE + slot`, so `BotStore.persistedFor(charId)` is a direct
index and a planned slot can never collide with a GM/hand-spawned bot (those draw from the dynamic region
handed out by `nextDynamicSlots`, and `isPlanned(slot) = slot >= FIRST_PLANNED_SLOT` keeps them out of the
store).

`EnvironmentManager.runWave(int number, String name, List<PlanTask> tasks)` hands every task its slot base by
its position in the wave list, and every spawn helper takes a leading `slotBase` (`spawnTownPresence`,
`spawnTownCohort`, `spawnBotsOnMapOnPlatform`, `spawnFMEntranceBotsBatch`, `spawnMerchBotsBatch`,
`spawnFillerBotsHenesys`, `spawnOPQBotsInLobby`, `spawnBlackjackTable`, ...). A task that fans out over
sub-positions encodes its sub-index into the low slot bits, so a bot keeps its row when a room, a platform or
a shop spot is skipped this boot and appears again next boot.

**Why arrival order could not work.** `randomizeCount(base) = max(1, base + random.nextInt(3) - 1)` is used by
the filler/FM/town cohorts, and the tasks of a wave run in parallel, so both the *count* and the *interleaving*
of `createBot` calls differ between boots. The previous `BotStore.nextBotId()` counter therefore handed slot
`100 + arrival` to whoever arrived first: a Kerning grinder adopted a Path-of-Time bot's level, job and look,
the stole-drow row swap crossed waves, the last N stored rows were never adopted and N bots were minted
template-fresh. Deterministic plan slots fix all three at once.

## Fixes this revision

- **Identity (MF-1, confidence 0.9).** Plan slots as above; `BotGeneration.createBot` reads
  `BotStore.isPlanned(slot) && BotStore.restoreIdentity(bot)` *before* `addBotToServer`/`placeBotOnMap`, so the
  spawn packet already carries the stored name, and `restorePlacement` only re-applies the stored point on the
  map the row remembers.
- **Holdings that cannot be read are no longer destroyed (MF-2, confidence 0.85).** `BotStore.writeHoldings`
  used to log and return when the bot's stored items were unreadable, which let the transaction commit and then
  `save`/`saveThenSettle`/`saveAll` discard the ledger - a bot's earned mesos and loot vanished. It now throws
  `SQLException("Not writing the inventory of bot <id> ... is settled onto its map")`, so the save fails and the
  despawn/shutdown rule settles the value onto the map instead of dropping it.
- **An adopted bot is never re-rolled (the OPQ lobby case).** `spawnOPQBotsInLobby` used to call
  `setBotsLevelRange(allBotIds, 50, 70)` over its whole lobby, adopted bots included, so every restart re-rolled
  a returning lobby bot's level and the next save wrote the re-roll over its row. `BotStore` now records the
  bots that took their row from the store (`adoptedThisRun`, exposed as `isAdoptedInThisRun(botId)`, set inside
  `restoreIdentity` right after `stored.applyTo(bot)`) and the lobby rolls levels only for the bots it minted.
- **GM re-runs cannot mint duplicates (SF-1).** `spawnOrdinal` (a boot-lifetime counter that was never reset) is
  gone; `!env loadenv` (`EnvironmentCommand`) and `shutdownInternal(true)` re-run the same plan and therefore
  overwrite the same slots instead of appending new rows. `recordFirstCreation` writes UPDATE-first, so a reused
  slot overwrites its row rather than failing on a duplicate key.

### Why each scope decision

- **A dedicated `botstore` table, not rows in `characters`.** An explicit-id insert into `characters`
  would push that table's AUTO_INCREMENT past 20000 and get the next real player classified as a bot by
  `BotHelpers.isBot` (id > 20000) everywhere. It would also put ~3 200 headless bots into the player
  list and the level-ordered Blessing query (`Character.java:7169`) under one account.
- **Identity comes from the plan slot, not from a generated name.** `BotGeneration` no longer has its own
  `AtomicInteger`; the wave plan decides the slot, so refresh-and-rejoin reproduces the same pixels, not just a
  similar bot.
- **Holdings ride the shared item tables** (`inventoryitems`/`inventoryequipment` keyed by the bot's
  character id, written through `ItemFactory.INVENTORY.saveItems` like every other character). A second
  item representation would have to be kept in step with every future item change; the shared one is
  already the repo's answer for "items that must survive".
- **Initial grants and caps are stated, not inherited.** `BotStore.recordFirstCreation` writes exp 0 and
  meso 0 explicitly rather than trusting whatever the CID 2 template row carries, and the store's slot
  caps come from the row the bot is cloned from (96 per tab) - the same source the login path uses.
- **Despawn saves instead of settling.** `BotHoldings.settle` handed a despawned bot's net loot to the
  map, which was correct only while bots were memory-only. `BotStore.saveThenSettle` now writes the bot
  first and *discards* the ledger, so the value is not also handed to the world; if the store cannot be
  reached, it falls back to the old settle rule so a bot's loot is never destroyed.
- **Shutdown settles the player-facing window first.** `settleAndSaveAll` closes every open trade through
  `Trade.cancelTrade(..., PARTNER_CANCEL)` - the trade's own path, which refunds both sides and nulls both
  `getTrade()` refs - and only then saves. Without that, a player's held items/mesos sat inside the `Trade`
  object and were written out as missing (this was a player-facing loss independent of bots).
- **Boot-time contention is retried, not abandoned.** `BotStore` writes each bot in its own transaction with
  `WRITE_ATTEMPTS = 4` and a jittered backoff, retrying only the two errors two colliding writers produce
  (`isContention`: SQL state `40001`, error 1213 deadlock, 1205 lock wait timeout).

## Evidence

- `BotStoreTest` (21 tests, no database): slot/key round trip, `abandonId` reuse, template identity/capacity
  restore, initial grants, adoption into the same slot, adoption only restores a position on its own home map,
  durable holdings are not also settled onto the map, the unreachable-store fallback (asserts `spawnMesoDrop`
  on a mock map), deadlock retry/give-up/non-retry/contention classification, `aBotWhoseHoldingsCannotBeReadIsSettledRatherThanSilentlyDropped`,
  `aBotsKeyComesFromItsPlaceInTheSpawnPlanNotFromArrivalOrder`, `handSpawnedBotsDrawSlotsTheSpawnPlanNeverUses`,
  `aBotThatTookItsRowFromTheStoreIsMarkedAsAdopted`.
- Repo (no `wz/`): `mvn -o test` -> 1989 tests, 1 failure / 4 errors = exactly the pre-existing WZ-data set
  (`CoarseExecutorTest`, `GCMoveBakeTest`, `GCWorldGraphTest`), unchanged from the ticket-05 baseline.
- Real-WZ sandbox (`/tmp/solomapling-e2e`, 149 MB `wz/`, ports 17575/18484, DB `cosmic_ticket06boot2`):
  `mvn -o test` -> 1989 tests, 0 failures, 0 errors.
- **Five consecutive server boots** (`/tmp/boot_cycle.sh <tag>`: start the jar detached, wait for
  `All bots initialized`, then dump `SELECT BotId, Name, Job, Level FROM botstore ORDER BY BotId` plus row
  counts and a fingerprint). The dump is taken ~5 s after init, i.e. before any save, so a boot's dump shows the
  *previous* boot's saved values; cross-boot level comparisons therefore lag one boot.

| boot | store at load | bots spawned | rows after | rows added | shared slots | identity mismatch | rows lost | error lines |
|---|---|---|---|---|---|---|---|---|
| 1 (fresh DB) | 0 | 3145 | 3145 | 3145 | - | - | - | 0 |
| 2 | 3145 | 3180 | 3313 | 168 | 3145 | 0 | 0 | 0 |
| 3 | 3315 | 3172 | 3393 | 80 | 3313 | 0 | 0 | 0 |
| 4 (this revision) | 3393 | 3179 | 3432 | 39 | 3393 | 0 | 0 | 0 |
| 5 | 3432 | 3168 | 3445 | 13 | 3432 | 0 | 0 | 0 |

  - `shared slots` join is by `BotId`; `identity mismatch` compares `Name|Job` for every shared slot and was 0 in
    every pair, and `rows lost` counts stored rows that no later boot stopped holding (0 everywhere). Rows only
    ever grow, and only where the current boot's plan left a position open: boot2's 168 new rows were all in the
    presence-random tasks (henesys/ludi/perion FM, the town fillers) and landed on the scattered ordinals boot1
    had skipped, while wave 8 (the training cohort) got *zero* new rows and identical per-task counts
    (20/225/225/225/225/225/180/220/200/160/200/200/25/20/20/20). Arithmetic is consistent throughout:
    `3180 spawns = 3012 adopted + 168 minted`, `3313 rows = 3145 + 168`, and the 35-population delta is
    168 - 133 boot1-only slots.
  - Every wave-8 slot's level sits inside its task's band (20/1-15, 225/10-95, ..., 160/70-95, beginners 25/1-9)
    in both boots: identity stayed with the plan position, it did not slide.
  - The OPQ lobby (`spawnOPQBotsInLobby`, wave 7 task 0) is the level case: with the unfixed jar, consecutive
    dumps disagreed on 9 of 14 lobby levels after one boot and on all 14 after the next (both directions,
    e.g. 70 -> 54); with this revision, boot4 -> boot5 preserves **14/14** lobby levels unchanged
    (`level_preserved=14 changed=0`) and the whole population shows `level_raised=0 level_lowered=0` over that
    pair.
  - Equipment survives byte-for-byte: the equipped-item multiset (`inventoryitems` rows for stored bot charids)
    compared across a restart is `shared=3315 changed=0 new_charids=78` - the only difference is the bots minted
    in that boot.
- **Deadlock retry, at scale.** The first sandbox boot lost 2241 of 3145 first-creation rows to MySQL
  `MySQLTransactionRollbackException` deadlocks (~925 rows written); the graceful shutdown sweep then re-covered
  them (925 -> 3145 rows, `[BotEquipChecker] All 3170 bots are dressed.`). After the retry was added, boots 1-5
  each logged **0** occurrences of `Deadlock found`, `Could not write`, `Could not save`, `Not writing the
  inventory`, and the shutdown sweep never had to repair anything. Sandbox MySQL needed
  `SET GLOBAL max_connections=600` (the server pools 100) for queries to run while the server was up.
- **Two-JVM database proof** (throwaway probes, deleted after the run) against a real MySQL 8 container
  with the migration applied:
  - Phase A (process 1): mimic `BotGeneration.createBot`, then `BotStore.recordFirstCreation`. Logged
    `botstore auto_increment before any row = 100`, `characters auto_increment before = 3`,
    `created botId=20100`, `after replay: level=41 exp=987654 meso=9500 items=3 characters_auto_increment=3`
    -> the store creates its own row, grants are explicit (exp 0 / meso 0 at creation), and the
    `characters` AUTO_INCREMENT does not move (bots never touch that table).
  - Phase B (separate JVM): `adopted botId=20100 level=41 exp=987654 meso=9500 items=3
    position=java.awt.Point[x=1234,y=567]`, then `after re-save: rows=3 botstore_rows=1` -> identity,
    progression, holdings and the last-seen position all came back from the previous process, three
    repeated saves left exactly 3 item rows, and `saveAll`/`settleAndSaveAll` counted only the bot.
  - The despawn rule was proven in the same run: `BotHoldings.grantMeso(bot, 1000)` + `saveThenSettle`
    wrote 10500 to the row, and a later `BotHoldings.settle(bot)` handed nothing to the map.

## Review findings folded in

From the standards/spec review of this round (reviewer notes, confidence in brackets):

- **MF-1 [0.9] - the store's key was not stable across boots.** Fixed by plan slots; evidence table above.
- **MF-2 [0.85] - `writeHoldings` swallowed an unreadable-holdings condition and let the ledger be discarded.**
  Fixed by throwing; unit test + 0 log lines in boots 1-5.
- **SF-1 [0.5] - `spawnOrdinal` never reset, so a GM environment reload minted a fresh row per bot.**
  Fixed by removing the counter with `PlanTask`/plan slots.
- **Note-1 [0.4] - the first spawn packet for an adopted bot could carry the placeholder identity.** Fixed by
  restoring identity before the bot joins the map.
- Verified-correct on the first pass and still true: equipment really round-trips (non-login `loadHoldings`
  plus the EQUIPPED dump), adoption cannot orphan a slot, no duplicate `characters.AUTO_INCREMENT` movement,
  the autosave split, the shutdown ordering before `w.shutdown()`, the deadlock retry tests, fame/tier
  round-trip, and the removal of `UNIQUE KEY BOT_NAME` (name collisions no longer fail a first creation).
- Decision-03 gap list (party membership, pending recruit, merchant stock, the TrainingBot train target, the
  mid-trade despawn window, level-up stat grants) is carried as declared limits below, not as work in this
  ticket.

## Known limits (inputs to tickets 07-08)

- **A merchant bot's `PlayerShop` contents are not durable.** Shop stock lives in an in-memory
  `PlayerShop` on the bot; the spawn wave re-stocks a fresh bot each boot, so a restart cannot duplicate
  or lose anything the *player* holds, but the shop's own contents do not survive. Bot-owned
  `HiredMerchant`s are the other half of this (they are registered in `World.activeMerchants` under a
  synthetic owner id).
- **Party and pending-recruit state are dropped at restart, consistently for both sides.** `World.parties`
  and `InviteCoordinator`/`BotRecruitManager` are process memory for players and bots alike, so nothing
  half-restores; if parties ever become durable, the store must grow a party column in the same change.
- **Only three events save a bot**: despawn, shutdown, and the hourly autosave sweep. A crash (or a
  kill -9) still loses everything since the last of those - the store states no write-ahead rule, and
  `USE_AUTOSAVE`/`SHUTDOWNHOOK` are configuration the operator can turn off.
- **A plan position that stops spawning keeps its row without an adopter.** Adoption is by slot, so a wave or
  task that the environment stops spawning (or that shrinks its ordinal fan-out) leaves those rows untouched
  and unclaimed - nothing is lost, and a bot whose task returns adopts its row again. Rows are only ever
  removed by `abandonId`.
- **The store keeps holdings and the row in one transaction but not the character's `map` column**
  (`characters.map` is not written for bots at all), so the last-seen map/position in `botstore` is the
  only placement record.
- **Carried items have no meaningful tab slot.** In the sandbox database a stored bot's *worn* items sit at
  their proper negative positions (`-5 -6 -7 -8 -9 -10 -11`), but every carried item holds `position = 0`
  (e.g. charid 100101: 8 worn rows plus 15 equip-tab rows all at 0), while template character 1 has distinct
  positions. The store round-trips that shape exactly (the multiset is stable across restarts), so nothing is
  lost; the degraded layout comes from how bots receive items (`inv.addItem`) rather than from the store, and
  only matters if a UI ever renders a bot's tabs.
- **Pre-existing, not caused by the store:** one `java.util.ConcurrentModificationException` at
  `ArrayList.sort` -> `client.Character.getFriendshipRings` -> `tools.PacketCreator.addRingLook` ->
  `spawnPlayerMapObject` -> `Character.sendSpawnData` -> `MapleMap.addPlayer` -> `Character.changeMapInternal`
  -> `soloMapling.ArtificialPlayer.BotTypes.TrainingBot.doDecide` (a bot changing map while another thread sorts
  the rings list).
