# 19: Route player chat through the decision model before the LLM

**What to build:** A `ChatRouter` that, for each player chat line on a map with bots, asks the owner's
GLiNER2.5-Decide endpoint (`POST {decide_url}/v1/systemone`, payload `{"model":"decide","state":...,"questions":{...}}`)
two choice questions in one call: **who** the line is aimed at (each nearby bot, the bot that spoke last, or none) and
**what** it is (question, smalltalk, trade, party, buff, insult, other). Then route: buff -> `BotBuffRequestHandler`,
party -> the party queue, trade -> the trade logic, question/smalltalk/insult -> ticket 13's LLM reply, other/none ->
nothing. Today's name-contains matching in `Dispatcher` (`BotMessagingSystem/Dispatcher.java:69-81`) stays as the
fallback when the endpoint is unset, down, slow (timeout ~300 ms) or under a confidence floor.

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
- [ ] Router calls are queued off the chat thread; the server handles one request at a time.
