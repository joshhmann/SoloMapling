# 23: Clickable bot menus in the NPC dialog window

**What to build:** Show the shared bot menu (ticket 21) as the client's NPC talk window with clickable
`#L0#...#l` options instead of numbered chat lines, through the existing NPC conversation packets (no client
change). Put it behind a `BotMenuView` interface so a custom client DLL can later replace the window (bot avatar,
talk button) without touching bot logic; the DLL side would add custom opcodes handled next to
`PartyOperationHandler`/`WhisperHandler`.

**Blocked by:** 21.

**Status:** done (2026-10-18) — the script runs in Cosmic's Graal engine; not yet seen in a real client

- [x] Typing a bot's name (or a GM command) opens the window with the bot's options; clicking one runs it.
- [x] Closing the window or walking away ends the interaction cleanly.
- [x] The numbered-chat menu remains as a fallback (config switch).

## What was built (as landed) - with ticket 21 part 2

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotMenus.java` | one menu per bot: the type's own options first, then the shared `Let's talk` and `Where do you train?` (both answered by the bot's brain); a `View` interface (the NPC window by default) so a custom client can replace it; `text` / `select` / `closed` for the script; the window only opens when the player has no NPC window open and is past the NPC click cooldown (`NPCScriptManager.start` would otherwise close their open window, or show nothing and report success) |
| `scripts/npc/botmenu.js` | `sendSimple` with the menu, the click back to `BotMenus.select`, dispose |
| `soloMapling/ArtificialPlayer/BotOptionMenu.java` | `show` opens the window (trainers', followers' menus), else the old chat hint |
| `soloMapling/ArtificialPlayer/BotTypes/SocialBot.java` | its five options open in the window; a click is the same as typing the number |
| `soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java` | a menu-less bot (town wanderer) named alone opens the shared menu |
| `config/ServerConfig.java`, `config.yaml` | `BOT_MENU_WINDOW` (true) |
| tests | `BotMenusTest` (4: order of options, a type option runs with its index, a menu is used once, shared options never reach the type, a view that cannot show falls back, the script calls the API) and `scripting.BotMenuScriptTest` (botmenu.js evaluated by Cosmic's own `AbstractScriptManager` engine: `start` shows the menu, `action` delivers the click and disposes) |

The window's face is NPC 9010000 (Maple Administrator), a portrait every v83 client has; the bot's name heads the
text. Service bots with their own `displayCommands` hints (merchants, dice, gacha, OPQ) are unchanged.
