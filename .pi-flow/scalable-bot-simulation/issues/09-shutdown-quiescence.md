# 09: Orderly bot shutdown (quiesce before settlement)

**What to build:** A shutdown phase that quiesces bot-owned activity before the bot settlement sweep and before world/channel disposal: no bot task body starts once the phase begins, in-flight macro ticks drain under a bounded wait, and the settlement sweep therefore records a frozen population instead of racing one.

**Blocked by:** 06, 07, 08 Shared resource accounting path and persistence store (the sweep this phase protects); Remaining reconciliation rules (the rules it must not break); End-to-end integration proof (the harness that demonstrates it).

**Status:** done (2026-10-17)

- [x] No bot-owned task body starts after `BotShutdown.begin()`; the macro wheel dispatches nothing and a late `register`/`nudge`/`reschedule` cannot re-arm it.
- [x] In-flight macro ticks drain within a bounded wait; shutdown always proceeds and logs the residue (tick count) when they do not.
- [x] The settlement sweep runs against a frozen population, and no bot-side world access or row write follows it before the JVM exits.
- [x] Every recurring bot task is gated or cancelled (the table below); each one has an anchor and a check at its body head.
- [x] A boot + shutdown run produces zero bot-side exception lines after `Shutting down the server!` (the three stack shapes recorded below are gone).
- [x] Steady state unchanged: full suite green and the ticket-08 proof still passes with the wheel still spinning during the sparse window.

## The risk

Today's shutdown order (`net/server/Server.java:1937` `shutdownInternal(boolean)`):

1. log `Shutting down the server!`
2. per world: `BotStore.settleAndSaveAll(players.getAllCharacters())` — the ticket-06 sweep, while maps are still alive
3. per world: `w.shutdown()` → per channel `ch.shutdown()` (`net/server/world/World.java:2159` → `net/server/channel/Channel.java:183`) → `mapManager.dispose()` (`Channel.java:198`, `MapManager.java:135`) → `MapleMap.dispose()` → `footholds = null` (`server/maps/MapleMap.java:4553`), then `channelServer.stop()` and `finishedShutdown = true`
4. wait for `ch.finishedShutdown()`, `resetServerWorlds()`, `ThreadManager.stop()`, TimerManager purge/stop, log `Worlds and channels are offline.`, `loginServer.stop()`, `LogManager.shutdown()`, then `new Thread(() -> System.exit(0)).start()`

Nothing in that sequence stops bot activity. `BotTickService.ensureDriver()` (`soloMapling/server/BotTickService.java:135-139`) schedules the wheel on the shared `ExecutorServiceManager.getScheduledExecutorService()` and **discards the returned future**; the class has no stop path at all. `BotTickService.register` is called from `BotSM` on every bot start, `drive()` dispatches ticks through `ExecutorServiceManager.runAsync` onto **virtual threads** (`BotTickService.java:171`), and `soloMapling/server/ExecutorServiceManager.java:48 shutdown()` exists but has **no caller anywhere in `src/main/java`** (grep). So bots keep simulating while step 3 disposes the maps they stand on.

### Observed evidence (three stack shapes, all bot-task-driven)

Recorded in the ticket-08 verify teardown, `/tmp/verify_rerun2.out` — 9 exception lines, **all after `PROOF-VERIFY PASS`**, i.e. after the harness called `System.exit(0)` and the shutdown hook started:

| # | Shape | Frames |
|---|---|---|
| 1 | travel start on a disposed map | `NullPointerException: Cannot invoke "server.maps.FootholdTree.getAllFootholds()" because the return value of "server.maps.MapleMap.getFootholds()" is null` at `GCMoveSystem.BotMovementManager.buildFhIndex(BotMovementManager.java:1019)` ← `GCMovement.enable` ← `GCTravel.travel(GCTravel.java:122)` ← `GCMovement.travel(GCMovement.java:323)` ← `TrainingBot.doTravel(TrainingBot.java:665)` ← `TrainingBot.updateState:370` ← `BotSM.lambda$new$0:83` ← `BotTickService.runTick:200` |
| 2 | map change on a dead channel | `NullPointerException: Cannot invoke "net.server.channel.Channel.getMapFactory()" because the return value of "client.Client.getChannelServer()" is null` at `client.Character.changeMap(Character.java:1380)` ← `TrainingBot.doDecide(TrainingBot.java:634)` ← `TrainingBot.updateState:374` ← `BotSM.lambda$new$0:83` |
| 3 | grind break on a disposed map (ticket-07 boot) | `NullPointerException: Cannot invoke "server.maps.FootholdTree.findBelow(java.awt.Point)" because "this.footholds" is null` at `server.maps.MapleMap.calcPointBelow(MapleMap.java:504)` ← `getPointBelow:1873` ← `BotPhysicsEngine.pointBelowIndexed:2563` ← `findGroundPoint:403` ← `GCMovement.groundPointBelow:659` ← `RestSpotFinder.buildContext:328` ← `GrindBreakRoutine.begin:108` ← `TrainingBot.doGrind:761` ← `BotTickService.runTick:200` |

Every shape is a bot task reading world state after disposal. The count varies with timing: an abrupt harness exit (population fully live when `System.exit` fires) produced 9 lines in a 5 s teardown; a longer, ordered player-style shutdown produced 1 (`calcPointBelow`). A larger population and up to 4 channels widen the window.

### Why it matters (beyond log noise)

- **Swallowed, not fatal.** `BotTickService.runTick` catches `Throwable`, `safeDrive` catches `Throwable`, `TrainingBot.combatTickAll` and `BotEquipChecker.check` catch per bot. Nothing crashes, no shutdown step is skipped — which is exactly why this hides: a *real* shutdown-time error becomes indistinguishable from the known spam.
- **It breaks the sweep's contract.** The sweep (step 2) is the point of the ticket-06 store: "final state written while the world is still alive". Bot ticks that keep running after it can gain mesos, loot, complete or open a trade window, or change map, none of which is in the row that was just written — the settled state is then not the final state, and `[INFERENCE]` a trade window opened after the sweep is process-only state that dies with the JVM.
- **Population scaling.** Every extra bot is another tick racing disposal, and the teardown window grows with the population and with the number of channels being torn down (channel 1 → channel 3 took ~26 s in the ticket-06 boot log).

### Alternatives considered

| Option | Why not |
|---|---|
| Null-guard the accessors (`buildFhIndex`, `changeMap`, `getFootholds`) | Symptom suppression: converts a race into a silent no-op and leaves the sweep racing an active population. |
| Gate only the two observed sites (`GCMovement.enable`, `changeMap`) | Incomplete — shape 3 is a third site already, and any new bot entry point re-opens it. |
| Do nothing; treat it as cosmetic | Accepts a broken settlement contract and growing error spam. |
| Call `ExecutorServiceManager.shutdown()` first | The sweep and the autosaver need `runAsync` on that pool (`CharacterAutosaverTask` imports it), and GM commands share it (`BotMoveCommand`, `EnvironmentCommand`); killing the pool before the sweep is a hang/failure risk for no gain. |
| Settle after `w.shutdown()` | The sweep closes open trade windows and refunds both sides — it needs live maps and channels to produce its packets. |
| Sleep/retry around disposal | Timing-dependent, still racy. |

## The fix

One bot-owned gate plus two cancellations and a bounded drain, wired at the head of the shutdown sequence.

1. **Gate.** `soloMapling/server/BotShutdown.java`: `private static volatile boolean quiesced`, `public static void begin()` (idempotent, logs one `[BotShutdown] bot activity quiesced` line), `public static boolean isQuiesced()` (a single volatile read, cheap enough for every task body head). One-way for the process run: the `restart=true` branch of `shutdownInternal` is unreachable today (the only caller is `client/command/commands/gm6/ShutdownCommand.java:81` with `shutdown(false)`); if it is ever wired, `Server.init()` must clear the gate.
2. **Wheel stop + drain.** `BotTickService.stop()`: (a) cancel the driver future — `ensureDriver` must keep it in a static field instead of discarding it, (b) make `register`/`nudge`/`reschedule` inert when quiesced so an in-flight tick cannot re-arm the wheel, (c) wait until no `Entry.ticking` guard is set, bounded by `DRAIN_MS = 5000`, (d) log `[BotShutdown] macro wheel drained in Xms (residue N ticks)`. The drain reuses the existing per-entry `AtomicBoolean ticking` (no new bookkeeping); the gate itself is set by `begin()` (step 4), not here.
3. **Cancel the global scanners and gate every recurring body.** Cancel: the combat ticker, the equip checker, the decoration queue, the observer tracker, the messaging queue tasks, the wander task, the OPQ orchestrator. Gate at body head: the per-bot movement-driver / fidget / follow / travel tasks (their futures are per bot, so the gate is the cheap guard).
4. **Order.** In `Server.shutdownInternal`, immediately after the `getWorlds() == null` idempotency check and **before** the ticket-06 sweep loop:

```
BotShutdown.begin();
BotTickService.stop();
// ... existing ticket-06 comment + settleAndSaveAll loop, then w.shutdown()
```

The settlement sweep then runs on a frozen population; its own `runAsync`/`Trade.cancelTrade`/store writes are unaffected because the executors stay alive (they die with `System.exit`).

## Change surface

| Anchor | Recurring task | Action |
|---|---|---|
| `soloMapling/server/BotTickService.java:135` `ensureDriver` + `:80` `register` | the macro wheel (all ~3k bots) | keep the future, cancel, gate `register`, drain |
| `soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:179` `ensureCombatTicker` | shared combat sweep | cancel + gate `combatTickAll` |
| `soloMapling/ArtificialPlayer/BotDecoratorSystem/BotEquipChecker.java:27` | 2 min scan + `runAsync` equipping | cancel (handle `task` exists) + gate `check` |
| `soloMapling/ArtificialPlayer/BotDecoratorSystem/BotDecorationQueue.java:64` | decoration pass (own pool) | cancel + gate |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:99` | per-bot coarse simulation (own pool) | gate body head |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCFidget.java:59` | per-bot fidget animation | gate |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCFollow.java:55` | per-bot follow | gate |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCTravel.java:126` | travel poll, warps via `changeMap` | gate the trip tick (shape 1/2 source) |
| `soloMapling/ArtificialPlayer/GCMoveSystem/ObserverTracker.java:111` | LOD refresh (local pool) | keep the handle, cancel + gate |
| `soloMapling/ArtificialPlayer/BotWanderSystem/BotWanderSystem.java:184` | wander tasks (shared pool) | cancel + gate |
| `soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQOrchestrator.java:322` | party-quest orchestrator | cancel + gate |
| `soloMapling/ArtificialPlayer/BotMessagingSystem/QueueCleaner.java:21`, `QueueMonitor.java:31`, `Dispatcher.java:27` | 1–2 s queue passes | gate (they broadcast) |
| one-shots already queued (`TrainingBot.java:541,545,720`; `DropCommands.java:67,74`; `SocialHotPotatoManager.java:135,149`; `ConversationManager.java:123`; `ArtificialFreeMarket.java:123,492,499`; `EnvironmentManager.java:679,919`; `PlatformPlacement.java:220,294`) | bot-driven single fires | gate the bodies that touch world state; boot-only ones need no check |
| `net/server/Server.java:1937` `shutdownInternal` | — | the 2-line wiring + comment |

## What was built (as landed)

New: `soloMapling/server/BotShutdown.java` — `volatile boolean QUIESCED` + `CopyOnWriteArrayList<Runnable> HOOKS`; `begin()` logs `bot activity quiesced (N schedulers cancelled)` and runs each hook once (a hook that throws is logged, the rest still run); `onQuiesce(Runnable)` runs immediately when the gate is already shut, otherwise registers it (dedupe by identity); `isQuiesced()` is the single volatile read used at every body head. `resetForTest()` is the one package-private seam outside production use.

| Anchor | Change |
|---|---|
| `soloMapling/server/BotTickService.java:157-181` | `CANCEL_DRIVER` held as one stable instance; `ensureDriver` keeps the future in `driver` and registers the hook; `cancelDriver()` cancels it and clears `DRIVER_STARTED` (so the flag tracks "a live driver exists" instead of latching the wheel off); `register`/`nudge`/`reschedule` all return early on the gate |
| `soloMapling/server/BotTickService.java:192-210` | `stop()` = `cancelDriver()` → wait for `tickingCount() == 0` bounded by `DRAIN_MS = 5000` → `ENTRIES.clear()` → log `macro wheel drained in Xms (residue N ticks)` |
| `net/server/Server.java:944-950` | `BotShutdown.begin(); BotTickService.stop();` immediately after the `getWorlds() == null` idempotency check, before the ticket-06 sweep comment and loop |
| `soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java` | `ensureCombatTicker` keeps the future and registers the hook; `combatTickAll` head gate; the scheduled `doTravel` callbacks (`:666` pattern) cancel as no-ops when quiesced; the sauna schedule/shortcut callbacks gate |
| `BotDecoratorSystem/BotEquipChecker.java`, `BotDecoratorSystem/BotDecorationQueue.java` | handle kept + hook registered + `check`/pass body head gate (the queue drains its pending decoration work) |
| `GCMoveSystem/{GCMovementDriver,GCFidget,GCFollow,GCTravel}.java` | per-bot task bodies gate at the head (driver also re-checks its own pool task); `GCTravel`'s trip tick is the shape-1/2 source that the gate closes |
| `GCMoveSystem/ObserverTracker.java` | local pool handle kept + hook registered + LOD refresh body gate |
| `BotWanderSystem/BotWanderSystem.java`, `BotTypes/OPQ/OPQOrchestrator.java` | hook registered + body head gate |
| `BotMessagingSystem/{Dispatcher,QueueCleaner}.java` | body gate (they broadcast, so a quiesced server delivers nothing); `QueueMonitor` is dev-only and left ungated |
| `server/BotTiming.java`, `server/MethodScheduler.java` | `after(...)` and `runAfterDelay(...)` refuse to schedule once quiesced — this covers the bot-driven one-shots (`DropCommands` pickup/expiry chain, `SocialHotPotatoManager`, `ConversationManager`, `ArtificialFreeMarket` shop open, `TrainingBot` restart ticks) without touching each call site |
| `BotCommandsPack/DropCommands.java`, `FreeMarket/ArtificialFreeMarket.java`, `SocialHotPotatoManager.java`, `ConversationManager.java` | body gates where a task was already in flight or the call sits on a player-facing path |
| `src/test/java/soloMapling/server/BotShutdownTest.java` | 6 tests: idempotent one-way gate + hook-registry semantics, hooks registered after the gate, a failing hook not stopping the rest, no work accepted after the gate, `stop()` drains an in-flight tick, wheel re-arms when the gate is reopened |

Not gated on purpose: boot-only schedulers (`EnvironmentManager` world init, `PlatformPlacement`, `ArtificialFreeMarket` initial shop shelf, `GCWorldGraph` bake, `EquipMetadataCache`) run before any shutdown can be requested, and the player disconnect path is untouched (the gate is only ever set from `Server.shutdownInternal`).

## Verification

1. **Unit (headless).** `BotShutdownTest` (6 tests, green in both trees): `begin()` is idempotent and one-way; `isQuiesced()` flips; a duplicate hook instance is absorbed while distinct hooks each run once; a hook registered after the gate runs immediately; a hook that throws does not stop the rest; `register` after the gate adds nothing and the wheel never dispatches; `stop()` waits for a tick that is already running; a wheel stopped and re-armed dispatches again.
2. **Evidence run.** Sandbox population rebooted (`/tmp/solomapling-e2e`, real WZ, DB `cosmic_ticket06boot2`) and both ticket-08 phases re-run (3171 bots in the run phase, 3167 in verify). Measured the same way as the baseline — `Exception|ERROR` lines after the harness's last `[PROOF]` line, i.e. inside the teardown window (logs preserved in `/tmp/ticket09-evidence/`):

| Teardown window | Before (pre-change logs) | After |
|---|---|---|
| ticket-08 `verify` phase (population live at `System.exit`) | **9** lines, 3 stacks (`/tmp/verify_rerun2.out`; an earlier verify boot recorded 2, `/tmp/verify_rerun.out`) | **0** (`/tmp/ticket09-evidence/botproof_verify.out`) |
| ticket-08 `run` phase (population fully live) | 1 `MapleMap.calcPointBelow` NPE in a recorded boot (ticket-08 record) | **0** (`/tmp/ticket09-evidence/botproof_run.out`) |

The drain lines are the last lines of each window: `[BotShutdown] macro wheel drained in 5008ms (residue 15 ticks)` (run) and `5021ms (residue 1 ticks)` (verify), each preceded by `[BotShutdown] bot activity quiesced (6/3 schedulers cancelled)`. A residue above zero means the 5 s bound was reached with ticks still in flight — designed behaviour (the bound and the report are the contract, and the sweep still proceeds on a population the gate froze), and the observed values come from a host running many other containers while the wheel was backlogged (`perf-crowded` macro-tick 5839 ms/s over 929 samples).

   **Ordering, proven at runtime.** Re-running the `run` phase with log4j enabled (`java -Dlog4j2.configurationFile=/tmp/log4j2-teardown.xml -cp target/Cosmic.jar:target/test-classes soloMapling.ArtificialPlayer.BotPopulationProof run > /tmp/botproof_ordered.out`) shows the phase sits where the design puts it — `Server.java:944-950`, i.e. after the idempotency check and before the sweep loop and `w.shutdown()`:

   ```
   10:37:50.261 [Thread-0] INFO server.Server - Shutting down the server!          <- Server.java:1940
   [BotShutdown] bot activity quiesced (6 schedulers cancelled)
   [BotShutdown] macro wheel drained in 5007ms (residue 17 ticks)
   10:38:15.483 [Thread-0] INFO channel.Channel - Shutting down channel 1 in world 0  <- Channel.java:189
   10:38:26.129 [Thread-0] INFO channel.Channel - Successfully shut down channel 3 in world 0
   10:38:26.130 [Thread-0] INFO world.World - Finished shutting down world 0
   10:38:26.131 [Thread-0] INFO server.Server - Worlds and channels are offline.    <- Server.java:2005
   ```

   ~20 s separate the drain from the first channel disposal (that gap is the ticket-06 settlement sweep the gate protects), and the whole 170-line ordered log contains **0** `Exception|ERROR` lines. Note for reproducing the *other* logs: the harness runs on the test classpath, where `src/test/resources/log4j2-test.xml` (`Root level="off"`) suppresses every log4j line — which is why the plain harness captures only stdout and their teardown anchor has to be the last `[PROOF]` line.
3. **No steady-state regression.** Repo suite: 2016 tests, 1 failure / 4 errors — exactly the 5 pre-existing WZ-missing failures (baseline 2010 tests, same 5). Sandbox suite on real WZ: `mvn -o test` BUILD SUCCESS (all green, including `BotShutdownTest`). Ticket-08 proof: every PASS line intact (`PROOF-VERIFY PASS`; the restored row, holdings, wallet and level/exp all match the state file), and the wheel still spins in the sparse window — `perf-sparse | BACKGROUND macro-tick: 3580.45 ms/s samples=2555` (`/tmp/ticket09-evidence/botproof_run.out`).
4. **Interactive path untouched.** Exercised in the same run: the process exits with a bot's trade window open (`open window: bot 577332 offers 250 mesos to bot 577182 ... wallet 468339 -> 468089, window open=true`) and the verify phase confirms `PASS the open trade window was settled before the row was written - wallet before the offers=468339, wallet with the offers open=468089, stored meso=468339` — the settlement path ran under the gate exactly as before, and the gate never touches direct interaction calls (only bot-owned periodic tasks).

## Non-goals

- No defensive null checks on `MapleMap`/`Channel` accessors; the ordering is the fix.
- No per-bot teardown loop, no extra persistence, no change to the settlement rule.
- No change to `ExecutorServiceManager`'s own lifecycle, to the player disconnect path, or to `w.shutdown()` internals.
- No scheduler, cadence or governor change; the gate is only ever set from the shutdown path.

## Open questions (settled by the build)

- **Drain bound.** Kept at a fixed `DRAIN_MS = 5000`. Observed residue (15 ticks in the run phase, 1 in verify) came from host backlog, not from a slow bot cadence — a cadence-derived bound would have stretched shutdown without finishing those ticks any sooner, and the log makes a too-short bound visible.
- **Bot chat / megaphone broadcasts.** Gated like any other body (`Dispatcher`, `QueueCleaner`) and any *scheduled* broadcast is refused at scheduling time (`BotTiming.after`, `MethodScheduler.runAfterDelay`), so a quiesced server emits nothing.
- **Lock contention with the sweep.** Not observed: no shutdown step blocked behind a bot-held lock in either phase, and the drain plus `saveAll`'s existing retry covers it. `BotShutdown.resetForTest()` exists so a test JVM can reopen the gate; production never reopens it.
