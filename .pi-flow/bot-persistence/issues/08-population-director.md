# 08: Population director — bots log in and out on a daily curve

**What to build:** A scheduler that replaces waves 8 and 9 for population bots. Every ~30-60 s it computes
`target(now) = peak × curve(hour, weekday)` from `config.yaml` (peak, curve points, timezone) and logs bots in
or out to approach it: logins prefer offline ACTIVE bots whose play window covers now, spread across the
interval so they trickle in; logouts pick bots whose session is spent or who are outside their window while
the world is above target. Staff waves 1-7 are unchanged. GM: `!roster curve` (preview today's target),
`!roster login|logout <name>`, a flat-target test mode.

**Blocked by:** 07.

**Status:** done (2026-10-18)

- [x] With a fake clock, online count tracks the curve within a tolerance over a simulated day.
- [x] The same bots recur at similar hours across simulated days ("regulars").
- [x] Logins/logouts are spread, never a burst of hundreds in one tick.
- [x] `SPAWN_BOTS_ON_STARTUP` still boots staff; population comes from the director.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotPopulationCurve.java` | the daily curve: `hour:fraction` points, linear between them and across midnight, weekend lift capped at peak, `flat` test mode |
| `soloMapling/ArtificialPlayer/BotPopulationDirector.java` | `plan` (pure): finished sessions end (each session's length jittered 0.75-1.35x around the bot's habit), above the curve the out-of-window and longest sessions leave, below it logins are drawn weighted 1.0 in-window / 0.08 off-window, a 20 min relog cooldown, 2% hysteresis; the live tick (30 s), a boot fill to the current target at once, logins spread across the tick on virtual threads, `logIn` (adopt + resume placement + a fresh activity from the personality unless the session is a resume), `logOut` (only when `mayLogOut`: no trade or armed invite, no SocialBot conversation, no party with a real player, not a FollowerBot), shutdown hook |
| `soloMapling/ArtificialPlayer/BotRoster.java` | `markSessionEnded`: a director logout records `ended`, so the next login is a fresh session (home kept, activity drawn anew) while a shutdown stays a resume |
| `soloMapling/Environment/EnvironmentManager.java` | waves 8 and 9 move into `seedPopulationWaves`; with the director on and at least `BOT_ROSTER_SEED_MIN` active members the director starts instead |
| `soloMapling/ArtificialPlayer/BotGeneration.java` | a bot the population waves mint is enrolled in the roster, so a fresh install seeds itself |
| `config/ServerConfig.java`, `config.yaml` | `BOT_DIRECTOR_ENABLED` (true), `BOT_ROSTER_PEAK_ONLINE` (2400 roster bots; staff come on top), `BOT_ROSTER_CURVE`, `BOT_ROSTER_WEEKEND_LIFT`, `BOT_ROSTER_TIMEZONE`, tick and per-tick caps, `BOT_ROSTER_SEED_MIN` |
| `client/command/commands/gm4/RosterCommand.java` | `!roster director`, `!roster curve`, `!roster login|logout <name|id>`, `!roster pause|resume` |
| `src/test/.../BotPopulationDirectorTest.java` | 5 tests: curve maths; two simulated days on 30 s ticks (1500 bots, peak 600) - worst miss 0.17% of peak at hourly samples, only the boot fill exceeds 25 logins; regulars - 51% of day 2's 8pm crowd is back on day 3 against 33% by chance; pinned (committed) bots never leave and the cooldown holds; finished sessions are replaced by other bots |
| `src/test/.../BotPopulationProof.java` | verify logs the tracked member in through `BotPopulationDirector.logIn` when the boot fill did not pick it |

Deferred to ticket 09: the goodbye line, leaving a party, and the wait cap for a bot that holds a commitment
(today it simply is not chosen). Deferred to ticket 10: growing the roster past the seeded 2508.

## Findings from the real boots

Two defects the simulation could not see, both found by the sandbox boot and fixed:
1. **The relog cooldown blocked every login after a restart.** A shutdown stamps every member's `LastLogoutAt`, so
   on the next boot every bot was inside its 20-minute cooldown and the boot fill logged in nobody (the world came up
   with only the ~330 staff). The cooldown now applies only to sessions the director ended (`ended` in the activity
   state); `BotPopulationDirectorTest.playersLoggedOutByARestartComeStraightBack` pins it.
2. **The boot fill starved the connection pool.** ~1000 logins started at once on virtual threads, each loading a
   bot from the database; the Hikari pool (52) timed out (`active=52, waiting=79`) and every login failed. Logins now
   hold one of 8 `LOGIN_SLOTS`.

`[EnvironmentManager] population: ...` and `[Director] boot fill: ...` lines go to stdout (the harness silences log4j).

## Verification

- Unit/simulation: `BotPopulationDirectorTest` 6/6. Full suite (2026-10-18): `Tests run: 2132, Failures: 1,
  Errors: 4` - the five known WZ-missing failures only.
- Real-WZ sandbox, both boots: `population: director on, roster ready`; `boot fill: target 979, roster 2508
  (0 online), logging in 979` then `target 965 ... logging in 965`; population settled at ~1305 (≈330 staff + the
  fill) instead of today's ~2900; run phase 0 errors; the tracked roster bot `Jun1567` logged in at exactly the map
  and point its row recorded (`211040900 (176, -423)`).
- The target came from the container's clock (UTC; the curve read ~0.4 at 00:xx UTC). Set `BOT_ROSTER_TIMEZONE`
  to the owner's zone for real use.
- Final two-boot proof (2026-10-18): `PROOF-VERIFY PASS`, 14/14, 0 run-phase errors; fills of 947 and 936; the
  tracked member `LudiGang84` was not in the second fill, so the harness logged it in through
  `BotPopulationDirector.logIn` (`logging it in: true`) - the director, not the boot, decides who plays.
