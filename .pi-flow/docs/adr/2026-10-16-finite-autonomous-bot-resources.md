# Finite resources for autonomous progression bots

Date: 2026-10-16
Status: Accepted target contract; implementation not authorized

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

- Whether ambient merchants and scripted attractions are in scope for migration remains undecided. Evidence for this boundary now exists: see `../../scalable-bot-simulation/issues/10-value-path-audit.md`, findings 1-4 (casino payouts conjure real-value chips, conjured display items can reach durable bot holdings, a bot-owned `HiredMerchant`'s income is sunk because only players can withdraw it, and the event-instance reward APIs are bot-hostile although no caller registers bots today). None of those is a leak through the accounting path; each needs a policy decision.
- The capacity basis, allocation policy, and calibration remain undecided; compare map hunting capacity and regional economic quotas with evidence.
- Initial grants, persistence across restarts, inventory capacity, transaction failure, and resource exhaustion behavior remain unspecified.
- No utility selector, scheduler redesign, or live-host change has been approved.
