# 17: Movement learns from real players

**What to build:** Always-on lightweight capture in `MovePlayerHandler` for real players only (separate from
the single-user GM `!move` recorder in `InPacketReader`): reduce move packets to foothold-to-foothold
transitions (take-off foothold, landing foothold, duration, input fragment). A transition the graph lacks, or
one players do much faster than its graph cost, becomes a candidate; it is validated by re-running the physics
sim and joins the graph as an `OBSERVED` edge (or a replayable fragment when the sim can't reproduce it).

**Blocked by:** 16.

**Status:** done (2026-10-18) — jumps only; not yet seen with a real player moving

- [x] Capture costs nothing measurable on the packet thread (work is queued off it).
- [x] A jump the bake missed, done by a player a few times, becomes an edge bots then use.
- [x] Invalid or impossible transitions (lag, knockback) are rejected.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/GCMoveSystem/NavObservedEdges.java` | capture: per real player, ground on foothold A -> airborne (fh 0) -> ground on foothold B is a jump (walking onto a neighbour or landing back on A is not); the packet thread only updates a small per-player record and queues a jump. Learning, off the packet thread: the base-profile graph of the map; skip when the regions are already joined; count sightings per (map, regions, direction, 20 px takeoff spot); at 2, re-simulate with `BotPhysicsEngine.simulateJumpLanding` from the player's takeoff (directional, then standing) and adopt only if it lands in the player's region, as an ordinary JUMP edge (1-px launch window at the takeoff x, cost = simulated air time). Adopted edges live in a per-map overlay (never written into the cached graph), apply to graphs of at least base speed/jump, are saved under `cache/bot-nav/observed/<map>.json` and dropped on a new graph version |
| `net/server/channel/handlers/AbstractMovementPacketHandler.java` | each absolute movement element of a real player (not a bot) goes to `onPlayerMove` |
| `GCMoveSystem/BotNavigationManager.java` | the A* expansion reads `NavObservedEdges.outgoing` (the graph's own list, with no allocation, when nothing was learned on the map) |
| `client/command/commands/gm4/GCMoveCommand.java` | `!gcmove learned` also reports learned jumps |
| `src/test/.../NavObservedEdgesTest.java` | the capture state machine; and, with the real `wz/` (skipped without it): Henesys' baked graph minus one of its own directional jumps that is the only link between two regions - a player making that jump once is not enough, twice gets it proven by the bots' physics and seen by the planner; a "jump" to a region 1,500 px away (lag, knockback) is never adopted |

Scope: jumps only (the case the sampled builder misses); drops/ropes a player discovers are not learned. The
per-player capture record is not cleared on logout (a handful of real players on a solo server).

## Verification

- Repo suite (2026-10-18): `Tests run: 2167, Failures: 1, Errors: 4, Skipped: 1` - the five known WZ-missing failures, and the new physics test skipped (no `wz/`).
- Real-WZ sandbox: `NavObservedEdgesTest` 2/2, 0 skipped.
- Not yet: a real player's jump picked up live.
