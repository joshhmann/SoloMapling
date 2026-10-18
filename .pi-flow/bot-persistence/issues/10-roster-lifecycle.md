# 10: Roster lifecycle — new characters, veterans, dormancy, returns

**What to build:** Keep the roster around `rosterMultiple × peak` (default 4x). A small daily rate of new
characters, mostly level 1 at the beginner start; when the roster is empty or far below size, seed "veterans"
across a level distribution with matching job and gear (`BotDecorate`). A bot that has not logged in for a long
stretch may become DORMANT (row kept); a dormant bot has a small daily chance to return. Rates live in config.

**Blocked by:** 08.

**Status:** open

- [ ] First boot on an empty roster seeds a level spread, not all level 1.
- [ ] Simulated weeks keep the roster near its target size, with some dormancy and occasional returns.
- [ ] A returning bot comes back with its old name, level, items and memories.
