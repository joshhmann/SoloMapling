# Ticket 05: What dominates sparse versus crowded simulation cost?

Type: research
Status: resolved

## Question

What dominates simulation cost in sparse-observer versus crowded-interactive workloads: active simulation, background accrual, or transitions?

## Answer

Sparse-observer cost is background O(N) wakeups: 100 ms wheel scan (`BotTickService`), 4 Hz shared combat sweep with observed-path early return (`TrainingBot:65-86,168-220`), 250 ms/1 s movement heartbeats (`GCMovementDriver:1-123`), 1 s observer poll (`ObserverTracker`), and 60–120 s GRIND macro accrual (`BotSM:294-403`; `TrainingBot:732-735,1171-1203`). Crowded-interactive cost is active simulation: 50 ms physics stepping, per-change movement broadcasts fanning to every real client (`BotMovementManager:860-938`; `MapleMap:2779-2873`), combat scans/damage/loot, plus entry nudge storms and coarse-to-physics reconstruction (`BotMapEntryResponder`; `GCMovementDriver:451-454`). Within-active ranking and absolute millisecond splits require `!env perf` and `!gcmove lod stats` measurement; not decided from code alone. Full findings: `agent://WorkloadCost`.

