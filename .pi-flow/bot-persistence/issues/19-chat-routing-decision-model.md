# 19: Route player chat through the decision model before the LLM

**What to build:** A `ChatRouter` that, for each player chat line on a map with bots, asks the owner's
GLiNER2.5-Decide endpoint (`POST {decide_url}/v1/systemone`, payload `{"model":"decide","state":...,"questions":{...}}`)
two choice questions in one call: **who** the line is aimed at (each nearby bot, the bot that spoke last, or none) and
**what** it is (question, smalltalk, trade, party, buff, insult, other). Then route: buff -> `BotBuffRequestHandler`,
party -> the party queue, trade -> the trade logic, question/smalltalk/insult -> ticket 13's LLM reply, other/none ->
nothing. Today's name-contains matching in `Dispatcher` (`BotMessagingSystem/Dispatcher.java:69-81`) stays as the
fallback when the endpoint is unset, down, slow (timeout ~300 ms) or under a confidence floor.

**Addressee resolution, in layers (owner, 2026-10-18: "hey you Resme when their name is Ressurectme"):**
1. exact (today's `contains`); 2. a free fuzzy pass in code over the bots on the map - case-insensitive, strip
`xX..Xx` and digits, prefix of 3+ letters, the typed word as an in-order subsequence of the name ("resme" ->
Ressurectme), edit distance <= 2 for names over 5 letters; 3. the decision model for what is left ("can i get a bless"
-> the cleric, "ill come" -> the last speaker, "u with the bow"); 4. none. Ties at layer 2 go to layer 3. A match below a confidence bar does not start a conversation outright: the bot asks
first, in its persona ("u talking to me?" / "me?"), and a yes-ish reply (or its name) confirms it, so a misheard
nickname costs one cute line instead of a hijacked conversation. Qwen is never
used for routing (too slow per line). The resolver is shared by every entry point of ticket 21.

**Blocked by:** 02.

**Status:** done (2026-10-18) — addressee only; routing by intent (trade/party) moved to ticket 21; not yet tried in game

Probe results (2026-10-18, owner's endpoint `192.168.0.17:8900`, 30-80 ms per call, serialized server):
`kimchiz can i get bless pls` -> buff/Kimchiz; `selling 60% glove att 3m` -> trade/none; `nayrb ur so bad lol` ->
insult/Nayrb; `brb mom says dinner` -> other/none; `ill come` after a bot's party call -> party/that bot (only with an
instruction saying short replies answer the last speaker). Context bleeds: with a recent party call in the state,
`yo nice hat` read as party — keep the state to the line, the nearby names and the last speaker.

- [x] Criteria text is sanitised: the server rejects reserved tokens (a `)` returned 400), and names/chat are user text.
- [x] Endpoint unset or failing -> behaviour identical to today's `Dispatcher`.
- [x] A reply like `ill come` reaches the bot that asked, not "none".
- [x] A low-confidence match makes the bot ask "u talking to me?" and only a yes / its name continues it.
- [x] `hey Resme` reaches Ressurectme; `yo sky` reaches xXSkyBladeXx; two equally good fuzzy matches go to the decision model.
- [x] Router calls are queued off the chat thread; the server handles one request at a time.

## What was built (as landed)

The layers in front of the old `Dispatcher` path (exact `contains(name)` and lines of a conversation under way are
untouched):

| Anchor | Change |
| --- | --- |
| `BotMessagingSystem/BotAddressee.java` | layer 2, pure: per word of the line, against each bot nearby - exact (any case) 1.0, the name's core without `xX..Xx`/trailing digits 0.95, a prefix of half the core 0.88, one typo 0.86, the word's letters in order from the name's first letter ("resme" -> Ressurectme) 0.80, a 3-letter prefix ("sky") 0.76, two typos 0.72; common chat words never match ("lol" is not Lolipop); sure >= 0.85 with an 0.08 margin, ask >= 0.68, else ambiguous |
| `BotMessagingSystem/BotChatRouting.java` | the last bot line per map (45 s); "u talking to me?" (5 wordings) with a 20 s pending answer that a yes or the bot's name confirms; layer 3a rule - a short acceptance ("ill come", "count me in", "inv me") right after a bot asked or recruited answers that bot, chatter ("brb", "lol", selling) does not; layer 3b - for request-shaped lines with no name, the decision model names the kind of player (cleric/magician/bowman/thief/warrior/pirate) and the one bot of that kind nearby asks first; trade talk never triggers it; criteria/state text sanitised |
| `BotMessagingSystem/Dispatcher.java` | `routeWithoutExactName`: pending confirmation, then (if not in a conversation) buff requests left to `BotBuffRequestHandler`, layer 2, layer 3a, layer 3b off the dispatcher thread |
| `BotCommandsPack/SocialCommands.java` | `BotSpeak` records the last speaker |
| `BotBuffRequestSystem/BotBuffRequestHandler.java` | `isBuffRequest` so routing steps aside for "hs pls" |
| `config/ServerConfig.java`, `config.yaml` | `BOT_DECIDE_URL` (blank = names and rules only), `BOT_DECIDE_TIMEOUT_MS`, `BOT_DECIDE_ASK` (0.55) |
| tests | `BotAddresseeTest` 4 (nicknames, typos, "sky" asks, chatter reaches nobody, ties), `BotChatRoutingTest` 5 (confirmations incl. expiry and once-only, acceptance rule vs chatter, buff/trade lines, job families, request cleaning and parsing) |

## Findings: what the decision model can and cannot do here

Probed with the real request against GLiNER2.5-Decide (`192.168.0.17:8900`, 72-85 ms a call):
- **Pick one of four nearby players + none: 0/8.** With opaque keys it chose Ressurectme for every line (0.70-0.91);
  with names as keys it chose the first name, or "none", for every line. Dropped.
- **Is this line an answer to the last speaker:** got "ill come"/"sure count me in" right but also took "lol" and
  "brb mom says dinner" for answers. Replaced by the 3a rule.
- **Which kind of player a request wants:** "u with the bow" bowman 0.70, "the thief" 0.78, "a mage" 0.62, "any
  warriors" 0.71, "how do i get to orbis"/"wanna party" -> any; weak on heals ("heal" any 0.39, "buff" 0.35) - which
  the existing buff handler answers anyway; "anyone selling claws?" -> thief 0.63, so trade talk is excluded first.
  Kept as 3b, and only ever makes a bot ask.

## Verification

- Full suite (2026-10-18): `Tests run: 2151, Failures: 1, Errors: 4` - the five known WZ-missing failures only.
- Not yet: a player in game typing "hey resme" and getting "u talking to me?".
