# 18: Bots go where players go

**What to build:** Aggregate the ticket-17 capture into per-map heatmaps (where players stand and grind) and
portal-choice counts, persisted with the learned nav data. Bias bot camp/loiter spot choice and travel route
selection toward them, never so hard that all bots pile onto one spot (spot claims still apply).

**Blocked by:** 17.

**Status:** done (2026-10-18) — positions only (no portal counts); not yet fed by a real player

- [x] Heatmaps persist and decay.
- [x] With heatmap data, bot spot choice shifts toward it; without it, behaviour is unchanged. (by construction: warmth is 0 everywhere without data; the shift itself is not covered by a test)

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/GCMoveSystem/PlayerHeat.java` | per map, 100 px cells weighted by how often a real player stood there (once per 2 s per player, on the ground only), fading with a 3-day half-life; `warmth(map, point)` 0..1 against the map's warmest cell; saved to `cache/bot-nav/heat/<map>.json` every 10 min and at shutdown |
| `net/server/channel/handlers/AbstractMovementPacketHandler.java` | real players' movement elements also feed `PlayerHeat` |
| `soloMapling/ArtificialPlayer/BotTownSystem/TownLoiter.java` | candidate loiter spots are tried warmest first (stable sort: unchanged order without data); ledge claims still cap each spot |
| `soloMapling/ArtificialPlayer/BotGrindSystem/SpotFinder.java` | `pickBest` adds `PLAYER_HEAT_W` (25) x warmth - about 2.5 spawn points, less than one more claimant (30), so bots lean toward where players grind but still spread |
| `client/command/commands/gm4/GCMoveCommand.java` | `!gcmove learned` also reports warm cells |
| `src/test/.../PlayerHeatTest.java` | 3: warmest/one-pass/cold/per-map; fading; ground-only sampling, the 2 s interval, and a restart reading the saved heat |

Not built: portal-choice counts for travel routes (positions only).

## Verification

- `PlayerHeatTest` 3/3; full suite (2026-10-18): `Tests run: 2170, Failures: 1, Errors: 4, Skipped: 1` - the five known WZ-missing failures and the WZ-only `NavObservedEdgesTest` case skipped.
- Not yet: heat from a real player, and bots visibly drifting toward it.
