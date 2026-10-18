# 11: Prove ~3,300 and ~4,100 online with the director

**What to build:** Extend `BotPopulationProof` (or a sibling harness) to drive the director at today's ~3,300 peak and 1.25x that, and record `BotPerformanceMonitor` costs split into sparse, crowded, transition and the new
login/logout cost. Tune LOD and login spreading until the target holds; only then raise the default peak.

**Blocked by:** 08.

**Status:** open

- [ ] Numbers for ~3,300 and ~4,100 online recorded under `## Verification`, with host specs.
- [ ] Login/logout cost measured separately from promotion/demotion cost.
- [ ] The default peak in config is set from the measurement, not guessed.
