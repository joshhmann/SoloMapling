# 11: WZ reads are not thread-safe (one Xerces document, many wrappers)

**What to build:** stop the WZ reader from throwing under the bot fleet. Every bot thread reads item
data through `server.ItemInformationProvider` -> `provider.wz.XMLDomMapleData`, and every call built a
fresh wrapper around a node of the same parsed `Document`. The `synchronized` modifiers on those
wrappers locked the wrapper instance, which no other thread ever shared, so two readers walked
Xerces' lazy `NodeListCache` at the same time and one of them threw
`NullPointerException: Cannot assign field "fChildIndex" because "this.fNodeListCache" is null`.
Fix the traversal (one lock per parsed document, inherited by every wrapper) and make the provider's
caches safe for the same fleet.

**Blocked by:** none.

**Status:** done (2026-10-17)

- [x] A wrapper's lock is the lock of the document it came from: child, grandchild and parent wrappers
      share one monitor per parsed file, and two different files stay independent.
- [x] The lock is walked down the tree, so the fix is per document, not per wrapper instance.
- [x] `ItemInformationProvider`'s caches are replaced with synchronized maps (they hold `null`
      values legitimately, so `ConcurrentHashMap` is not an option), and the dead `descCache` field is
      deleted rather than converted.
- [x] `FMBot.purchaseShopItems` no longer computes an item name it never uses (the frame that used to
      surface the WZ crash first).
- [x] A unit test pins the lock identity and that concurrent traversals of one document still return
      the same tree a single reader sees.
- [x] Booting the population reads WZ without a single `fNodeListCache` line, and the ticket-09
      teardown behaviour is unchanged.

## What actually raced

`provider/wz/XMLDomMapleData.java` (211 lines) declared seven `synchronized` methods, but the monitor
was the wrapper, and the wrapper is created per call: `getChildByPath` walks the tree and hands back
`new XMLDomMapleData(childNode)`, `getChildren` hands back one wrapper per child, `getParent` builds
another. Two threads asking the same document for two different paths therefore never contend. The
race itself is inside Xerces: `NodeImpl.getChildNodes()` hands out a live `NodeList`, and the first
`getLength()`/`item(i)` materializes the node's private `fNodeListCache` without synchronization, so
concurrent first-touch throws that NPE. The second walk in the class (`getChildren`) uses the same
`getChildNodes()` + `getLength()`/`item(i)` pattern.

Observed once per boot, in every harness log on disk:

```
java.lang.NullPointerException: Cannot assign field "fChildIndex" because "this.fNodeListCache" is null
    at provider.wz.XMLDomMapleData.getChildByPath(XMLDomMapleData.java:81)
    at server.ItemInformationProvider.getStringData(ItemInformationProvider.java:277)
    at soloMapling.ArtificialPlayer.BotHelpers.convertItemIdToName(BotHelpers.java:55)
    at soloMapling.ArtificialPlayer.BotTypes.FMBot.purchaseShopItems(FMBot.java:318)
```

It never took the boot down: the per-bot tick body catches `Throwable`
(`soloMapling/server/BotTickService.java:285-286`), so the crash is one lost bot tick per occurrence -
but it is a genuine data race on shared parser state, one line away from a `ConcurrentModification`
in the same cache, and it happens on the path every bot walks.

Counts before the fix (`grep -c fNodeListCache`):

| Log | Occurrences |
| --- | --- |
| `/tmp/ticket10-evidence/proof.out` (ticket-10 proof) | 1 |
| `/tmp/botproof_run.out` (ticket-10 harness run) | 1 |
| `/tmp/boot_boot5.out` (ticket-06 era) | 1 |
| `/tmp/boot2.out` (ticket-06 era) | 1 |

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `provider/wz/XMLDomMapleData.java:55` | `private final Object domLock` - the monitor every wrapper carries. |
| `provider/wz/XMLDomMapleData.java:58` | `DOCUMENT_LOCKS = Collections.synchronizedMap(new WeakHashMap<>())` keyed by `Document`, so a parsed file that goes away does not pin its lock. |
| `provider/wz/XMLDomMapleData.java:64-69` | `static Object lockFor(Node node)` resolves the owning `Document` (the node itself when it is the document node, else `getOwnerDocument()`) and returns its shared lock; a node with no document gets a private one. |
| `provider/wz/XMLDomMapleData.java:86` | the file constructor assigns `this.domLock = lockFor(this.node)`. |
| `provider/wz/XMLDomMapleData.java:89-91` | the private node constructor now takes the parent's lock, so the whole tree shares one monitor. |
| `provider/wz/XMLDomMapleData.java:102,131,148,190,225,239` | the seven methods replaced their per-instance `synchronized` modifier with `synchronized (domLock)`; `iterator` still delegates to the locked `getChildren`. |
| `provider/wz/XMLDomMapleData.java:122,136,231` | all three wrapper construction sites pass `domLock` down. |
| `server/ItemInformationProvider.java` (44 fields) | `= new HashMap<>()` -> `= Collections.synchronizedMap(new HashMap<>())`; `itemNameCache` stays a plain `ArrayList` (documented non-goal below). |
| `server/ItemInformationProvider.java` | `descCache` deleted: declared, never read, never written. |
| `soloMapling/ArtificialPlayer/BotTypes/FMBot.java` | removed `int itemId = thisItem.getItemId(); String itemName = convertItemIdToName(itemId);` (both dead) and the then-unused static import. `convertItemIdToName` itself stays - six other callers use it. |
| `src/test/java/provider/wz/XMLDomMapleDataConcurrencyTest.java` | four cases: lock shared by every wrapper of one document (via reflection on `domLock`), lock not shared across documents, parent/prefix navigation unchanged, 16 threads x 2000 rounds of `getChildByPath`/`getChildren`/`getData` seeing the single-reader tree. |

The lock choice (a per-document monitor rather than a lock-free sibling walk) is deliberate: it is
provably correct for every `NodeList` user in the tree, and the same document is only ever read, so
the cost is serialization of WZ reads per file. Contention is bounded by the number of parsed files,
not by the number of bots. The per-document identity is what makes it work: `XMLWZFile`
(`provider/wz/XMLWZFile.java:75`) and `soloMapling/itemPool/EquipMetadataCache.java:255` each parse
their own document, so they do not serialize against each other, while every wrapper of one file does.

## Non-goals (recorded, not fixed here)

- `ItemInformationProvider.itemNameCache` (`protected List<Pair<Integer, String>> itemNameCache = new ArrayList<>();`) is still an unsynchronized list; it is only appended under the caller's own guards and was not part of this race.
- `equipCache` returns mutable `Equip` values; sharing them across threads is unaffected by this ticket.
- The other DOM walker, `soloMapling/itemPool/ItemDataParserXML.java:42,47`, parses its own document and caches under `itemCache.computeIfAbsent`; it is left as it was.
- The unit test pins the mechanism (shared lock) rather than the crash: the race itself is only observable under a booting fleet, so the red evidence is the boot log counts above.

## Verification

1. Repo suite (`/root/SoloMapling`, no `wz/`), `mvn -o test`: `Tests run: 2024, Failures: 1, Errors: 4` -
   exactly the five pre-existing WZ-missing failures (`GCWorldGraphTest.scansWorldAndRoutesAdjacentMaps`,
   `CoarseExecutorTest.factoryPlansARealRoute`, `CoarseExecutorTest.interpolatesARealBakedEdge`,
   `GCMoveBakeTest.bakesHenesys`, `GCMoveBakeTest.bakesEllinia_ladderHeavy`). The 2020-test baseline
   carried the same five; the delta is this ticket's four cases, all green
   (`provider.wz.XMLDomMapleDataConcurrencyTest`). Log: `/tmp/ticket11-evidence/repo-suite.out`.
2. Sandbox suite (`/tmp/solomapling-e2e`, real `wz/` + MySQL), `mvn -o test`: `Tests run: 2024,
   Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` in 242 s. Log:
   `/tmp/ticket11-evidence/sandbox-suite.out`.
3. Harness boot (ticket-08 proof on the sandbox jar, 60 962 202 bytes): `/tmp/botproof_driver.sh`
   exits 0 after 342 s. Run phase: `fNodeListCache` occurrences **0** (every pre-fix boot log had 1),
   `Exception|ERROR` 0, teardown intact - `[BotShutdown] bot activity quiesced (6 schedulers
   cancelled)` then `[BotShutdown] macro wheel drained in 5007ms (residue 19 ticks)`. Verify phase: 0
   and 0, all ticket-08 assertions `PASS`, including the settlement check (`wallet before the
   offers=534424, stored meso=534424`). Logs: `/tmp/ticket11-evidence/botproof_run.out`,
   `botproof_verify.out`, `proof-driver.out`.
4. Cost of the lock. The harness metric is too noisy to resolve it: pre-fix boots on this host spanned
   macro-tick `sparse 2804.15 ms/s (samples 2465)` / `crowded 6778.94 (4957)` at 16:16 and
   `sparse 6707.77 (2525)` / `crowded 7701.41 (4959)` at 10:38, while the post-fix boot reads
   `sparse 5744.77 (2739)` / `crowded 7762.21 (4774)` - inside the pre-fix spread, i.e. the harness
   cannot see a change smaller than roughly 2x. So the seam was measured directly: a throwaway probe
   (`/tmp/wzbench/WZLockBench.java`, real `wz/Item.wz/Etc/0400.img.xml`, one shared root wrapper, best
   of three) run against the sandbox built from `HEAD` sources and then from the fix:

   | Threads | Pre-fix ops/s | Post-fix ops/s |
   | --- | --- | --- |
   | 1 | 1 942 043 | 2 241 417 |
   | 4 | 1 861 035 | 1 522 843 |
   | 16 | 1 765 137 | 1 804 167 |

   Within noise - the single-threaded case is *faster* after the fix, so the measurement floor is
   about 15%. The cache conversion was measured at the JDK level in the same probe:
   `Collections.synchronizedMap.get` 7.5 ns/op against plain `HashMap.get` 3.8 ns/op, i.e. about
   +3.5 ns per provider cache access, which is nothing against a macro tick that costs ~1 ms.
   Caveat, stated plainly: the probe warms the shared node before measuring, so it reports cost, not
   the race (it recorded 0 failures in both builds); the race evidence is the boot-log counts in
   "What actually raced" above and their 0 after the fix.
