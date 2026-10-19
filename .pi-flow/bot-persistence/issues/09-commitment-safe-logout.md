# 09: Bots never log out mid-commitment

**What to build:** Before the director logs a bot out, check `BotCommitments` (party, open trade, follow,
recruit) and any active conversation. If held, the bot waits until it ends, up to a hard cap; then it says a
goodbye line ("gtg dinner", from a YAML pack) and leaves through `removeBotFromServer`. A partied bot leaves the
party cleanly before logging out.

**Blocked by:** 08.

**Status:** done (2026-10-18) — the goodbye/leave flow is not yet watched on a live server

- [x] A bot in a party with a player, a trade, or a conversation is never chosen for immediate logout.
- [x] After the cap it says goodbye, leaves the party, and logs out; nothing is left half-open.

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotLogout.java` | a logout is a request: a free bot leaves now; a bot holding a commitment (`BotPopulationDirector.mayLogOut` false) waits, re-checked every director tick, up to 15 min; leaving = a goodbye line when a real player can see it (party line when partied with one), a 1.5-3 s beat so it is read, leave the party (all-bot parties too, so no ghost member stays), at the cap close any trade window (both sides refunded), then `removeBotFromServer`; a guard stops a second request inside the beat from logging the bot out twice |
| `soloMapling/ArtificialPlayer/BotDialoguePack/LogoutDialogue.yaml` | `Alone` and `Party` goodbye lines in era chat style |
| `soloMapling/ArtificialPlayer/BotPopulationDirector.java` | plan logouts go through `BotLogout.request`; an online member whose session is done but which holds a commitment is queued to leave; `BotLogout.process` each tick; `logOut` (GM) is a request too; `describe` shows how many are waiting |
| `src/test/.../BotLogoutTest.java` | party-with-player detection, the goodbye packs load and fit the chat box |

The commitment rule itself is ticket 08's `mayLogOut`: no open trade or armed invite (`BotCommitments.inFlight`),
no SocialBot conversation, no party with a real player, not a `FollowerBot`. `BotPopulationDirectorTest` already
shows pinned bots are never chosen by the planner.

## Verification

- `BotLogoutTest` 2/2, `BotPopulationDirectorTest` 5/5.
- Not yet seen live: a bot actually saying bye and leaving a player's party; that needs a player in game
  (`!roster logout <name>` on a partied bot shows it).
