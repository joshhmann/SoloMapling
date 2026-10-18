# Ticket 04: What capacity basis should govern offscreen rewards?

Type: research
Status: resolved

## Question

Should offscreen tradable rewards use map hunting-capacity budgets, regional economic quotas, or another basis?

## Answer

Use map hunting-capacity budgets, not regional quotas. Observed kills roll per-map spawn/drop tables at world rates (`BotAttackEffects:117-216`; `World:359-413`); capacity already exists per map instance via spawn points, `mobCapacity`/`spawnedMonstersOnMap`, occupancy-scaled respawn, and `ITEM_LIMIT_ON_MAP=200`. No map/regional/global reward-budget or drop-quota mechanism exists; the only quota-like system gates expedition entries, and merchant controls are pricing/fees only. Offscreen progression currently yields EXP only (`TrainingBot:707-735,1171-1198`), so tradable offscreen rewards have no generation path yet. Derive them from per-map-instance spawn budget × effective drop expectation at world rates, clamped by item limits and respawn behavior. Full findings: `agent://RewardCapacity`.
