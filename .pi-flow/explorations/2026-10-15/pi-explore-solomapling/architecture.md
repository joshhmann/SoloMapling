# Architecture

Source checkout: /tmp/solomapling-analysis; GitHub commit 47b61b5381118ebdc1064ddbd0b1c32f75778871. Investigation only; no product source edits.

## Identity and host engine
- `src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:83-109,128-135,192-197`: load template Character ID 2 from DB, assign synthetic identity, register in channel/world storage, place on map, decorate, asynchronously play arrival choreography.
- `src/main/java/client/BotClient.java:12-48`: shared headless Client has no socket; outbound packets to the bot are discarded, client reports logged in, account login-state writes disabled. It is not an external client controller.
- `src/main/java/soloMapling/ArtificialPlayer/BotClientHandler.java:20-28` owns the shared client. `src/main/java/net/server/Server.java:961-964` initializes it before optional bot startup. README's first-player/relog dependency is outdated.
- Game behavior ultimately calls Cosmic Character/MapleMap/Trade/monster APIs and broadcasts standard packets to actual players.

## Behavior and scheduling
- `src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java:46-179,184-226`: concrete FSM factory; type conversion preserves Character and refuses mid-trade; initial schedule waits for arrival choreography.
- `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:72-110,227-255,295-395`: shared state lifecycle, trade interception, timestamp-based waits, observer-dependent cadence; default observed 2–6 s, unobserved 9–12 s with subtype overrides.
- `src/main/java/soloMapling/server/BotTickService.java:74-139,149-214`: shared 100 ms driver scans due bots, dispatches virtual-thread ticks, guards same-bot overlap, measures period after completion, allows nudges, stretches steady cadence under lag.
- `src/main/java/soloMapling/Environment/EnvironmentManager.java:178-208`: current startup includes training (wave 8) and town presence (wave 9), beyond README's seven waves.

## Navigation
- Legacy `BotMovementSystem`: graph of authored recording areas/connectors, selected stream playback, timestamped position updates and broadcasts. It is not terrain-based physics. Still used by Henesys/JQ/OPQ and scripted sequences.
- `GCMoveSystem/GCMovement.java`: high-level move/follow/travel control. `BotNavigationGraphProvider.java`: WZ footholds/ropes/portals → cached region graph with physically baked WALK/DROP/JUMP/CLIMB/PORTAL edges. Cache is movement-profile dependent.
- `GCMoveSystem/BotNavigationManager.java:939-1019,1533-1579`: active Character calls supply stable nonzero per-bot seed → randomized weighted A*, 0–55% edge jitter and heuristic weight [1,1.15); seed-zero callers retain Dijkstra mode. Old comments overstate universal Dijkstra routing.
- `GCMoveSystem/BotMovementManager.java` and `BotPhysicsEngine.java`: steering, committed launch edges, gravity and collision, authoritative position updates, then packet synthesis. Observed physics nominally 50 ms; planning 100 ms.
- `GCMoveSystem/GCMovementDriver.java`, `MovementPlan.java`: observer-dependent simulation; unobserved routes may interpolate endpoints and snap, not follow full physics.
- `GCMoveSystem/GCWorldGraph.java`, `GCTravel.java`, `GCPortals.java`: BFS world routes include portals, taxis and curated scripted connections; walk to trigger then direct Character.changeMap; no-route/stall paths can warp.
- `BotWanderSystem/BotWanderSystem.java`: reachable target selection and dwell loop layered above GC, not a separate physics system.

## Integration direction
Concrete bot FSMs and action loops → movement/combat/trade/social services → Cosmic game objects → standard observer packets. Configuration and WZ supply content/geometry; no real client, vision model, or keyboard injection drives these characters. Detailed combat and non-movement action seams are recorded in the data-flow and synthesis records.
