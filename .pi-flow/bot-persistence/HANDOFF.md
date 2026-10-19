# Handoff — the living bot population (bot-persistence series)

Written 2026-10-18 at commit `e228857e`, for a session started in `/root/SoloMapling`. Read this, then
`spec.md` and the ticket you are picking up. Nothing here is a live instruction: it is state. The older
accounting/durability series (`../scalable-bot-simulation/`, `../HANDOFF.md`) is finished and parked — the owner
does not want more economy/ledger work unless asked.

## What the owner wants

An AzerothCore-playerbots-style world: a persistent roster of randomly generated players who log in and out on a
daily curve, resume exactly where they stopped, talk through an LLM like real 2006 players, remember and gossip
about the real player, can be reached however a player would (nickname, whisper, menu window), and move better over
time. Settled decisions are the table in `spec.md`.

## Where things are

| What | Where |
| --- | --- |
| Spec + tickets | `.pi-flow/bot-persistence/spec.md`, `issues/01-23-*.md` (each has `What was built (as landed)` and `Verification`) |
| Commits | `baef5353..e228857e` on local `main`, **never pushed** (remote `origin = github.com/joshhmann/SoloMapling.git`) |
| Uncommitted, not ours | `src/main/java/client/Character.java` + `src/test/java/client/CharacterRingListConcurrencyTest.java` — another (stopped) session's crush-ring CME fix. Leave alone unless the owner says. |
| Real-WZ sandbox | `/tmp/solomapling-e2e` (ephemeral). Port tweaks: `Channel.java:78 BASE_PORT = 17575`, `Server.java:950 initLoginServer(18484)`. **Copying `Server.java` into it overwrites the port line** — re-apply the sed. Sync = copy every differing `src` file except those two. |
| MySQL | container `solomapling-verify-db`, root/no password; sandbox schema `cosmic_ticket06boot2` (now holds ~4,270 roster members from the scale run); scratch schema `cosmic_t04_scratch` |
| Owner's model servers (LAN) | Qwen3.8-9B llama.cpp `http://192.168.0.17:8080/v1` (2 slots, needs `chat_template_kwargs: {enable_thinking:false}`); GLiNER2.5-Decide `http://192.168.0.17:8900/v1/systemone` (serialized, no auth) |

## Status: 21 of 23 done

| # | Ticket | State |
| --- | --- | --- |
| 01 | `!roster status/saveall` + restart check | done |
| 02 | bots no longer drop each other's chat lines; dispatcher drains every line | done |
| 03 | snowshoe nav cache key | done |
| 04 | roster identity (`botroster`, migration 026, 2508 enrolled) | done |
| 05 | unique names (`BotNames`, drop-in `BotNames/*.txt`) | done — **owner's 2004-2010 name list not in yet** |
| 06 | resume exactly (placement, forced-return maps, grind in place) | done, all seen live |
| 07 | personality + per-session activity | done |
| 08 | population director (daily curve, boot fill, churn) | done |
| 09 | polite logout (bye, party leave, commitment wait, 15 min cap) | done |
| 10 | roster lifecycle (beginners, veterans, quit, return) | done (simulated) |
| 11 | scale proof at 3,300 / 4,100 (`BotScaleProof`) | done |
| 12 | `BotBrain` over any OpenAI-compatible URL | done |
| 13 | LLM chat with personas + world facts | done |
| 14 | memory notes (`botmemory`, migration 027) | done |
| 15 | gossip (hearsay, 2 hops) | done |
| 16 | nav learning from failures (`NavEdgeLearning`) | done — learns only on player-observed maps |
| 17 | nav learning from real players' movement | **open** |
| 18 | heatmaps: bots go where players go | **open** (after 17) |
| 19 | addressing: fuzzy names, "u talking to me?", reply rule, GLiNER by job | done |
| 20 | omit gear with broken sprites (`scripts/bot_item_audit.py`) | done |
| 21 | one interaction layer (`BotConversation`, `BotMenus`) | done (service bots' own hints unchanged) |
| 22 | whispers reach bots | done |
| 23 | clickable NPC-window menus (`scripts/npc/botmenu.js`) | done |

Also landed: the owner's other branch `fix/headless-drop-delivery` (cherry-picked as `7b6a8a0b`).

## The biggest open item is not a ticket

**Nothing chat-side has been seen in a real client**: the NPC window, whispers, "u talking to me?", trainer/wanderer
chat, a bot saying bye and leaving a player's party. Everything is unit-tested and the LLM/GLiNER parts were tried
live against the owner's servers. A 15-minute in-game session with this config is the most valuable next step:

```yaml
BOT_LLM_BASE_URL: "http://192.168.0.17:8080/v1"
BOT_LLM_MODEL: "Qwen3.8-9B-Distill-Heretic-Uncensored-Q8_0"
BOT_DECIDE_URL: "http://192.168.0.17:8900"
BOT_ROSTER_TIMEZONE: "America/Los_Angeles"   # owner's zone; blank = server clock (UTC in the sandbox)
```
and the owner's name list in `src/main/java/soloMapling/ArtificialPlayer/BotNames/` **before** the first real boot
(ticket 05 renames ~1,900 bots that share names on that boot, permanently).

## Verification recipes

- Repo suite: `mvn -o test` -> `Tests run: 2165, Failures: 1, Errors: 4` = only the five WZ-missing failures
  (`GCWorldGraphTest`, `CoarseExecutorTest` x2, `GCMoveBakeTest` x2). Anything else is a regression.
- Two-boot proof: sync the sandbox, `mvn -o -q package -DskipTests && mvn -o -q test-compile` there, then
  `bash /tmp/botproof_driver.sh` -> `PROOF-VERIFY PASS`, 15/15 lines in `/tmp/botproof/verify.txt`.
- Scale proof: in the sandbox, `java -Xmx6g -cp target/Cosmic.jar:target/test-classes
  soloMapling.ArtificialPlayer.BotScaleProof` -> `/tmp/botscale/results.txt`.
- Live LLM checks: scratch `java -cp target/Cosmic.jar <file>.java` programs against the owner's endpoints (see
  tickets 12-15 for the exact prompts and results).

## Trap list

- **Stuck proof JVMs.** A proof/scale JVM often hangs in teardown after its results are written; it keeps the login
  port and ~50-100 MySQL connections (`ERROR 1040 Too many connections`, or `LoginServer.start` bind failures on the
  next run). Check `pgrep -af "BotPopulationProof|BotScaleProof"` before every run; kill your own.
- **The driver's `Worlds and channels are offline` wait always times out** (log4j is off in the harness); the
  `[PROOF]`, `[Director]`, `[EnvironmentManager]`, `[BotShutdown]` stdout lines are the markers.
- **Director at boot** logs in only the curve's target (e.g. ~950 at 00:xx UTC), so the world is smaller than the old
  fixed ~2,900. `BOT_DIRECTOR_ENABLED: false` restores the old waves. The relog cooldown applies only to sessions the
  director ended (`ended` in the activity state), never to a restart; logins hold one of 8 `LOGIN_SLOTS` (an
  unbounded boot fill starved the 52-connection pool).
- **GLiNER Decide cannot pick 1 of N people** (0/8 live); it is used only to name the *kind* of player a request
  wants. Replies to the last speaker are a rule. Buff requests belong to `BotBuffRequestHandler`.
- **`NPCScriptManager.start` closes an open NPC window and silently no-ops during the click cooldown** — `BotMenus`
  checks both and falls back to the chat hint.
- **Macro-tick wall time is large** (~20 ms/tick) on the session's starting commit too; it includes blocking waits.
  Judge load by `BotPerfStats` lag and `BotTickService.throttleFactor()`, not macro-tick ms/s.
- **Nav learning needs an observer**: offscreen bots use the coarse planner and never fail a physical edge.
- Cone-mode sparse checkout (`.mvn .pi-flow database docs scripts src` + top-level files): a new top-level dir needs
  `git sparse-checkout add`.
- `graphify update .` after code changes (AGENTS.md).
