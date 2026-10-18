Status: draft

## Problem statement

SoloMapling bots look alive but do not hold finite resources: kills create world drops that bots remove without credit, purchases and trades move value only on the counterparty side, and headless bots are skipped by character persistence. Offscreen activity is EXP-only with no tradable generation path. Scaling up population without defining durable state, a reward capacity basis, and the measured bottleneck risks inconsistent promotion and unbounded economic output.

## Solution

Define and verify the simulation contract for persistent finite-resource bots with approximate offscreen activity: exact durability for identity, progression, position, and player-facing commitments; real accounting for bot inventory and mesos; offscreen tradable rewards derived from map hunting-capacity budgets; and workload measurements separating sparse-observer, crowded-interactive, and transition costs before any scheduler or concurrency decision.

## User stories

1. As a server operator, I want bots to keep identity, level, position, and commitments across observed/offscreen transitions, so that promotion never silently loses player-visible state.
2. As an economy steward, I want bot pickups credited and bot purchases debited against finite holdings, so that autonomous progression cannot print value.
3. As an economy steward, I want offscreen tradable rewards bounded by per-map-instance hunting capacity at world rates, so that background bots share the same budget as observed hunting.
4. As a server operator, I want sparse-observer, crowded-interactive, and transition costs measured separately, so that scaling work targets the real bottleneck.
5. As a player, I want seller stock, sold ledgers, credited mesos, party, trade, and recruit commitments preserved exactly, so that interacting with bots behaves like interacting with the shared world.

## Implementation decisions

- Durability boundary: identity and progression inputs (bot id/name/level/exp, train target, mob level, accrual timestamps), map/position as the reconciliation input, and player-facing commitments (party, in-progress trade, seller stock/sold/credited mesos, pending recruit state, lifecycle ownership) persist exactly; fine movement trails, coarse plans, combat presentation, contact damage, chatter/buff pacing, watchdog state, and tick debounce reconstruct on promotion.
- Resource accounting: bot pickup posts to holdings through the shared credit path or an explicit budgeted equivalent; bot purchases debit mesos and grant goods with defined exhaustion/failure behavior; despawn and restart preserve or explicitly settle holdings rather than dropping them.
- Offscreen rewards: per-map-instance spawn budget times effective drop expectation at current world rates, clamped by item limits and occupancy-scaled respawn behavior; world rates and drop-table membership are inputs, not budgets; no regional quota layer unless later built on per-map budgets plus market telemetry.
- Promotion is a correctness boundary with explicit reconciliation rules, not a cadence change; the rules are settled and enforced — cross-restart store (issue 06), quest durability, level-up fidelity, map-slot claims, trade-vs-travel and recruit atomicity (issues 06/07, the five rules stated in issue 07).
- Shutdown is ordered: bot-owned activity (the macro wheel, the shared combat ticker, scanners and per-bot movement tasks) is quiesced and drained before the bot settlement sweep and before world/channel disposal, so the sweep records a frozen population and no bot task runs against disposed maps (issue 09).
- Baseline for design: locally inspected SoloMapling baseline; okzkx fork fixes are later cherry-pick candidates, not design ground.

## Testing decisions

- Highest seams: existing unit tests (`BotTickServiceTest`, `BotSpotClaimsTest`, `CoarseExecutorTest`, `GCMoveBakeTest`) plus live diagnostics (`!env perf`, `!gcmove lod stats`) read at two points seconds apart for sparse vs crowded vs transition comparisons.
- End-to-end proof: `src/test/java/soloMapling/ArtificialPlayer/BotPopulationProof.java` (issue 08) is a test-scope `main`, not a unit test. It boots a real server in-process against MySQL and the WZ archive, then drives the shipped economy through production calls only — `DropCommands.botLootSingleDrop`, `PlayerShop.botBuy`, the two-sided `BotTradeCommands`/`Trade` sequence, `Party.createParty`/`World.updateParty`, `OffscreenRewardBudget.draw` and `BotPerformanceMonitor.report` — and a second phase boots again to compare the recorded bot's identity, progression, meso and settled commitments. Two phases, ~5 minutes, not wired into CI.
- Verify: pickup credits holdings; purchases debit and grant; despawn/restart preserves or settles; offscreen rewards stay within map budget; promotion reconciles position/commitments; sparse cost stays in background wakeups while crowded cost is attributed to physics/broadcast/combat/transition separately.
- These seams defend external behavior (holdings, economy, transitions, scaling costs) rather than internal tick shapes; no new production seam was added for the proof either — it drives existing entry points — so the "confirm before adding one" rule stands.
- Prior art: read-only probes exercised the tick scheduler, log reader, YAML parsing, and encoding behavior; a full game/database/WZ run and workload attribution were delivered by issue 08 (`BotPopulationProof`), which reports sparse, crowded and post-demotion cost windows from a live server. A load benchmark (sustained load, many players) is still unperformed.

## Out of scope

- Product source changes, live-host work, full deployment, load-benchmark execution, whole-fork adoption, scheduler/concurrency redesign, implementation tickets, and ambient merchant/attraction migration (undecided boundary).

## Further notes

- Standing constraints: persistent identity with approximate offscreen activity; finite inventory/mesos for autonomous progression bots; shared capacity limits on offscreen tradable rewards; measure sparse and crowded workloads before redesign.
- Risks: within-active cost ranking unknown without measurement; `BOTS_PER_MAP` keyed by map id may conflate channel instances; fork documents contain disproved scheduler advice. ~~party-split passive credit, OPQ floor-spawn crediting, minigame flows, and pet-loot interaction untraced~~ closed by [issue 10](../scalable-bot-simulation/issues/10-value-path-audit.md): party split moves only EXP, OPQ real drops and pet loot are unreachable-or-accounted, and the one reachable leak (a bot-owned `PlayerShop`'s proceeds) is ledgered. Issue 10 also records four policy findings with magnitudes - casino payouts conjure real-value chips, conjured display items can reach durable holdings, bot-owned `HiredMerchant` income is sunk, and the event-instance reward APIs are bot-hostile - all of which need an owner decision rather than a leak repair.
- Evidence: [map](../scalable-bot-simulation/map.md), tickets [02](../scalable-bot-simulation/decisions/02-resource-flow.md), [03](../scalable-bot-simulation/decisions/03-offscreen-durability.md), [04](../scalable-bot-simulation/decisions/04-reward-capacity.md), [05](../scalable-bot-simulation/decisions/05-workload-cost.md); ADRs [offscreen-simulation-contract](../docs/adr/2026-10-16-offscreen-simulation-contract.md), [finite-resources](../docs/adr/2026-10-16-finite-autonomous-bot-resources.md). No `ready-for-agent` label: no triage record exists.
