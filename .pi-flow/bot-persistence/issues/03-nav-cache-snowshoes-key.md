# 03: The nav graph disk cache ignores snowShoes

**What to build:** `BotNavigationGraphProvider` keys graphs by (map, speed, jump, snowShoes) in memory but the
cache filename (`:568`) and the `loadGraph` checks (`:546-549`) leave snowShoes out, so a snowshoe graph and a
plain graph can overwrite or load as each other. Put snowShoes in the filename and the load check; bump
`GRAPH_VERSION` only if old files would otherwise load wrongly.

**Blocked by:** none.

**Status:** done (2026-10-18)

- [x] A test showing the two keys produce two files and each loads as itself.
- [x] Old cache files either still load correctly or are invalidated by version, never misread.

## What was built (as landed)

Scope note: `canonicalProfile` strips snowshoes except on slippery maps, so only those maps were affected.

| Anchor | Change |
| --- | --- |
| `GCMoveSystem/BotNavigationGraphProvider.java` | `graphFile` adds `-ss` for a snowshoe key (plain names unchanged, so existing plain files stay valid); `isGraphFor` adds the snowshoe check to the load test, so a snowshoe graph left under a plain name is refused and rebuilt. No `GRAPH_VERSION` bump. `GraphCacheKey`, `graphFile`, `isGraphFor`, `GRAPH_VERSION` made package-private for the test |
| `src/test/.../GCMoveSystem/BotNavigationGraphCacheKeyTest.java` | 2 tests: distinct files, each graph loads only as its own key |
