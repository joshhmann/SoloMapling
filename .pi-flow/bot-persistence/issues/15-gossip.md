# 15: Bots gossip about players

**What to build:** When two online bots share a map (or run a scripted bot-to-bot conversation), a bot holding
a DIRECT note about a player may pass it on with some chance; the receiver stores it as HEARSAY with
`FromBotId` and `Hops + 1`. No LLM call (copy plus attribution). Stop at 2 hops. The prompt labels hearsay so the
bot says "heard u ..." rather than claiming it was there.

**Blocked by:** 14.

**Status:** open

- [ ] A note spreads between co-located bots and stops at 2 hops.
- [ ] A bot that heard about you mentions it as hearsay, naming or hinting at its source.
