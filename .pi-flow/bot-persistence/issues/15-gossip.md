# 15: Bots gossip about players

**What to build:** When two online bots share a map (or run a scripted bot-to-bot conversation), a bot holding
a DIRECT note about a player may pass it on with some chance; the receiver stores it as HEARSAY with
`FromBotId` and `Hops + 1`. No LLM call (copy plus attribution). Stop at 2 hops. The prompt labels hearsay so the
bot says "heard u ..." rather than claiming it was there.

**Blocked by:** 14.

**Status:** done (2026-10-18) — spreading not yet watched on a live server

- [x] A note spreads between co-located bots and stops at 2 hops.
- [x] A bot that heard about you mentions it as hearsay, naming or hinting at its source.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotGossip.java` | once a minute (from the director's tick) bots are grouped by map; up to 20 random teller/listener pairs on the same map, each passing on one of the teller's 5 newest passable notes with chance 0.3 |
| `soloMapling/ArtificialPlayer/BotMemory.java` | `recentPassable` (notes under `MAX_HOPS` = 2), `hear` (kept as HEARSAY one hop further, never twice, never past two hops, never back to the bot that saw it); recall renders hearsay as `heard from <teller's name>: ...` (`someone` when the store cannot say) |
| `soloMapling/ArtificialPlayer/BotChatPrompt.java` | with hearsay in the memories, the prompt tells the bot to present it as something heard, and from whom |
| `soloMapling/ArtificialPlayer/BotPopulationDirector.java` | calls `BotGossip.maybeStep` |
| `src/test/.../BotMemoryTest.java` | +2: hop counting, no duplicates, the two-hop stop, no echo; gossip only between bots on the same map |

## Verification

- Full suite (2026-10-18): `Tests run: 2161, Failures: 1, Errors: 4` - the five known WZ-missing failures only; `BotMemoryTest` 6/6.
- Live, owner's Qwen3.8-9B, Kimchiz holding only `heard from Nayrb: said they're saving up for a pink adventurer
  cape`: `guess what im buying today` -> `lol ur getting the pink adventurer cape, right?`; `anything new?` ->
  `not really just grinding, heard someone's saving for a pink cape : )`; a plain `hey` does not drag it in.
