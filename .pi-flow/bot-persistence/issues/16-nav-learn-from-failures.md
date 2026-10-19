# 16: Movement learns from its own failures

**What to build:** Persist per-edge outcomes `(mapId, GRAPH_VERSION, from foothold, to foothold, edge type) ->
attempts, successes, stuck, avg duration` under `cache/bot-nav/learned/`, recorded from the existing signals:
stale-edge give-up and replan (`BotNavigationManager`), `tickUnstuck` (`BotMovementManager.java:818`) and
GCTravel warp reasons (`GCTravel.java:246/306/332`). The planner multiplies an edge's cost by its failure rate;
an edge past a threshold is disabled for a decay window. Repeated stuck spots mark nearby footholds as poor
loiter/camp spots.

**Blocked by:** 03.

**Status:** done (2026-10-18) — learning only happens on maps a player is on; not yet seen live

- [x] A deliberately bad edge (test fixture) is penalised then avoided after N failures, and retried after decay.
- [x] Learned stats survive restart and are dropped when `GRAPH_VERSION` changes.
- [ ] Stuck/warp counts on a real map go down over a run (recorded under `## Verification`).

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/GCMoveSystem/NavEdgeLearning.java` | per graph (map, speed, jump, snowshoes, version): failures and successes per edge (regions, type, launch window, start point), both fading with a 12 h half-life; the planner's factor is 1 + 2 x fail-rate x confidence (confidence reaches 1 at 4 observations), 8x once an edge has >=5 failures at >=80%; never forbidden; portals are not judged; saved to `cache/bot-nav/learned/<graph>.json` every 5 min and at shutdown, dropped on a new `GRAPH_VERSION`; identity caches keep the planner's per-relaxation lookup free of string building (and an immediate 1.0 for graphs with nothing learned) |
| `GCMoveSystem/BotNavigationManager.java` | a failure when the stale-edge give-up drops a committed edge; a success once per committed edge when the bot reaches its target region; the A* step costs `NavEdgeLearning.scale(...)` |
| `GCMoveSystem/BotMovementManager.java` | a failure when `tickUnstuck` fires on a committed edge |
| `GCMoveSystem/BotMovementState.java` | `navLearnedEdge` (counted once) |
| `client/command/commands/gm4/GCMoveCommand.java` | `!gcmove learned` |
| `src/test/.../NavEdgeLearningTest.java` | 4: the factor curve; failures fade; **the real `runSearch` takes the direct jump first and, after six misses on it, the two-jump detour**; learned data survives a restart and is dropped for a new graph version |

GCTravel's inter-map warps (`GCTravel.java:246/306/332`) are not counted: they are a map-to-map signal, not an edge.

## Verification

- `NavEdgeLearningTest` 4/4; full suite (2026-10-18): `Tests run: 2165, Failures: 1, Errors: 4` - the five known
  WZ-missing failures only.
- Live: the ticket-11 scale run (4,100 bots, learning code included) recorded nothing, as expected - with no player
  on any map every bot moves by the offscreen coarse planner, which never executes a physical jump or drop and so
  never misses one. Learning happens only where moves are simulated (observed maps). Criterion 3 (stuck/warp counts
  fall over a run) therefore still needs a player-observed session; `!gcmove learned` shows what has been learned.
