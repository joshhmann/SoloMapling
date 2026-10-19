# 05: Unique, era-appropriate bot names

**What to build:** A name source for a ~10,000 roster (3x a ~3,300 peak) with no duplicates. Input: an owner-supplied list of
2004-2010-era IGNs (plain text, one name per line) merged with `FMNameDesc/randomRealMaplestoryIGNs.txt`
(1636 lines today). Every name must pass `Character.canCreateChar`'s rule `[a-zA-Z0-9]{3,12}`
(`client/Character.java:1005`) — longer or symbol names are dropped or normalised, not truncated into nonsense.
When the pool runs dry, generate era-style variants (trailing digits, `x...x` wrapping, `i`/`l` swaps, two-word
joins) and check them. A name is unique across the roster, staff, and real `characters` rows.

**Blocked by:** 04.

**Status:** done (2026-10-18)

- [x] Loader reads the owner's list plus the existing file, filters to the legal pattern, dedupes case-insensitively.
- [x] New roster bots never reuse a name held by another roster bot, a staff bot or a real character.
- [x] Variant generator produces legal, unique names after the pool is exhausted (test with a tiny pool).

## What was built (as landed)

Finding: the store already had duplicate names. The old pool (1592 legal names, case-insensitive) reshuffled
when it ran out, and 3491 bots were drawn from it, so most names were held by two or three bots.

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotNames.java` | the pool (legacy IGN file + every `*.txt` in `BotNames/`, `#` comments, legal `[A-Za-z0-9]{3,12}` only, case-insensitive dedupe), one holder per name for good, real players' names (`SELECT name FROM characters`) always taken, era-style variants once the pool is spent, `claimStored`: the earliest-created holder keeps a shared name and later holders are renamed when adopted (the rename is written by the bot's next save) |
| `soloMapling/ArtificialPlayer/BotNames/README.md` | where the owner's list goes and the rules |
| `soloMapling/ArtificialPlayer/BotGeneration.java` | `createBot` claims the name after deciding adopt-or-mint; the placeholder draw is gone, so adopted bots no longer burn pool names |
| `soloMapling/FreeMarket/FMShopDescGen.java` | `registerCharacterName` keeps shop owners drawable from online bots |
| `src/test/.../BotNamesTest.java` | 6 tests: filtering, no repeats / no real-player names, 500 legal unique names from a 2-name pool, oldest keeps a shared name, unheld stored name kept and reserved, variants legal |

Follow-up, done later (2026-10-18): `Character.canCreateChar` now also calls `BotNames.reserveForPlayer` - a name a
bot holds is refused, and a name that passes is kept from bots for good at the check itself (so no bot claims it
between the check and the creation; a checked-but-unused name merely leaves the bots' pool). This covers new
characters and cash-shop name changes alike. `BotNamesTest` +1. The owner's list is in too: `BotNames/` now holds
12,000 era-style generated names (`scripts/gen_era_names.py`), 13,592 in the pool with the legacy file.
