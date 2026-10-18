# SoloMapling dissection

Revision: 47b61b5381118ebdc1064ddbd0b1c32f75778871. Source checkout: /tmp/solomapling-analysis. Read-only product investigation. Entry records: architecture.md, data-flow.md, call-graph.md.

## Conclusions
1. These are server-resident Characters backed by a shared headless BotClient, not external clients or keyboard/vision automation. Native game objects provide authoritative state; standard packets render behavior to humans.
2. Navigation is mixed: authored recording graphs remain active; newer GC navigation builds movement-profile-specific graphs from footholds/ropes/portals, bakes jumps through physics, uses bot-seeded weighted A*, and executes server physics. Seed-zero callers retain Dijkstra. World travel uses BFS, direct map changes, and explicit warp recovery.
3. TrainingBot is active production code. Its 250 ms combat loop chooses map strategies, approaches targets, and invokes a separate synthetic combat driver. Actual monster HP, deaths, EXP attribution, and map drops change. Damage is configured job-tier/level arithmetic, not normal player stat/skill validation.
4. Many actions intentionally trade fidelity for appearance: cosmetic air swings, self-buffs, hurt numbers without HP loss, prerecorded jump quests, staged scrolling, generic pickup removal without inventory credit, and offscreen EXP accrual without monster fighting.
5. Social, party and economy effects mix scripts with native server operations. YAML/keyword dialogue, delayed emotes and probabilistic choices drive behavior. Party joins/trades are real. Shop purchases credit players but do not charge bots; stock is consumed rather than inventoried. NX codes are database-backed. OPQ supports specific cloud/record tasks, not the whole quest.
6. Observer-dependent simulation is central: detailed movement/combat for observed maps, coarse interpolation/snaps and abstract leveling when unobserved. The README and several comments describe older implementations and cannot be treated as current specifications.

## Important boundaries
- Movement is not guaranteed physical end-to-end: no-route/stall recovery can warp; coarse movement can snap and omit approach/final segments.
- Monster movement still relies on real player clients; bots are excluded from monster controller selection.
- Combat approach target and actual hit targets are selected separately. Driver filtering lacks the strategy's friendly-monster exclusion.
- Direct Monster.damage bypasses parts of ordinary attack handling, resource consumption, and some special monster rules.
- Shared chat queues and singleton OPQ context have concurrency/isolation risks; these were source observations, not reproduced in a live game.

## Verification
Compiled and ran the repository's actual BotTickService.java, BotPerfStats.java, and ExecutorServiceManager.java with Java 21 and a temporary harness. Probe used 250 ms ticks with a 20 ms requested period and deliberately threw on the first tick. Exact result:

actual BotTickService: completed=3 maxConcurrent=1 virtualThreadTicks=3 survivedFirstTickException=true registeredAfterRemoval=false

This proves the exercised scheduler dispatch, no same-entry overlap, continued ticking after that exception, and removal. It does not prove navigation/combat rendering or a full game session. No DB/WZ/client was configured; no full server startup, end-to-end gameplay, or project test suite was run. Scouts performed source-only investigation. No product code edits.
