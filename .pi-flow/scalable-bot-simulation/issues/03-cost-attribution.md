# 03: Sparse, crowded, and transition cost attribution

**What to build:** Operator-readable cost attribution that separates sparse-observer background cost, crowded-interactive active cost, and transition cost, so a scaling decision targets the measured dominant term.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Diagnostics report active-simulation, background, and transition cost separately for a running population.
- [x] Readings taken seconds apart distinguish sparse and crowded workloads.
- [x] Within-active breakdown attributes cost across physics stepping, movement broadcast, combat scan/damage/loot, and promotion burst.
- [x] The report is reachable from the existing diagnostics surface without a new host or deployment.

## Comments

Implemented in `/root/SoloMapling` (uncommitted at review time).

- `GCMoveSystem/BotPerformanceMonitor` became a real windowed aggregator over the existing phase hooks (the stub's
  `record`/`recordPathfind` signatures kept): per-phase nanoseconds/calls/units with the previous window's baselines
  advanced inside `report()`, so two readings taken seconds apart rate only the window between them.
- New hooks for the work that had none: physics stepping (`BotPhysicsEngine`), movement-packet fan-out and raw
  sends with the recipient count (`MapleMap.broadcastMessage` now returns it, `BotMovementManager`), combat
  scan/damage/loot, the sparse background phases at their existing `BotPerfStats` increment sites (macro tick,
  movement tick, idle broadcast, nudge, observer poll, offscreen accrual), and the transition events (promotion
  reconstruct, coarse demotion, tier flips, entry nudge storms).
- Buckets: ACTIVE = `move-*`, physics step, broadcast (with fan-out units), combat scan/damage/loot; BACKGROUND =
  macro ticks, movement ticks, observer poll (carrying nav-resolve, pathfind, idle broadcasts, nudges, offscreen
  accrual as nested splits); TRANSITION = promotion/demotion/tier flips/entry nudges. Nested phases are listed but
  excluded from their bucket total; broadcast is deliberately *not* nested, because fan-out also happens with no
  `move-*` phase open — the resulting bounded overlap is documented in the class instead of hiding that work.
- Surface: `!env perf` (`EnvironmentCommand.printPerfReport()`) appends the report lines after its existing
  thread/heap/tick readout. No new command, host or deployment.

Evidence from the real `!env perf` readout (sandbox, two windows ~1.1 s apart): sparse window → ACTIVE 0.00,
BACKGROUND 35.74 ms/s (observer-poll 27.08, movement-tick 5.42, macro-tick 3.25, offscreen-accrual nested),
TRANSITION 0.00; crowded window → ACTIVE 60.74 ms/s (combat-damage 32.35, move-ground 10.78, combat-loot 7.91,
combat-scan 6.47, physics-step 4.31 nested, broadcast-move 3.23, broadcast-fanout units 240), BACKGROUND 0.00,
TRANSITION 1.35 ms/s (promotion-reconstruct). Unit coverage: `BotPerformanceMonitorTest` 2 tests.

Deviation, deliberate: criterion 3 names the promotion burst inside the *within-active* breakdown while criterion 1
requires transition cost reported *separately*. The burst stays in TRANSITION — all five named cost sources are
attributed in the same readout (four in the ACTIVE split, the burst as the TRANSITION total with its own
`promotion-reconstruct` line), and the bucket separation criterion 1 asks for is preserved. Review record:
`.pi-flow/reviews/2026-10-16/review-scalable-bot-frontier/` (`synthesis.md`, disposition S7).
