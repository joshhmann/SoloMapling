# 07: Remaining reconciliation rules

**What to build:** The outstanding boundary rules are settled and enforced: whether progression bots carry quest state, how multi-level offscreen jumps are presented, whether map-slot claims survive demotion, what happens to travel attempted mid-trade, and how recruit capacity is reserved atomically.

**Blocked by:** 04 Durability boundary across observed and offscreen states.

**Status:** done (2026-10-17). All five rules are stated below, implemented at one seam each, and covered by
headless tests; the sandbox boot at the end is the runtime proof that the seams are on live paths.

- [x] Quest durability for progression bots is decided and applied consistently — rule 1: bots carry no
      quest state, enforced at the character-level quest writers.
- [x] Multi-level offscreen gains produce a defined observer-visible outcome — rule 2: level/exp/stats under
      the character monitor, nothing replayed, observers see only the next spawn packet's level byte.
- [x] Map-slot claims either persist across demotion or are re-acquired on promotion by one rule — rule 3:
      kept across demotion/promotion, ended only by a map change or a despawn.
- [x] Travel during an active trade follows one explicit freeze-or-abort rule — rule 4: abort-then-travel, the
      travel callback still fires.
- [x] Recruit acceptance reserves follower capacity atomically so concurrent accepts cannot exceed the cap —
      rule 5: one reservation per accept, released at conversion or refusal, and a party that never exceeds 6.

## The five rules

Written as the code states them; each is enforced at exactly one seam, so the rule cannot be true in one path
and false in another.

> **1. A bot carries no quest state.** Nothing grants it (bots are cloned without the channel-server quest
> block and never log in, so `saveCharToDB` never runs for them, and the store has no quest column); the
> character-level quest writers refuse to build it, so a future grant cannot create in-memory state that would
> be dropped at despawn while still awarding quest points and broadcasting quest packets.

> **2. Offscreen progression is silent, atomic and stat-consistent.** EXP accrues under the same monitor as
> the observed gain path, levels and leftover EXP are written from one pure calculation, local stats are
> re-derived, and nothing is replayed to observers: the only visible trace of a multi-level jump is the higher
> level byte in the bot's next spawn packet. One level-up event per accrual goes to the internal event bus.

> **3. A claim is owned by a bot id and survives demotion.** It is kept across observed/offscreen flips and
> re-adopted by the same claim call, and it ends only at a map change or a despawn — and a despawn ends it in
> one place, for every caller, whatever path the bot took out of the world.

> **4. A bot never travels out of an open trade window.** A trip requested mid-trade cancels the window first
> (partner-cancel: the bot's offered mesos come back, the player's items are returned), then movement starts;
> the trip is never refused, so the caller's callback fires on every path.

> **5. Follower capacity is reserved at accept time and released when the accept or the conversion does not
> land; a party never exceeds six members.** The reservation is what makes the ask-time cap check honest
> (concurrent accepts cannot all pass the same stale reading), and the party cap is taken under the party lock
> next to the insert, so it cannot be raced.

## 1. Bots carry no quest state

**Why not "durable quests".** Bots have no quest source: `BotGeneration.java:63` loads the template clone with
`channelserver=false`, so the quest load at `client/Character.java:7181-7237` never runs; `loggedIn` is never
true, so `saveCharToDB` early-returns; and `botstore` has no quest column, so anything that did land in
`quests` would be process memory only. Durable quests would mean new schema, new save/load, and a grant source
that does not exist — for a field no player can observe, since bots are never quest NPCs.

**Enforcement** (one predicate, one line per writer), `client/Character.java`:

- `tracksQuestState()` (:5838) — `!BotHelpers.isArtificial(getId())`. `BotHelpers.isArtificial(int)` (:46) is
  now public (it was private) and means "id above the produced-bot base", console pseudo-character excluded.
- Guards on the read/write pair: `getQuest(Quest)` :5847 returns a transient `NOT_STARTED` status,
  `getQuestNAdd` :5864 the same, `getQuestNoAdd` :5891 returns null, `setQuestAdd` :5877 returns,
  `getQuestRemove` :5900 returns null, `updateQuestStatus` :9868 returns **before** the map write, the packet
  and the quest-point award.

**Evidence:** `BotQuestStateBoundaryTest` (2 tests) drives the real `Character` API with a mocked quest: an
artificial character's `getQuestNoAdd` stays null, `getQuest`/`getQuestNAdd` answer without storing,
`setQuestAdd` and `updateQuestStatus` mutate nothing (no quest points, no map entry).

## 2. Offscreen progression is silent, atomic and stat-consistent

**What the outcome is.** `soloMapling.ArtificialPlayer.BotGrindSystem.OffscreenProgression.accrue(level, exp,
gain, levelCap, expNeededForLevel)` is a pure function returning `Gain(level, exp, levelsGained)`: it consumes
the gain against the level table while the level is below the cap and keeps the surplus (clamped at
`Integer.MAX_VALUE`). `client/Character.applyOffscreenExp(long, int)` (:3161) is `synchronized`, i.e. on the
same monitor as `gainExpInternal`, so a concurrent observed kill cannot lose either gain; it writes
`level`/`exp` and calls the (private, packet-free) `recalcLocalStats()` when a level was gained, so a promoted
bot never fights with the stats of its old level. `TrainingBot.accrueAbstractExp` (…:1194-1207) now only
computes the gain, applies it and publishes one `EventFactory.createLevelUpEvent` via `EventBus` (:1206) when
levels were gained; the old in-place read-modify-write loop is gone.

**Observer-visible outcome:** none at the moment of accrual. The level byte in the next
`PacketCreator.spawnPlayerMapObject` is the whole trace (LOD promotion emits no spawn packet of its own —
`GCMovementDriver.reconstructPhysicsFromCoarse` only resets state and teleports), so an observer sees a
higher-level bot standing where it was promoted, with no level-up effect replay. The store path re-derives
stats too: `BotStore.restoreIdentity` calls `bot.refreshStatsAfterProgression()` (:348) after holdings are
loaded.

**Evidence:** `OffscreenProgressionTest` (6 tests) covers a single-level crossing with no remainder, a
multi-level crossing in one tick (30 → 33 with 90 EXP left), the cap with surplus retained, a no-gain no-op and
a non-positive level table.

**Named limit (deliberate).** Level-triggered *milestones* are observed-only: the `level % 20` inventory slot
grants and rate upgrade (`client/Character.java:6546-6562`), the `PERFECT_PITCH` mint (:6564), and the level-10
starter-party hint/leave. An offscreen jump applies level, EXP and stats but does not mint items or expand
inventory, so two bots at the same level can differ by those grants depending on whether the crossing was
observed. This behaviour predates this ticket (the previous offscreen path was a raw `setLevel`/`setExp` loop)
and is now a stated rule rather than an accident; wiring the milestones into the offscreen path is a
follow-up candidate if inventory pressure shows up in ticket 08's budget work.

## 3. A claim is owned by a bot id and ends only at a map change or a despawn

`BotSpotClaims` keeps `mapId → spotId → (botId → slotIndex)` (`:23`); a bot's slot fixes its X band via
`section()`. Demotion to offscreen and promotion back both keep the claim (the demotion path returns before any
release; `BotTransitionBoundaryTest.promotionLeavesPartySellerAndSpotCommitmentsAlone` asserts exactly that).

**Enforcement:** `BotSpotClaims.releaseAllFor(int botId)` (:70) walks every (map, spot) and removes the bot's
slot, counts what it released, prunes emptied spots and maps (the registry is a census of live holders), and is
idempotent. It is called from `BotGeneration.removeBotFromServer` (:229), which now ends a departed bot's
lifecycle state in one place, in this order:

1. `BotStore.closeOpenWindow(fakechar)` — an open window holds items and mesos out of both wallets, so a save
   taken before the cancel would write the bot out without the refund;
2. `GCMovement.disable(fakechar)` — dynamic control owns a per-bot driver, its travel/follow pollers and the
   shared movement lock; `disable` is null-safe and idempotent;
3. `BotSpotClaims.releaseAllFor(fakechar.getId())`;
4. `BotStore.saveThenSettle(fakechar)` and the existing removal from map, channel, world storage and the
   in-memory registries.

The leak this closes: `/dchere` (and any caller that does not run `manuallyStopBot` first) reached
`removeBotFromServer` directly, so the bot left the world while its claim stayed in the registry forever,
holding a slot that a live bot then could not take.

**Evidence:** `BotSpotClaimsTest` (6 tests, 2 of them new) — release frees a bot on every map it claimed,
leaves other holders alone, prunes the maps it emptied, hands the freed slots to the next claimer, is a no-op
for an unknown bot and for a second release of the same bot.

**Limit:** `removeBotFromServer` itself is verified by reading plus the boot below — the call needs a channel,
a world player store and the database, so it has no headless test. The primitive it calls is tested.

## 4. A bot never travels out of an open trade window

`GCTravel.travel` used to start the trip unguarded. Because the movement driver freezes a traveling bot while
it holds a trade (`GCMovementDriver` commitment hold), a trip requested mid-trade sat still until the 20 s soft
lock / 90 s hop ceiling warped the bot, and the window died implicitly inside `Character.changeMapInternal`
with an unrelated cancel reason.

**Enforcement:** `BotTradeCommands.abortOpenTradeForTravel(Character)` (:302) — null or no trade returns false
with no side effects; otherwise `Trade.cancelTrade(bot, PARTNER_CANCEL)`, the same path
`BotStore.closeOpenWindow` uses, which refunds the bot's offered mesos and returns the partner's items, plus one
log line ("Bot {} closed an open trade window to start travel"). The cancel is wrapped in a try/catch: a
cancellation failure warns and returns false but never throws, so the trip still starts. The call site is
`GCTravel` (:121), after the same-map early return (a same-map request moves nothing, so nothing leaves the
window) and before `GCMovement.enable`. The trip is never refused, so `TownWandererBot`'s and `TrainingBot`'s
callbacks fire on the same-map, arrival, failure and cancel paths exactly as before.

**Evidence:** `BotTravelTradeAbortTest` (3 tests) — a window is cancelled with `PARTNER_CANCEL` exactly once
(static mock on `Trade`), no trade means no interaction, and a real window with 400 of 1000 mesos offered ends
with the bot holding 1000 again and a second call not refunding twice.

## 5. Follower capacity and the party cap

**Follower cap (`FOLLOWER_CAP = 30`, `BotRecruitManager.java:26`).** Asking and accepting are two different
moments, and the ask-time check reads a count that live followers alone cannot close: the accept tick converted
the bot a moment later. `activeFollowerCount()` (:194) is now the live scan **plus** accepts in flight, and
`tryReserveFollower(int)` (:218) takes one slot with a compare-and-set loop against that scan — refuse when
live + held would exceed the cap, and an id-keyed set makes a take or a release idempotent per bot, so a
double take cannot leak capacity and one release cannot free two slots. `releaseFollowerReservation(:236)`
decrements only when the id was actually held.

**Ordering (one place, `pollInvites` :116-131):** (1) reserve, (2) accept the invite, (3) the converter
releases. A refused reservation fails closed: the bot politely rejects and does not join. If the accept itself
fails (the coordinator's window expired), the reservation is released before returning. `SocialBot` releases in
a `finally` around lookup → speak → `setPendingLeader` → `convertBotType`, so success, a refused conversion and
a throw all leave no held slot.

**Party cap.** `Party.MAX_MEMBERS = 6` (`Party.java:64`) and `Party.addMember` (:83) is now `boolean` and
takes the size check **and** the insert under the party's existing `ReentrantLock`: a party at six returns
false having mutated nothing. `World.updateParty(int, PartyOperation, PartyCharacter)` (:1018) returns whether
anything changed, and its JOIN branch only registers and announces a member that really joined. `Party.joinParty`
(:374-386) keeps its advisory pre-check and, when the JOIN is refused, returns false after the pre-existing
`partyStatusMessage(17)` ("party is full") — the same player-visible answer the pre-check branch gave, so
nothing changes for a legitimate sixth member. All eight other `updateParty` call sites are expression
statements that discard the result.

**Evidence:** `BotRecruitCapacityTest` (8 tests) — live scan plus in-flight accepts accounting at the cap, the
ask-time check seeing an accept still in flight, a 200-round two-thread race for the last slot in which exactly
one accept wins and the loser leaves no claim, a refused accept freeing its slot when a follower departs,
re-reserving for the same bot holding one slot, a release with nothing held being a no-op, and the party half:
a sixth member accepted, a seventh refused, plus an eight-thread race for the last party slot in which exactly
one joins.

**Limits:** `World.updateParty(JOIN)` end-to-end needs a booted world player storage, so the tests drive
`Party.addMember` (the seam World consults) and the World branch is verified by reading;
`SocialBot.pollRecruitInvite`'s release-on-throw and `pollInvites`' reserve-then-reject seam need a real client,
channel, queue and invite coordinator, so the reservation API they call is tested directly instead. No runtime
run has yet had a follower accept at the cap or a trade open at the moment a trip starts (see the boot log
below: zero occurrences of both log lines in a two-minute run) — those two paths rest on their unit tests.

## Evidence

- **Repo suite (no `wz/`)**: `mvn -o test` → **2010 tests, 1 failure / 4 errors**, i.e. exactly the pre-existing
  WZ-data set (`GCWorldGraphTest`, `GCMoveBakeTest`, `CoarseExecutorTest`), unchanged from the ticket-06
  baseline; 21 of the 2010 are new here.
- **Sandbox suite with real `wz/`** (`/tmp/solomapling-e2e`, same source as the jar below): `mvn -o test` →
  **2010 tests, 0 failures, 0 errors, BUILD SUCCESS** — the three WZ-data failures of the repo run are the
  missing `wz/` directory, nothing else.
- **New/changed tests, run together**: 70 tests, 0 failures — `BotStoreTest` 21, `BotTransitionBoundaryTest` 10,
  `BotCommitmentsTest` 10, `BotRecruitCapacityTest` 8, `BotSpotClaimsTest` 6, `OffscreenProgressionTest` 6,
  `BotTradeOfferTest` 4, `BotTravelTradeAbortTest` 3, `BotQuestStateBoundaryTest` 2.
- **Real-WZ sandbox boot** (`/tmp/solomapling-e2e`, 149 MB `wz/`, ports 17575/18484, DB
  `cosmic_ticket06boot2`; jar built from this change set — `diff -rq` against the repo differs only by the two
  port lines):
  - `Bot store holds 3445 bots`, then `=== All bots initialized: 3161 bots in 15.0s ===`;
  - two minutes of live ticking, **0 exception or error lines**, `[BotEquipChecker] Scan complete: 6/3161
    naked`;
  - graceful `SIGTERM` → `Worlds and channels are offline.`;
  - store after the shutdown sweep: **3456 rows (+11, the plan positions this boot left open), 0 rows lost, 0
    identity mismatches** (`Name|Job` for all 3445 shared `BotId`s), levels 1..95 (cap 195), no negative EXP,
    **413 rows' EXP advanced (sum +746 225)** — the accrual path is on a live path, persisted, and produces
    no out-of-range value.
  - One pre-existing shutdown-race NPE was observed after "Worlds and channels are offline":
    `MapleMap.calcPointBelow` (`server/maps/MapleMap.java:504`, `footholds` nulled at `:4553` when the map was
    disposed) ← `BotPhysicsEngine.pointBelowIndexed` ← `RestSpotFinder.buildContext` ← `GrindBreakRoutine.begin`
    ← `TrainingBot.doGrind`, from a still-running tick virtual thread. No file on that path is part of this
    change set, the run's saved state is consistent, and it is reported here rather than fixed because it is
    not this ticket's boundary.
- **Rule-specific runtime signals**: `closed an open trade window to start travel` and the follower-cap
  refusal line did not occur in the two-minute boot (no mid-trade trip, no accept at the cap), so rules 4 and 5
  rest on their unit tests and on reading their single seam each.

## What stays open for ticket 08

- Level-triggered milestones (slot grants, rate upgrade, `PERFECT_PITCH`) are observed-only — decide whether
  the offscreen path should mint them, which is an inventory-capacity question rather than a transition one.
- The shutdown-race NPE above: a tick can still run against a disposed map at shutdown. Nothing in this ticket
  depends on it, but a settlement pass that stops ticks before the world is torn down would remove it.
- Follower/party behaviour under load (many accepts in the same tick, a party at six with a seventh player
  asking) has unit coverage but no runtime observation yet.
