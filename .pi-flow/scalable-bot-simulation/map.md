# Wayfinder map: scalable finite-resource bot simulation

## Destination

A decision-ready investigation route for persistent finite-resource bots with approximate offscreen activity, ending in a spec handoff covering durable fields, offscreen/economic capacity basis, and the measured bottleneck.

## Notes

- Baseline: locally inspected SoloMapling baseline from the earlier dissection, not the okzkx fork. Fork fixes are later cherry-pick candidates, not investigation ground.
- Read-only investigation: no source changes, live-host work, deployment, load benchmark, or wholesale fork adoption.
- Standing decisions: [persistent bots with approximate offscreen activity](../docs/adr/2026-10-16-offscreen-simulation-contract.md); [finite resources for autonomous progression bots](../docs/adr/2026-10-16-finite-autonomous-bot-resources.md).
- Consult `skill://research` for read-only tickets and `skill://grilling` for decision tickets. Skills consulted so far: explore, research, grilling, domain-modeling.
- Handoff goes to `/pi-to-spec`, then `/pi-to-tickets`; no implementation planning in this map.

## Decisions so far

- [Inspected baseline, not okzkx fork](decisions/01-investigation-baseline.md): use the locally inspected SoloMapling baseline as investigation ground.
- [Bot resource flow](decisions/02-resource-flow.md): bot holdings lack real entry/exit accounting and durable persistence; counterparty/owner-side writes are the only durable effects.
- [Sparse vs crowded cost](decisions/05-workload-cost.md): sparse cost is background O(N) wakeups, crowded cost is active simulation plus transitions; within-active ranking needs measurement.
- [Offscreen durability boundary](decisions/03-offscreen-durability.md): identity/progression/commitments persist exactly; movement/combat presentation reconstructs; holdings/restart/trade-travel rules need decisions.
- [Offscreen reward capacity](decisions/04-reward-capacity.md): map hunting-capacity budgets are the basis; no reward-quota mechanism exists and regional quotas would be new construction.

## Not yet specified

- Durable-field boundary: which bot state and commitments must persist exactly versus what may be approximated offscreen.
- Offscreen/economic accounting basis: map hunting capacity versus regional quota calibration remains an open comparison.
- Bottleneck measurement: sparse-observer versus crowded-interactive workloads are not yet measured, so no scheduler/concurrency decision is available.
- Ambient population boundary: merchant/attraction roles may remain synthetic while progression bots stay finite.

## Out of scope

- Product source changes, live-host access, full client/DB/WZ deployment, benchmark execution, whole-fork merge, or implementation tickets.
