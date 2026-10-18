# Ticket 06: What are the boundaries for casino payouts, conjured props and ambient merchants?

Type: grilling
Status: resolved

## Question

Ticket 10's value-path audit found the remaining places where bot value leaves the accounting path:
blackjack payouts are conjured (`BlackjackDealerBot.payWinner`), conjured display items can be credited
into durable holdings, OPQ hand-over duplicates pieces, and a bot-owned HiredMerchant's proceeds are
discarded. Which invariant governs them, what is deliberately out of bounds, and what lands now?

## Answer

**Casino: pay from the pot, never conjure a payout, and a conjured prop is never a value item.**
The three casino policies below are decided; their implementation is deliberately deferred (user call,
2026-10-17: "you know we can actually defer the gambling stuff, its not super importantant atm"). The
analysis stays in ticket 10 (`../issues/10-value-path-audit.md`, findings 1-3)
so the work can be picked up without re-deriving it.

> **Update (2026-10-17):** the deferral is lifted. The three casino policies are implemented as ticket 12
> (`../issues/12-casino-pot-payout.md`), landed in `1dfc85a4589d7451820fbc541302f16fef2d3390` (`Mark
> fabricated bot drops as props and pay casino pots from the house`). The paragraph above and the numbered
> policies below are kept as the record of the decision; read them as history, not as open work.

1. **A winning hand is paid from the collected bets, not from a faucet.** `payWinner`
   (`BlackjackDealerBot.java:560-575`) currently conjures one drop per bet unit through
   `DropCommands.botThrowItemToOwner` -> `BotLogic.generateCleanItem`, while the winner's own stake
   stays on the floor owned by it (`DropCommands.java:145-147` `setPermanentOwner(true)`). For a real
   player that doubles real-value chips on every won hand, and chips cash out at par through the
   lossless sell price (`server/Shop.java:208-210` with `CasinoChipConfig.java:28-31`: 4002000 =
   10 000, 4002001 = 50 000, 4002002 = 250 000, 4002003 = 1 000 000). The fix hands the pot over
   instead of fabricating copies and touches `payWinner`/`lootLoserBets` only; player-facing card
   behaviour (betting window, table seats, chat verbs) is unchanged.
2. **A bot's stake stays a prop.** Keep the conjured 5 x 4031560 display bet
   (`BlackjackDealerBot.java:48-49`, dropped by `DropCommands.botDropItemQtyOwnerOnly` at `:645-646`),
   but conjured drops must be neither creditable nor lootable by bots. A bot seat then moves no value
   at all, its hand is cosmetic, and the dealer's pot loot (`:603` -> `BotHoldings.creditDrop`) only
   ever collects props.
3. **The blackjack bonus is house-funded.** The 1.5x at `BlackjackDealerBot.java:565` comes out of the
   dealer bot's own looted holdings, through a new ledgered item debit beside `BotHoldings.noteMesoSpent`
   (`BotHoldings` has `creditDrop`/`grantItem`/`spendMeso` but no item-spend API). The house pays what it
   holds and mints nothing.

What makes policy 2 and 3 need a mechanism rather than a rule: provenance cannot be decided by item id.
The OPQ pieces are the same ids whether they are real reactor drops (`OPQBot.java:486,:705` loot them,
`MapleMap.java:804` `dropFromReactor`) or fabricated copies (`OPQBot.java:510-511,:732-733`). The mark
must be per drop instance, set where the drop is created (`DropCommands`/`BotLogic.generateCleanItem*`),
with bot loot removing a flagged drop without crediting it.

**Ambient merchants: sunk by design, and the dead write goes.** A bot-owned `HiredMerchant`
(`FreeMarket/ArtificialFreeMarket.java:328`) sells to players, but its proceeds are discarded rather
than banked: `HiredMerchant.botBuy` sends a bot owner into the DB branch
(`owner != null && !isBot(owner)`, `HiredMerchant.java:866`), and `Character.addMerchantMesos`
(`client/Character.java:8929`) writes `characters.MerchantMesos` for a row that does not exist.
Measured in container `solomapling-verify-db`, DB `cosmic_ticket06boot2`: `characters` holds 2 rows,
0 joining `botstore` (0 by `20000 + BotId`, 0 by name) and 0 with `MerchantMesos > 0`, while `botstore`
holds 3488 rows (3481 with `Level > 1`). The only wallet path is `withdrawMerchantMesos()`
(`client/Character.java:8957`), called solely from
`net/server/channel/handlers/PlayerInteractionHandler.java:702,737` and
`client/processor/npc/FredrickProcessor.java:290`. So a bot merchant's income is a sink, not a leak:
ambient merchants stay outside the finite-resource contract (the ADR's undecided boundary), and the
owner-bank branch that produces a no-op `UPDATE` per sale is removed instead of simulated.

**Event instances: recorded, not hardened.** `EventInstanceManager.unregisterPlayer` calls
`dropExclusiveItems` outside its `try` (`EventInstanceManager.java:389-406`, `dropExclusiveItems`
`:940-946`) and the reward APIs start from `Client.getPlayer()`; no caller registers a bot today, so a
fix would be unverifiable. Recorded as a boundary, no code.

## What lands

- `server/maps/HiredMerchant.java`: the bot-owner dead write removed from `botBuy` and `buy` (the
  `addMerchantMesos` bank that targets a non-existent row).
- Ticket 10 gains a closing-actions line pointing at this record.
- Casino findings 1-3: **implemented** as ticket 12 (`../issues/12-casino-pot-payout.md`, commit
  `1dfc85a4589d7451820fbc541302f16fef2d3390`) —
  the deferral recorded in the Answer above is lifted. Payouts now come from the pot (a winning hand is paid
  out of the collected bets, capped by the house's stock), a conjured prop is never a value item (per
  drop-instance provenance flag, not an id list), and the 1.5x blackjack bonus is house-funded from the
  dealer's own ledgered holdings.
- `PlayerShop`'s bot-owner credit (already fixed in ticket 10) stays as the contrast: a stall's sale
  proceeds *are* value the bot receives, so they go through `BotHoldings.noteMesoEarned`.
