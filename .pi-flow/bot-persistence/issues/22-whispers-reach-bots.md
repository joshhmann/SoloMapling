# 22: Whispers reach bots

**What to build:** `WhisperHandler` (`net/server/channel/handlers/WhisperHandler.java:51`) finds an online bot by name
but its text never reaches bot logic. Route a whisper to a bot into its interaction (ticket 21) as a private
conversation answered by whisper through the brain. A whisper to a name that only fuzzily matches a nearby bot
(ticket 19 layer 2) answers "did you mean <name>?" instead of "not found".

**Blocked by:** 13, 21.

**Status:** open

- [ ] Whispering a bot gets an in-character whisper back (LLM), or a scripted one when the brain is down.
- [ ] Whisper conversations share history and memory with all-chat ones.
