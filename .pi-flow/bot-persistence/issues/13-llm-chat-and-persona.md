# 13: Player-directed chat goes through the LLM, in character

**What to build:** Route player-directed chat (the `Dispatcher`'s addressed bot / current respondant) for
roster `SocialBot`s and other population bots through `BotBrain.reply`; staff bots and ambient bot-to-bot
chatter stay scripted. The prompt is the bot's `Persona` (typing style, vibe, interests — generated once with
the personality), its live state via `DialogueContextResolver` (level, job, map, activity), and the last few
lines of the conversation. It plays a real person playing MapleStory: short lowercase chat, era slang, never
mentions being an AI. Replies are clamped to chat length and sent after a short typing delay.
The persona prompt carries the bot's name, level, job, map and era ("it's 2006"); a smoke test against the
reference endpoint with exactly that produced `got it at the quest shop in henesys lol`, while a generic
"you are a player" prompt produced capitalised, punctuated lines — so the style rules live in the prompt, with a
light post-pass (lowercase, drop a trailing period) only as a backstop. The reference model is uncensored, so the
prompt keeps the bot on in-game topics and an optional word blocklist in config filters replies.

**Blocked by:** 02, 04, 12.

**Status:** in progress (2026-10-18) — SocialBot wired and tried live on the owner's Qwen; other bot types move to ticket 21

- [x] Talking to a population bot gets an LLM reply when the endpoint is up, a scripted one when it is not.
- [x] Replies fit the chat box and arrive after a human-ish delay.
- [x] Staff bots and bot-to-bot chatter make no LLM calls.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotPersona.java` | a persona per bot from its seed: lowercase / short forms / laugh, a vibe (9), two interests (11); stored in the roster `Persona` column at first login; `describe` turns it plus chattiness into the style half of the prompt |
| `soloMapling/ArtificialPlayer/BotChatPrompt.java` | the system prompt: name, level, job, map, activity, persona, who it is talking to, memories (ticket 14 fills them), short GMS v83 world facts (towns, cab, Orbis ship, job advance places, PQ bands), and the rules (stay a player, never an AI, admit not knowing, one line under 60 characters) |
| `soloMapling/ArtificialPlayer/BotTypes/SocialBot.java` | free-form lines (no menu number or keyword) go to the brain with the last 8 turns; the reply is typed at ~55 ms/char minus the brain's time, gated on the conversation still being with that player; no reply -> today's scripted `WhatsUp` line; history clears with the conversation; the conversation timeout is 60 s instead of 35 s when the brain is live |
| `soloMapling/ArtificialPlayer/BotRoster.java` | `withPersona`, `personaOrNull`; generated at login |
| `soloMapling/ArtificialPlayer/BotBrainSystem/ReplyCleaner.java` | an over-long line is cut at its last sentence break before the 70-char box, else at a word |
| `config/ServerConfig.java`, `config.yaml` | `BOT_LLM_MAX_TOKENS` default 40 |
| `src/test/.../BotChatPromptTest.java` | persona stable + column round trip + length by chattiness; the prompt carries who/where/partner/memories/facts/rules; message order |

Only roster and town `SocialBot`s talk through the brain here; trainers, wanderers and service bots get it through
the shared interaction layer (ticket 21).

## Verification

Live, owner's llama.cpp Qwen3.8-9B (thinking off), 3 personas x 5 lines, the real `BotChatPrompt`:
- before the facts/length fixes: Orbis right; Kerning ("ship to Orbis") and 2nd job ("El Nath") wrong; lines cut at
  70 characters mid-sentence;
- after: `how do i get to orbis` -> `just take the ship from ellinia to orbis!`; 2 of 3 send a magician to his instructor
  in Ellinia (the third assumed its own class); `are you a bot` -> `lol no im real just tired of grinding`; replies
  316-940 ms, inside the chat box. Remaining: occasional invented detail ("walk up the hill from henesys").
- Not yet: a conversation with a SocialBot in game (needs a player on the real server with `BOT_LLM_BASE_URL` set).
