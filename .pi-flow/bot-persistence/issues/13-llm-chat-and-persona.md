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

**Status:** open

- [ ] Talking to a population bot gets an LLM reply when the endpoint is up, a scripted one when it is not.
- [ ] Replies fit the chat box and arrive after a human-ish delay.
- [ ] Staff bots and bot-to-bot chatter make no LLM calls.
