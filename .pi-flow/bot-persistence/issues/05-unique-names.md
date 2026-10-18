# 05: Unique, era-appropriate bot names

**What to build:** A name source for a ~2000+ roster with no duplicates. Input: an owner-supplied list of
2004-2010-era IGNs (plain text, one name per line) merged with `FMNameDesc/randomRealMaplestoryIGNs.txt`
(1636 lines today). Every name must pass `Character.canCreateChar`'s rule `[a-zA-Z0-9]{3,12}`
(`client/Character.java:1005`) — longer or symbol names are dropped or normalised, not truncated into nonsense.
When the pool runs dry, generate era-style variants (trailing digits, `x...x` wrapping, `i`/`l` swaps, two-word
joins) and check them. A name is unique across the roster, staff, and real `characters` rows.

**Blocked by:** 04.

**Status:** open

- [ ] Loader reads the owner's list plus the existing file, filters to the legal pattern, dedupes case-insensitively.
- [ ] New roster bots never reuse a name held by another roster bot, a staff bot or a real character.
- [ ] Variant generator produces legal, unique names after the pool is exhausted (test with a tiny pool).
