# 06: A bot resumes exactly where it stopped

**What to build:** On login a roster bot is placed at its stored `MapId` + `PosX/PosY` regardless of any wave,
and its activity is restored from `Activity`/`ActivityState` (e.g. `TrainingBot` gets `homeMapId` and
`currentTrainMapId` from the row instead of "wherever I spawned", `TrainingBot.java:408`). Movement and FSM phase
still reconstruct (ticket 04 boundary of the old series). Logout (`BotGeneration.removeBotFromServer`) also
writes `Activity`, `ActivityState` and `LastLogoutAt`. If the stored map is invalid (event/instance map, gone),
the bot starts in `HomeMapId`.

**Blocked by:** 04.

**Status:** open

- [ ] A trainer saved on a field map logs back in on that map, grinding, not in town.
- [ ] A bot saved on an instance map falls back to its home town.
- [ ] `BotPopulationProof` phase 2 asserts map, position and activity survive a restart.
