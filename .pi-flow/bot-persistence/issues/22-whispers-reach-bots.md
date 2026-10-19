# 22: Whispers reach bots

**What to build:** `WhisperHandler` (`net/server/channel/handlers/WhisperHandler.java:51`) finds an online bot by name
but its text never reaches bot logic. Route a whisper to a bot into its interaction (ticket 21) as a private
conversation answered by whisper through the brain. A whisper to a name that only fuzzily matches a nearby bot
(ticket 19 layer 2) answers "did you mean <name>?" instead of "not found".

**Blocked by:** 13, 21.

**Status:** done (2026-10-18) — not yet tried with a real client

- [x] Whispering a bot gets an in-character whisper back (LLM), or a scripted one when the brain is down.
- [x] Whisper conversations share history and memory with all-chat ones.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `net/server/channel/handlers/WhisperHandler.java` | a whisper to a bot goes to `BotConversation.say` and is answered by whisper (`getWhisperReceive` from the bot, on the player's channel); with no brain the bot is a busy player (a short "busy" whisper one time in three, else nothing); a whisper to a name nobody holds, when a bot on the player's map is a close fuzzy match (ticket 19's `BotAddressee`), adds "Did you mean <name>?" |
| `soloMapling/ArtificialPlayer/BotConversation.java` | `say(..., deliver)`: the reply goes through a delivery (whisper) instead of map chat; one session, history and memory whichever way the player talks; the bot only turns to face the player in map chat on the same map |

Bots are anywhere in the world, so whispering reaches a bot on another map, as with a player.

## Verification

- Full suite (2026-10-18): `Tests run: 2153, Failures: 1, Errors: 4` - the five known WZ-missing failures only.
- No unit test drives the packet path (it needs a client); not yet tried in game.
