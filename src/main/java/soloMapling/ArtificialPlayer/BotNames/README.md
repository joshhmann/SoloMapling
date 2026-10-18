# Bot name lists

Every `*.txt` file in this folder joins the bot name pool (`BotNames`), alongside
`../../FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt`.

- One name per line; a line starting with `#` is a comment.
- Only names the client accepts are used: 3-12 letters or digits (`[A-Za-z0-9]{3,12}`).
  Anything else is skipped, not shortened.
- Duplicates are dropped ignoring case. A name a real player's character holds is never given to a bot.
- When the pool runs out, era-style variants of pool names are generated (`name23`, `xXnameXx`, `NameOther`, ...).
