# 11: Prove 500 and 750 online

**What to build:** Extend `BotPopulationProof` (or a sibling harness) to drive the director at 500 and 750
online, and record `BotPerformanceMonitor` costs split into sparse, crowded, transition and the new
login/logout cost. Tune LOD and login spreading until the target holds; only then raise the default peak.

**Blocked by:** 08.

**Status:** open

- [ ] Numbers for 500 and 750 online recorded under `## Verification`, with host specs.
- [ ] Login/logout cost measured separately from promotion/demotion cost.
- [ ] The default peak in config is set from the measurement, not guessed.
