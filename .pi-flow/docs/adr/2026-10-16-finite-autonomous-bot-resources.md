# Finite resources for autonomous progression bots

Date: 2026-10-16
Status: Accepted target contract; implementation not authorized. **Update (2026-10-17):** tickets 01-13 have since implemented part of this contract - the `BotHoldings` ledger, the ownership/hand-over rules, the casino/provenance boundaries (`decisions/06`) and the ledger/row durability set (ticket 13, commit `8d5fd93e420dc3f97a62930aa071d49adb7684a5`) are landed - while the offscreen capacity basis, allocation policy and calibration remain open. Read this document as the target contract plus the open boundaries below, not as "nothing landed".

## Context

The proposed read-only SoloMapling investigation evaluates greater autonomy through reuse of existing training, equipment, shopping, and social behaviors. A lightweight activity selector is a hypothesis to assess, not an approved architecture.

The existing implementation includes removal-only bot pickups and synthetic purchases that credit sellers without charging bots. These behaviors can support ambient population, but cannot represent finite-resource autonomous progression unchanged.

The user selected finite gameplay resources for bots that autonomously train, upgrade equipment, and shop.

## Decision

For those autonomous progression bots, inventory and mesos are finite gameplay resources. Bots must earn or explicitly receive what they spend. Picked-up items and mesos must be accounted for in their holdings; purchases must debit their funds and account for acquired goods. Equipment upgrades must consume their required resources.

Approximate offscreen training may produce tradable items and mesos, subject to shared capacity limits rather than independent full-rate production for every bot. The read-only investigation must compare map hunting-capacity budgets against regional economic quotas before choosing the capacity basis or allocation policy.

This is a target contract, not a claim that the current implementation satisfies it. It does not authorize product source changes.

## Consequences

- Resource acquisition, spending, and transfer must be traced before choosing activity-selection architecture.
- Existing removal-only pickups and uncharged bot purchases are incompatible with this target for autonomous progression bots.
- Abstract offscreen progression remains permitted by the offscreen-simulation ADR, but its resource inputs and rewards need explicit rules.
- Economic correctness and existing player-facing features must be considered alongside performance measurements.

## Alternatives not selected for these bots

- Synthetic population resources: generating money and supplies under explicit limits instead of requiring finite holdings.
- Splitting the selected autonomous progression population into finite and synthetic resource roles.

## Open boundaries

- Whether ambient merchants and scripted attractions are in scope for migration remains undecided. Evidence for this boundary now exists: see `../../scalable-bot-simulation/issues/10-value-path-audit.md`, findings 1-4 (casino payouts conjure real-value chips, conjured display items can reach durable bot holdings, a bot-owned `HiredMerchant`'s income is sunk because only players can withdraw it, and the event-instance reward APIs are bot-hostile although no caller registers bots today). ~~None of those is a leak through the accounting path; each needs a policy decision.~~ **Update (2026-10-17):** two of those boundaries are closed and one is settled, so only the event-instance note remains recorded. The **casino payout** and **conjured display item** boundaries are decided in `../../scalable-bot-simulation/decisions/06-casino-and-ambient-merchant-boundaries.md` and implemented by ticket 12 (`../../scalable-bot-simulation/issues/12-casino-pot-payout.md`, commit `1dfc85a4589d7451820fbc541302f16fef2d3390`): a payout comes from the collected pot/house stock, never a conjured copy, and a conjured drop carries per-instance provenance so bot loot removes it without crediting it. The **ambient-merchant** boundary is settled as **sink-by-design** by the same decision: a bot-owned `HiredMerchant`'s income is a deliberate sink, and the dead owner-bank write was removed (commit `9f7aca06`). The **event-instance reward APIs** remain recorded, not hardened (no caller registers a bot today).
- The capacity basis, allocation policy, and calibration remain undecided; compare map hunting capacity and regional economic quotas with evidence.
- Initial grants, persistence across restarts, inventory capacity, transaction failure, and resource exhaustion behavior remain unspecified.
- No utility selector, scheduler redesign, or live-host change has been approved.
