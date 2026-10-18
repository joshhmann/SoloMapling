# 02: SocialBot drops chat lines meant for other bots

**What to build:** Confirm and fix `SocialBot.processMessages` (`soloMapling/ArtificialPlayer/BotTypes/SocialBot.java:268-284`),
which polls the shared "secondary" queue and appears to discard a message whose sender is not this bot's
respondant (:274-275). A line meant for bot B must reach bot B even when bot A polls first. Ticket 13 (LLM
chat) routes through this path, so it has to be lossless first.

**Blocked by:** none.

**Status:** done (2026-10-18)

- [x] A failing test first: two SocialBots each in a conversation; a line from B's player is not lost when A polls.
- [x] Fix (per-bot inbox, or re-queue lines that are not for this bot) and the test goes green.

## What was built (as landed)

The defect was wider than SocialBot. One global `secondary` queue feeds every bot in a conversation, and each
live consumer took the head and then dropped it if it was not from its own player: `SocialBot`, `DiceBot`,
`GameZoneHostBot`, `TutorialBot` (`BlackjackDealerBot` re-queued instead, so it reordered but did not lose).
Separately, `Dispatcher` moved one `primary` line per 2 s pass while `QueueCleaner` expires lines after 10 s, so
a burst of more than five lines lost its tail before any bot saw it.

| Anchor | Change |
| --- | --- |
| `BotMessagingSystem/MessageQueue.java` | `pollFor(queue, predicate, timeout)` takes the first message the bot wants and leaves the rest; `claim` is the atomic remove (two bots seeing one line: only one remove succeeds) |
| `BotTypes/SocialBot.java` | polls only its respondant's (non-bot) lines |
| `BotTypes/DiceBot.java`, `GameZoneHostBot.java`, `TutorialBot.java` | poll with `getInteractors()::isMessageFromRespondant` (their handlers' own check, moved before the take) |
| `BotMessagingSystem/Dispatcher.java` | drains every waiting `primary` line per pass |
| `src/test/.../BotMessagingSystem/MessageQueueClaimTest.java` | 4 tests: own line taken with another player's at the head, nothing wanted leaves the queue alone, 200 rounds x 4 threads -> exactly one winner, `pollFor` waits and times out |

Not changed: `BlackjackDealerBot` (re-queue already avoids loss; out-of-turn lines are dropped on purpose).
Dead consumers that take and discard (`HenesysBot`, `GachaBot`, `FMBot`, `SellingMerchantBot`,
`BuyingMerchantBot`, `NXMerchantBot`, `ScrollingBot`, `BotSM.processMessages`) have no caller today; if one is ever
wired into a tick it must use `pollFor` too.

Red-first note: the regression test pins `claim` directly (the head-of-queue case a plain poll gets wrong); it was
not run against the old consumer code, which had no seam to call.

## Verification

- `MessageQueueClaimTest`: 4/4 (2026-10-18). Full suite: see ticket 03's run (same tree).
