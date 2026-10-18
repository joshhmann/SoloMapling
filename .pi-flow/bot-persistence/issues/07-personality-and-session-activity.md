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

**Status:** open

- [ ] Personalities are deterministic from their seed and stored, not re-rolled per boot.
- [ ] Over many sessions a bot's activity mix follows its weights (test with a fixed seed).
- [ ] Level gates hold (no level-1 bot assigned to a high-level grind).
