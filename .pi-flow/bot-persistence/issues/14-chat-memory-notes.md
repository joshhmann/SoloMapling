# 14: Bots keep short notes about past chats

**What to build:** Table `botmemory (Id, BotId, AboutCharacterId, Note, Source DIRECT|HEARSAY, FromBotId, Hops,
CreatedAt, LastUsedAt)` (migration 027). When a conversation ends (`SocialBot.doGoodbye`,
`checkConversationTimeout`, `resetConversation`), `BotBrain.summarize` writes at most 2 notes of <=140 chars.
Cap ~10 notes per (bot, player); when full, merge the oldest into one. The newest/most-used notes go into the
reply prompt, and `onFirstInteraction` may open with one ("didnt u say u were going to orbis").

**Blocked by:** 13.

**Status:** open

- [ ] Chat, restart, chat again: the bot references the earlier conversation.
- [ ] Note caps and merging hold under a long chat history.
- [ ] With the scripted fallback, no notes are invented.
