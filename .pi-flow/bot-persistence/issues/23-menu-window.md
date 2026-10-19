# 23: Clickable bot menus in the NPC dialog window

**What to build:** Show the shared bot menu (ticket 21) as the client's NPC talk window with clickable
`#L0#...#l` options instead of numbered chat lines, through the existing NPC conversation packets (no client
change). Put it behind a `BotMenuView` interface so a custom client DLL can later replace the window (bot avatar,
talk button) without touching bot logic; the DLL side would add custom opcodes handled next to
`PartyOperationHandler`/`WhisperHandler`.

**Blocked by:** 21.

**Status:** open

- [ ] Typing a bot's name (or a GM command) opens the window with the bot's options; clicking one runs it.
- [ ] Closing the window or walking away ends the interaction cleanly.
- [ ] The numbered-chat menu remains as a fallback (config switch).
