# 02: Offscreen reward budget from per-map capacity

**What to build:** Offscreen bots produce tradable items and mesos only within a per-map-instance hunting-capacity budget derived from that map's spawn table and effective drop expectation at current world rates, clamped by item limits and occupancy-scaled respawn behavior.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] A bot accruing offscreen rewards cannot exceed the map instance's expected yield over the elapsed window.
- [x] World rates and drop-table membership feed the budget as inputs rather than being replaced by a second rate source.
- [x] Output respects the per-map item limit and respawn/occupancy behavior already present.
- [x] No independent per-bot full-rate production path is introduced.

## Comments

Implemented in `/root/SoloMapling` (uncommitted at review time).

- New `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenRewardBudget.java`: one token meter per map
  id, accruing at that map's expected yield and capped at one window; a single draw can never take more than the
  window the bot just lived through, and every bot on the map draws from the same meter.
- Yield inputs are the existing sources: `MapMobIndex` spawn slots (now carrying each slot's respawn time from WZ
  `mobTime`), `MapleMap.getCurrentSpawnRate(occupants)` (made public so no second occupancy rule is minted),
  `YamlConfig` `RESPAWN_INTERVAL` / `ITEM_LIMIT_ON_MAP`, and `MonsterInformationProvider.retrieveEffectiveDrop`
  with `World.getDropRate()` / `getMesoRate()`. Quest drops are skipped; per-item allowances are clamped by the map
  item limit.
- Wire-up: `TrainingBot`'s offscreen accrual (GRIND phase, unobserved map) now draws the reward and grants it through
  `BotHoldings`, alongside the EXP it already accrued.
- Deliberate, documented bounds: boss maps are estimated below their real yield (boss maps are not bot grind
  targets), and channels of the same map id share one meter, so output under-produces rather than exceeding a map's
  yield.

Evidence (sandbox with real WZ data, real `drop_data` tables and a MySQL 8.4 copy of the schema): map `100000002`
(17 slots, mob level 8) yields 389 mesos per 60 s window at 1 occupant, 441 at 3, 519 at 6, 3893 at drop rate 2 /
meso rate 5; three bots × 60 s totalled 441 — exactly one window — with 39 items ≤ Σ ceil(per-item allowance). The
real `TrainingBot.accrueAbstractLoot` path credited bot 1 with 389 mesos and `{4000012=16, 2061000=4}`, bot 2 with
0 mesos and `{2061000=13}`. Unit coverage: `OffscreenRewardBudgetTest` 9 tests. Review record:
`.pi-flow/reviews/2026-10-16/review-scalable-bot-frontier/`.
