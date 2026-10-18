# Ticket 03: Which offscreen fields require exact durability?

Type: research
Status: resolved

## Question

Which bot fields and player-facing commitments require exact durability when a bot moves between observed and approximate offscreen states?

## Answer

Must persist exactly: identity/progression inputs (`Character` id/name/level/exp, `TrainingBot` train target/mob level/accrual timestamps), map/position as the reconciliation input, and player-facing commitments (party, in-progress trade, seller stock/`sold`/credited mesos, pending recruit state, lifecycle ownership). Fine movement trails, coarse plans, combat presentation, contact damage, chatter/buff pacing, watchdog/repair state, and tick debounce are reconstructible on promotion. Needs decision: bot holdings crediting, purchase debit/exhaustion, offscreen tradable production basis, quest durability, cross-restart persistence, level-up fidelity, map-slot claims, trade-vs-travel rule, and recruit atomicity. Full findings: `agent://OffscreenDurability`.
