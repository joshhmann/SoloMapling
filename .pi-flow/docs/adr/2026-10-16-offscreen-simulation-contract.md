# Persistent bots with approximate offscreen activity

Date: 2026-10-16
Status: Accepted direction; detailed invariants and implementation undecided

## Context

The improvement goal for SoloMapling is a scalable simulation framework. The inspected implementation mixes observed physics/combat with coarse offscreen movement and abstract EXP accrual. Increasing population without defining this boundary risks inconsistent transitions between background and interactive behavior.

The user chose persistent state with approximate offscreen activity, rather than full gameplay simulation everywhere or disposable ambient characters. The user also chose to measure both sparse-observer and crowded-interactive workloads before choosing the primary scaling bottleneck.

## Decision

Bots retain persistent identity and durable gameplay state while unobserved. Their offscreen activity may be summarized under explicit rules rather than simulated action by action. Position and action state must be reconciled before a bot becomes interactable again.

This decision does not yet specify which state is durable, how approximate progress is calculated, or what qualifies a bot for background simulation. Those invariants must be defined before implementation.

Benchmark sparse-observer and crowded-interactive workloads before selecting a scheduler, concurrency model, or primary optimization. No capacity or latency claim has been established.

## Consequences

- Promotion into interactive simulation is a correctness boundary, not merely a cadence change.
- Offscreen summaries need explicit inputs, outputs, and reconciliation rules.
- Persistent state rules must distinguish internal simulation from effects on players and shared world objects.
- Benchmarks must separate active simulation cost, background cost, and transition cost.
- This direction permits approximation, not silent loss of player commitments or arbitrary mutation of unspecified durable state.

## Alternatives not selected

- Full gameplay fidelity while unobserved: stronger continuity, but less opportunity to reduce simulation cost.
- Disposable ambient population: cheaper suspension/regeneration, but does not preserve the selected persistent-bot model.

## Open questions

- Which fields and player-facing commitments must persist exactly?
- What observer/interaction conditions permit background simulation?
- What are the intended host, population, observer distribution, and latency targets?
- What should happen when active interactive demand exceeds capacity?
- Which measured subsystem dominates each workload?
