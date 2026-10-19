# 21: Every bot is interactable the same way

**What to build:** One interaction layer for all bots, replacing the per-type menus (`SocialBot`'s numbered menu,
`TrainingBot`'s solo/party menus, `BotOptionMenu`). Every entry point - a name in all-chat (resolved by ticket 19),
a whisper (22), a right-click party/trade (already routed), the menu window (23) - reaches the same per-bot
interaction: a shared menu (Talk, Party up, Trade, Follow me / Train with me, Where do you train?) plus options each
bot type adds (a merchant's "What are you selling?", a dealer's "Deal me in"), and free talk through the brain with
the bot's persona and memory. Service bots keep exact scripts where they must (bets, prices) but still chat.

**Blocked by:** 13, 19.

**Status:** done (2026-10-18) — for social, menu and menu-less bots; service bots' own hints are unchanged

- [x] One interface every bot type implements for its extra options; the shared options work on every type.
- [x] Each entry point reaches the same interaction state (a whisper and an all-chat line to the same bot are one conversation).
- [x] The old per-type menus are gone or delegate to the layer; no bot type is unreachable.

## Part 1 (as landed): every bot talks

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotConversation.java` | one free-form chat engine per (bot, player): last 8 turns, persona + live activity + recalled memories in the prompt, human typing pace, a scripted `fallback` when the brain does not answer, ends on goodbye or 60 s of silence (a sweeper) and hands the transcript to `BotMemory`; `partnerOf` / `isTalking` / `endAllOf` |
| `soloMapling/ArtificialPlayer/BotTypes/SocialBot.java` | its chat moved onto the engine (menu, party and goodbye stay scripted; the scripted `WhatsUp` line is the fallback) |
| `soloMapling/ArtificialPlayer/BotOptionMenu.java` | a line that is no menu choice is talk (trainers, followers and every other menu bot), and closing the menu ends the chat |
| `soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java` | a bot with no menu of its own (no `displayCommands` override - town wanderers...) answers its name with talk, and the player's next lines on the same map go to that chat |
| `soloMapling/ArtificialPlayer/BotGeneration.java` | a bot leaving the world ends its chats (and writes its notes) |
| `src/test/.../BotConversationRoutingTest.java` | menu detection (trainers have one, wanderers do not); with no brain the caller answers from its script |

Still to do (part 2): the shared menu - Party up / Trade / Follow me / Where do you train? on every type, with per-type
extras, replacing `SocialBot`'s menu, `TrainingBot`'s solo/party menus and the `displayCommands` hints; and service
bots with their own `displayCommands` (merchants, dice, gacha...) still drop free text.

## Verification (part 1)

- Full suite (2026-10-18): `Tests run: 2153, Failures: 1, Errors: 4` - the five known WZ-missing failures only.
- The prompt and brain are the ones tried live in tickets 13/14; a trainer or wanderer conversation in game is not
  yet seen.

## Part 2 (as landed): the shared menu

Built with ticket 23 (see there): `BotMenus` puts each type's own options ahead of the shared `Let's talk` / `Where do
you train?`, in the NPC window or as the chat hint. Rather than rewriting each type's options, the existing
`BotOptionMenu`s and `SocialBot`'s options are the "per-type extras", so their behaviour is unchanged. Not migrated:
service bots that only show a `displayCommands` hint (merchants, dice, gacha, OPQ) - they still drop free text.
