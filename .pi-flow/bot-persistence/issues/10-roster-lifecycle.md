# 10: Roster lifecycle — new characters, veterans, dormancy, returns

**What to build:** Keep the roster around `rosterMultiple × peak` (default 3x, ~10,000 for a ~3,300 peak). A small daily rate of new
characters, mostly level 1 at the beginner start; when the roster is empty or far below size, seed "veterans"
across a level distribution with matching job and gear (`BotDecorate`). A bot that has not logged in for a long
stretch may become DORMANT (row kept); a dormant bot has a small daily chance to return. Rates live in config.

**Blocked by:** 08.

**Status:** done (2026-10-18) — simulated; new characters not yet watched being made on a live server

- [x] First boot on an empty roster seeds a level spread, not all level 1.
- [x] Simulated weeks keep the roster near its target size, with some dormancy and occasional returns.
- [x] A returning bot comes back with its old name, level, items and memories.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotRosterLifecycle.java` | `plan` (pure, per span of hours): offline active players quit with `BOT_ROSTER_QUIT_PER_DAY`, dormant ones return with `BOT_ROSTER_RETURN_PER_DAY`, new level 1-3 beginners arrive at `BOT_ROSTER_NEW_PER_DAY` in Henesys/Kerning/Perion/Ellinia/Lith, and while the active roster is under `BOT_ROSTER_VETERAN_FILL_BELOW` of its size veterans arrive at `BOT_ROSTER_VETERANS_PER_DAY`, drawn from wave 8's cohorts by size; creation stops at `peak x BOT_ROSTER_SIZE_MULTIPLE` (7200); `maybeStep` runs it hourly from the director; `create` = a new slot above the plan, `createBot` (decorated in its band, unique name), enrol, login, start as a grinder |
| `soloMapling/Environment/EnvironmentManager.java` | wave 8's cohorts become `TRAINING_COHORTS` (same task order, so every stored slot is unchanged); the wave builds its tasks from it |
| `config/ServerConfig.java`, `config.yaml` | `BOT_ROSTER_SIZE_MULTIPLE`, `BOT_ROSTER_NEW_PER_DAY`, `BOT_ROSTER_VETERAN_FILL_BELOW`, `BOT_ROSTER_VETERANS_PER_DAY`, `BOT_ROSTER_QUIT_PER_DAY`, `BOT_ROSTER_RETURN_PER_DAY` |
| `src/test/.../BotRosterLifecycleTest.java` | 4 tests: from 2508 active, >900 veterans in week one and half size within a week; after a year the active roster sits within 150 of 7200 with >300 dormant, >100 returns, >1000 beginners; nobody quits while online and a full roster makes no one; a return changes only the status; fractional daily rates average out |

First boot on an empty roster: unchanged path - waves 8 and 9 seed it (levels 1-95 across the cohorts), and every
bot they mint is enrolled (ticket 08); the director takes over on the next boot.
