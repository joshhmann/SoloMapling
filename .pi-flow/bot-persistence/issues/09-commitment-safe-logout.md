# 09: Bots never log out mid-commitment

**What to build:** Before the director logs a bot out, check `BotCommitments` (party, open trade, follow,
recruit) and any active conversation. If held, the bot waits until it ends, up to a hard cap; then it says a
goodbye line ("gtg dinner", from a YAML pack) and leaves through `removeBotFromServer`. A partied bot leaves the
party cleanly before logging out.

**Blocked by:** 08.

**Status:** open

- [ ] A bot in a party with a player, a trade, or a conversation is never chosen for immediate logout.
- [ ] After the cap it says goodbye, leaves the party, and logs out; nothing is left half-open.
