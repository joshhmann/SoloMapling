# 07: Stable personality, activity chosen per session

**What to build:** A personality generated once per roster bot (activity weights: grind / town / social;
chattiness; play window; typical session length) stored in `Personality`. Each fresh session picks an activity
from the weights and the bot's level and maps it to a bot type (grind -> `TrainingBot`, town ->
`TownWandererBot`, social -> `SocialBot`) through `BotTypeManager.setAndStartBots`/`convertBotType`. A resumed
session (ticket 06) keeps its previous activity. Activity may switch mid-session occasionally (e.g. a grinder
goes to town to hang out).

Hard needs are rules in code, never a model's call: a full inventory or no potions sends the bot to town, a
session past its length ends it. A model (the decision endpoint of ticket 19) may only break ties among
choices that are all acceptable — probing showed it picks "grind" for a bot with a full bag when its
description says it likes grinding.

**Blocked by:** 06.

**Status:** done (2026-10-18) — mid-session switching moves to ticket 08 (it needs the director's tick)

- [x] Personalities are deterministic from their seed and stored, not re-rolled per boot.
- [x] Over many sessions a bot's activity mix follows its weights (test with a fixed seed).
- [x] Level gates hold (no level-1 bot assigned to a high-level grind). (grind maps are chosen by `TrainingMapChooser` from the bot's level, so every activity is level-safe as is; no extra gate added)

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotPersonality.java` | `Traits` (grind/town/social mix, chattiness, play window, session length) generated once from a seed; an evening-heavy play-window distribution (55% start 17-21h, 20% afternoon, 15% late night, 10% morning; 1.5-6 h windows, 40 min-4 h sittings); `choose` draws a fresh session's activity from the mix; `Activity` maps an activity to its recorded name and bot type |
| `soloMapling/ArtificialPlayer/BotRoster.java` | a member gets traits at its first login (seed = its slot), tilted toward its habit - its last activity, or grind for wave 8 and town for wave 9 - so the migrated population keeps its character; written by the next session record; `resumedType` / `peekResume` |
| `soloMapling/ArtificialPlayer/BotTypeManager.java` | `setAndStartBots` starts a resuming member as the activity it logged out in, not the wave's type |
| `src/test/.../BotPersonalityTest.java` | 5 tests: deterministic + column round trip, habit tilt (grinders avg >0.6 grind), session mix within 2% of weights over 20k draws, 55% +/-3% evening starts and sessions inside their window, recorded names match the bot types' snapshot names |

Where the choice is made: every boot today is a resume (bots "log out" at shutdown), so `choose` is first used by the
director (ticket 08) for fresh logins; that ticket also owns occasional mid-session switches. Hard needs stay in
code (a full bag means town) - the decision model of ticket 19 may only break ties.

## Verification

- `BotPersonalityTest` 5/5. Full suite (2026-10-18): `Tests run: 2117, Failures: 1, Errors: 4` - the five known WZ-missing failures only.
