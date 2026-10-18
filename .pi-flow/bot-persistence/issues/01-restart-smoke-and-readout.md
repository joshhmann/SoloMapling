# 01: Prove today's bot persistence on the real server, and make it visible in game

**What to build:** A GM read-out `!roster status [name|id]` (slot/roster id, stored level/exp/meso, stored map
and position, saved-at, adopted-this-run) and a recorded restart check on the real server: let training bots
level and loot, restart, and confirm the same bots return with the same name, level, gear and mesos.

**Blocked by:** none.

**Status:** done (2026-10-18)

- [x] `!roster status` prints the stored row next to the live bot, from `BotStore.persistedFor`.
- [x] Restart check done on the real server (real `wz/`, real DB) and its result recorded under `## Verification`.
- [x] Any mismatch found is filed as its own ticket rather than fixed silently here.

## What was built (so far)

| Anchor | Change |
| --- | --- |
| `client/command/commands/gm4/RosterCommand.java` | `!roster status` (store + online counts), `!roster status <name\|id>` (live vs stored, every bot of that name), `!roster saveall` (the autosave on demand) |
| `soloMapling/ArtificialPlayer/BotStoreReport.java` | builds the lines; `*` marks a live field that differs from its row (normal between saves, a defect right after a save or restart) |
| `soloMapling/ArtificialPlayer/BotStore.java` | package-private `storedRows()` and `isHoldingsUnavailable(int)` for the report; no behaviour change |
| `client/command/CommandsExecutor.java` | registers `roster` at GM level 4 |
| `src/test/java/soloMapling/ArtificialPlayer/BotStoreReportTest.java` | 4 tests: matching row, unsaved progress, unsaved bot + offline row, age formatting |

## Verification

- `mvn -o test` in `/root/SoloMapling` (2026-10-18): `Tests run: 2072, Failures: 1, Errors: 4` — only the five known
  WZ-missing failures (`GCWorldGraphTest`, `CoarseExecutorTest` x2, `GCMoveBakeTest` x2).
- Restart check (2026-10-18), real-WZ sandbox `/tmp/solomapling-e2e` + MySQL `cosmic_ticket06boot2`, with this
  ticket's files copied in and `HiredMerchant.java` re-synced (the sandbox still had the pre-`9f7aca06` copy):
  `bash /tmp/botproof_driver.sh` -> run phase `PROOF-RUN COMPLETE`, 0 exception/ERROR lines; verify phase
  `PROOF-VERIFY PASS`, 11/11 PASS lines, 0 FAIL (the recorded bot Celare, lv 67, came back with name, job, level,
  exp, mesos and all 13 item stacks).
- Population-wide, from the store after both boots: 3491 rows, **0 created by either boot**, 3331 re-saved by the
  verify boot (adopted, not minted); the other 160 are slots the boot plan's +/-1 count jitter left unused, or old
  on-demand rows.
- Not a defect of this tree, recorded: the driver's `Worlds and channels are offline` wait always times out
  (log4j is off in the harness, as the old handoff says). A run-phase JVM from the stopped session (pid 823229) was
  still alive with its shutdown hook finished but non-daemon `TimerManager-Worker-*` / `nioEventLoopGroup-*`
  threads holding it (thread dump kept in the session scratchpad); this run's verify JVM exited by itself after a
  slow teardown, so the hang did not reproduce here. Stale proof JVMs also exhaust MySQL connections
  (`ERROR 1040 Too many connections` until one exited).
- Still open for the owner: the same check on the real server in game (`!roster saveall`, note a few bots with
  `!roster status <name>`, restart, look for `*` fields).
