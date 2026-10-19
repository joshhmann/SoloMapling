# 21: Every bot is interactable the same way

**What to build:** One interaction layer for all bots, replacing the per-type menus (`SocialBot`'s numbered menu,
`TrainingBot`'s solo/party menus, `BotOptionMenu`). Every entry point - a name in all-chat (resolved by ticket 19),
a whisper (22), a right-click party/trade (already routed), the menu window (23) - reaches the same per-bot
interaction: a shared menu (Talk, Party up, Trade, Follow me / Train with me, Where do you train?) plus options each
bot type adds (a merchant's "What are you selling?", a dealer's "Deal me in"), and free talk through the brain with
the bot's persona and memory. Service bots keep exact scripts where they must (bets, prices) but still chat.

**Blocked by:** 13, 19.

**Status:** open

- [ ] One interface every bot type implements for its extra options; the shared options work on every type.
- [ ] Each entry point reaches the same interaction state (a whisper and an all-chat line to the same bot are one conversation).
- [ ] The old per-type menus are gone or delegate to the layer; no bot type is unreachable.
