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

**Status:** open

Probe results (2026-10-18, owner's endpoint `192.168.0.17:8900`, 30-80 ms per call, serialized server):
`kimchiz can i get bless pls` -> buff/Kimchiz; `selling 60% glove att 3m` -> trade/none; `nayrb ur so bad lol` ->
insult/Nayrb; `brb mom says dinner` -> other/none; `ill come` after a bot's party call -> party/that bot (only with an
instruction saying short replies answer the last speaker). Context bleeds: with a recent party call in the state,
`yo nice hat` read as party — keep the state to the line, the nearby names and the last speaker.

- [ ] Criteria text is sanitised: the server rejects reserved tokens (a `)` returned 400), and names/chat are user text.
- [ ] Endpoint unset or failing -> behaviour identical to today's `Dispatcher`.
- [ ] A reply like `ill come` reaches the bot that asked, not "none".
- [ ] A low-confidence match makes the bot ask "u talking to me?" and only a yes / its name continues it.
- [ ] `hey Resme` reaches Ressurectme; `yo sky` reaches xXSkyBladeXx; two equally good fuzzy matches go to the decision model.
- [ ] Router calls are queued off the chat thread; the server handles one request at a time.
