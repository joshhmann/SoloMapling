# 06: A bot resumes exactly where it stopped

**What to build:** On login a roster bot is placed at its stored `MapId` + `PosX/PosY` regardless of any wave,
and its activity is restored from `Activity`/`ActivityState` (e.g. `TrainingBot` gets `homeMapId` and
`currentTrainMapId` from the row instead of "wherever I spawned", `TrainingBot.java:408`). Movement and FSM phase
still reconstruct (ticket 04 boundary of the old series). Logout (`BotGeneration.removeBotFromServer`) also
writes `Activity`, `ActivityState` and `LastLogoutAt`. If the stored map is invalid (event/instance map, gone),
the bot starts in `HomeMapId`.

**Blocked by:** 04.

**Status:** done (2026-10-18) — the grind-in-place resume is unit-built but not yet observed on a live bot

- [x] A trainer saved on a field map logs back in on that map, grinding, not in town. (placement proven live; the GRIND re-entry not yet observed, see Verification)
- [x] A bot saved on an instance map falls back to its home town. (as forced-return maps, like a player; unit-tested)
- [x] `BotPopulationProof` phase 2 asserts map, position and activity survive a restart.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotResume.java` | where a member logs in: its stored map and exact point; a map with a forced return (dungeon, boss room, event map) sends it to that map's spawn like a player; a map that no longer loads keeps the wave's placement |
| `soloMapling/ArtificialPlayer/BotGeneration.java` | `createBot` places an adopted roster member by `BotResume` instead of at the wave's point, stamps its login and arms its last activity; `removeBotFromServer` records the session with a logout time |
| `soloMapling/ArtificialPlayer/BotRoster.java` | `Activity` (name, home map, small JSON state), `recordLogin` (arms a one-shot resume; a member with no recorded activity resumes with the wave's town as home and "training where it stands"), `takeResume`, `recordSessions` (one batch upsert of activity, home and - on logout - the logout time), `loginPlace` |
| `soloMapling/ArtificialPlayer/BotSM.java` | `activitySnapshot()` default: the bot type's name |
| `soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java` | snapshot = `grind`, home, train map; `doInit` resumes: home comes back, and a bot back on its train map goes straight to GRIND there (DECIDE's search excludes the map it stands on, so without this every resumed grinder would leave) |
| `net/server/task/CharacterAutosaverTask.java`, `net/server/Server.java` | record sessions next to the hourly bot save and after the shutdown sweep (logout) |
| `soloMapling/ArtificialPlayer/BotStoreReport.java` | `!roster status <bot>` shows where it logged in this run |
| `src/test/.../BotResumeTest.java` | 5 tests: exact point, forced return, missing maps, activity column round trip, login arms once + fallback |
| `src/test/.../BotPopulationProof.java` | verify phase: the tracked (wave 8) bot is a member, logged in where its row says it logged out, the row holds its logout activity, and a grinder that logged out on its train map kept grinding there |

Waves 8/9 still choose the bot type at boot; ticket 07 makes the activity a per-session choice.

## Verification

- Unit: `BotResumeTest` 5/5. Full suite (2026-10-18): `Tests run: 2110, Failures: 1, Errors: 4` - only the five
  known WZ-missing failures.
- Two-boot proof in the real-WZ sandbox with tickets 05 and 06: run phase 0 errors; verify `PROOF-VERIFY PASS`,
  14/14 - the tracked bot `Jun1567` (a ticket-05 rename that survived the restart) logged in at
  `211040300 (-34, -1386)`, exactly the map and point its row recorded at logout, with `grind`, home 211040300
  (its wave's deep hub) recorded.
- Store after both boots: all 2508 members have `LastLoginAt`, `LastLogoutAt` and an activity (2390 `grind`,
  80 `social`, 38 `townwanderer`); 51 grinders logged out holding a train map and all 51 stood on it.
- Gap: the tracked bot logged out with no train map (most grinders are still in their first town dwell two minutes
  after boot), so "a grinder that logged out on its train map kept grinding there" did not apply in this run.
  `TrainingBot.resumeGrind` is therefore not yet seen working on a live bot; a longer run or a chosen grinder in
  the harness would show it.
- Sandbox note: copying `net/server/Server.java` into the sandbox overwrote its login-port tweak; re-applied
  (`initLoginServer(18484)`) before the run. Copy that file by hand or re-apply the sed after.
- Later run (ticket 08's proof): the tracked bot logged out on `105090400`, a map with a forced return, and logged in
  at the return map `105090200`'s spawn - the forced-return path live. The grind-in-place check still did not apply
  (that bot was not on its train map); the harness now waits for the FSM's init before judging it.
