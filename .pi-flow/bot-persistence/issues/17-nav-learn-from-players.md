# 17: Movement learns from real players

**What to build:** Always-on lightweight capture in `MovePlayerHandler` for real players only (separate from
the single-user GM `!move` recorder in `InPacketReader`): reduce move packets to foothold-to-foothold
transitions (take-off foothold, landing foothold, duration, input fragment). A transition the graph lacks, or
one players do much faster than its graph cost, becomes a candidate; it is validated by re-running the physics
sim and joins the graph as an `OBSERVED` edge (or a replayable fragment when the sim can't reproduce it).

**Blocked by:** 16.

**Status:** open

- [ ] Capture costs nothing measurable on the packet thread (work is queued off it).
- [ ] A jump the bake missed, done by a player a few times, becomes an edge bots then use.
- [ ] Invalid or impossible transitions (lag, knockback) are rejected.
