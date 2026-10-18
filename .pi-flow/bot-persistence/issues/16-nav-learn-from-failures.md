# 16: Movement learns from its own failures

**What to build:** Persist per-edge outcomes `(mapId, GRAPH_VERSION, from foothold, to foothold, edge type) ->
attempts, successes, stuck, avg duration` under `cache/bot-nav/learned/`, recorded from the existing signals:
stale-edge give-up and replan (`BotNavigationManager`), `tickUnstuck` (`BotMovementManager.java:818`) and
GCTravel warp reasons (`GCTravel.java:246/306/332`). The planner multiplies an edge's cost by its failure rate;
an edge past a threshold is disabled for a decay window. Repeated stuck spots mark nearby footholds as poor
loiter/camp spots.

**Blocked by:** 03.

**Status:** open

- [ ] A deliberately bad edge (test fixture) is penalised then avoided after N failures, and retried after decay.
- [ ] Learned stats survive restart and are dropped when `GRAPH_VERSION` changes.
- [ ] Stuck/warp counts on a real map go down over a run (recorded under `## Verification`).
