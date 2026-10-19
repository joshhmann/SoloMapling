# 14: Bots keep short notes about past chats

**What to build:** Table `botmemory (Id, BotId, AboutCharacterId, Note, Source DIRECT|HEARSAY, FromBotId, Hops,
CreatedAt, LastUsedAt)` (migration 027). When a conversation ends (`SocialBot.doGoodbye`,
`checkConversationTimeout`, `resetConversation`), `BotBrain.summarize` writes at most 2 notes of <=140 chars.
Cap ~10 notes per (bot, player); when full, merge the oldest into one. The newest/most-used notes go into the
reply prompt, and `onFirstInteraction` may open with one ("didnt u say u were going to orbis").

**Blocked by:** 13.

**Status:** done (2026-10-18) — not yet exercised in game; the opening line from memory is not built (see below)

- [x] Chat, restart, chat again: the bot references the earlier conversation.
- [x] Note caps and merging hold under a long chat history.
- [x] With the scripted fallback, no notes are invented.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `db/tables/027-botmemory.sql`, changeSet 27 | `botmemory` (Id, BotId, AboutCharacterId, Note, Source, FromBotId, Hops, CreatedAt, LastUsedAt), index on (BotId, AboutCharacterId) |
| `soloMapling/ArtificialPlayer/BotMemory.java` | per-pair notes cached after the first read; `recall` (direct before hearsay, newest first, 5, stamped as used); `remember`; past 10 notes the oldest 4 are condensed into one by the brain, or dropped when it is down; `writeConversationNotes` asks the brain only when the bot actually spoke through it |
| `soloMapling/ArtificialPlayer/BotBrainSystem/BotBrain.java`, `OpenAICompatibleBrain.java` | `notes(...)`: a second call shape - no per-player limit, 30 s budget, 120 tokens, optional `BOT_LLM_NOTES_MODEL` (a bigger model for notes only); the scripted brain returns nothing |
| `soloMapling/ArtificialPlayer/BotBrainSystem/ReplyCleaner.java` | `notes`: at most 2 lines of 140 chars, bullets stripped, `NONE` means nothing |
| `soloMapling/ArtificialPlayer/BotTypes/SocialBot.java` | the prompt is built off the tick thread with the pair's recalled notes; `resetConversation` hands the transcript to `writeConversationNotes` before clearing it |
| `config/ServerConfig.java`, `config.yaml` | `BOT_LLM_NOTES_MODEL` |
| tests | `BotMemoryTest` (4: notes survive a cache flush and come back direct-first newest-first, pairs are separate, a full pair drops its oldest four without a brain, the scripted brain writes nothing, the notes prompt), `ReplyCleanerTest` +1 |

Not built: opening a conversation with a memory before the player says anything. The memory is in the prompt from
the first reply on, which in the live check was enough for the bot to bring it up by itself.

## Verification

- Full suite (2026-10-18): `Tests run: 2142, Failures: 1, Errors: 4` - the five known WZ-missing failures only.
- Migration 027 applied to a scratch MySQL schema; insert + `bot_about` index confirmed.
- Live, owner's Qwen3.8-9B: day-1 chat ("saving up 5m for a pink adventurer cape", "ludi pq tonight with my guild")
  -> notes in 636 ms: `said they're saving up for a pink adventurer cape.` / `also planning to do Ludi PQ tonight with
  their guild.`; day 2 with those notes: `guess what i finally got it` -> `wowww!!!! tell meeeeeee, did u finally get ur
  pink adventurer cape!!?!`; a hi/bye chat -> no notes written.
