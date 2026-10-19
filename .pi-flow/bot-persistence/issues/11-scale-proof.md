# 11: Prove ~3,300 and ~4,100 online with the director

**What to build:** Extend `BotPopulationProof` (or a sibling harness) to drive the director at today's ~3,300 peak and 1.25x that, and record `BotPerformanceMonitor` costs split into sparse, crowded, transition and the new
login/logout cost. Tune LOD and login spreading until the target holds; only then raise the default peak.

**Blocked by:** 08.

**Status:** done (2026-10-18)

- [x] Numbers for ~3,300 and ~4,100 online recorded under `## Verification`, with host specs.
- [x] Login/logout cost measured separately from promotion/demotion cost.
- [x] The default peak in config is set from the measurement, not guessed.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `src/test/java/soloMapling/ArtificialPlayer/BotScaleProof.java` | test-scope main: boot; grow the roster through `BotRosterLifecycle.create` (8 at a time) until it covers each target; set the director's curve flat at that peak and `fillNow`; settle; a 30 s window of `BotPerformanceMonitor` + `BotPerfStats` scheduler lag + `BotTickService` throttle + heap; then 200 logouts through the director and the refill, inside one window. Writes `/tmp/botscale/results.txt` |
| `soloMapling/ArtificialPlayer/GCMoveSystem/BotPerformanceMonitor.java` | `roster-login` / `roster-logout` TRANSITION phases |
| `soloMapling/ArtificialPlayer/BotPopulationDirector.java` | times each login; `fillNow` for the harness |
| `soloMapling/ArtificialPlayer/BotLogout.java` | times each logout |

## Verification (real-WZ sandbox, 32 cores, `-Xmx6g`, 2026-10-18)

| Online | Heap used | Scheduler | Throttle |
| --- | --- | --- | --- |
| 1,129 (boot, curve as configured) | 715 MB | 2,740 dispatches / 30 s, avg lag 49.9 ms | 1.00 |
| 3,300 (332 staff + 2,968 roster) | 885 MB | 6,797, avg lag 49.7 ms | 1.00 |
| 4,075 (332 staff + 3,743 roster) | 954 MB | 6,337, avg lag 48.9 ms | 1.00 |
| 3,975 after 200 logouts + refill | 974 MB | 4,560, avg lag 47.8 ms | 1.00 |

- The scheduler lag does not move with the population (49 ms is the wheel's own granularity) and the adaptive throttle
  never engaged: the world holds at 1.25x today's peak.
- Growing the roster: 560 new characters in 3,920 ms and 800 in 5,486 ms at 8 in parallel (~55 ms per login of a new
  character, all-in).
- Macro-tick wall time is large (22.7 s/s at 3,300 online), so it was checked against the session's starting commit
  (`ae662c74`, a separate worktree on the same database, same proof window): ~20.4 ms per macro tick there (2,842 bots)
  vs ~22.2 ms here (1,170 bots) - no regression from this series. It is wall time and includes blocking waits inside
  ticks; the flat lag and idle throttle are the load signal. An earlier afternoon reading of ~2.7 ms/tick came from a
  different moment of a different run and is not comparable.
- Churn, measured inside its own window (second run: 3,300 lag 48.7 ms, 4,100 lag 48.3 ms, throttle 1.00 again):
  **login ~33 ms** each (100 samples, 3.3 s), **logout ~284 ms** each (200 samples, 56.8 s wall, 200 at once). A
  logout writes the bot's row and items, its session record and its party leave, contending for the database when
  200 run together; at the director's cap (25 per 30 s tick) that is ~7 s of wall time spread over threads per tick.
  Logout is the director's most expensive step - batching its writes is the place to optimise if it ever matters.
  Only 100 of the 200 came back: the 200 that left are in their 20-minute relog cooldown and ~100 other active
  members were offline - as designed.
- Default `BOT_ROSTER_PEAK_ONLINE` stays 2,400 roster bots (~2,730 with staff), inside the measured envelope.

## Follow-up (2026-10-18): logouts spread across the tick

The director fired a tick's logouts (up to 25) all at once, each writing a row, items and a session record - the
contention the 200-logout burst exposed. They are now scheduled at random points across the tick, like logins, and a
logout planned but not yet started counts as already gone in the next tick's planning (`scheduledLogouts`), so it is
neither planned twice nor double-counted. Not re-measured under the director's own churn (the harness's churn calls
`logOut` directly).
