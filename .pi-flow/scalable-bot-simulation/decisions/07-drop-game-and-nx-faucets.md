# Ticket 07: A bot-run minigame takes real value from a player and hands back something the bot never held — which cases are a defect, and which are a deliberate service?

Type: grilling
Status: resolved (decision agreed; implementation deferred)

## Question

Ticket 10's value-path audit and the ticket-12 provenance work closed the drop and casino paths. Two faucets
remain where a bot-run interaction takes real value from a player and hands back something the bot never held:
the drop-game prize and the NX gift codes. Which of these are defects and which are deliberate service, and
what rule makes them decidable? The user has deferred the gambling-side work ("you know we can actually defer
the gambling stuff, its not super importantant atm"), so this record settles the rule and the classification
but leaves the code unchanged.

## Answer

**Value a bot hands out must come from its own holdings.** A fabricated faucet is acceptable only where the
receiving player is charged nothing — pure pantomime — because then no real value enters or leaves the world.
Any faucet that answers a real player payment (real mesos, an item, a timed action) with an item the bot
itself never held is a defect: the player pays real value and receives conjured value, so the hand-over is a
net faucet into the player's inventory. Against that rule the two candidate cases below are defects, and the
pantomime cases and the deliberately-minted handout are not.

### Defect: `DropGameBot`'s paid prize

The player pays real mesos into a trade, and that meso genuinely leaves the player and lands in the bot's
ledger:

- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/DropGameBot.java:34-35` defines the tiers,
  `MEDIUM_TIER_COST = 10_000_000` and `ELITE_TIER_COST = 50_000_000`.
- `DropGameBot.java:308` reads the player's stake with
  `int offeredMesos = BotTradeCommands.readPartnerMeso(getChr())`, and `:311`/`:313` select the tier by exact
  match against those two constants.
- The player's meso is ledgered on the bot's side by the ordinary trade path: `server/Trade.java:123`
  (`completeTrade`) -> `:142` `creditReceivedMeso(chr, exchangeMeso - fee)` -> `:240` `creditReceivedMeso`,
  which calls `BotHoldings.grantMeso` — net of the trade fee (`getFee`). So the bot *does* receive the stake.

The prize, however, is fabricated in place with no debit anywhere on the prize path:

- `DropGameBot.java:540` `DropCommands.botDropItemWithExpiry(getChr(), itemId, isEquip, despawnMs)` mints the
  item through `BotLogic.generateCleanItem` / `generateCleanItemEquip` (the `DropCommands` rule: fabricated =
  a prop). The bot gains nothing and pays nothing; the player paid real meso and collects a conjured item.

Also recorded (narration that does not match the code): the "Loot pool error. Refunding and resetting." line at
`DropGameBot.java:343` and the forfeit narration at `:399` ("mesos forfeited") describe refunds that no code
performs. Those paths call `cleanupTradeAndReset()` (`:344`, `:587`) and `forfeitAndReset()` (`:372`, `:606`),
and the shared exit `BotSM.cleanupTradeState()`
(`src/main/java/soloMapling/ArtificialPlayer/BotSM.java:520-528`) only tears down the trade state — it moves no
value. The loot pool is a server-side YAML table
(`src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/DropGameLootPool.yaml`, read by
`DropGameLootPool.load`, `src/main/java/soloMapling/ArtificialPlayer/BotTypes/DropGameLootPool.java`), not
something the bot holds, so nothing draws the prize from a stock.

### Defect: the NX gift-card mints

- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/NXMerchantBot.java:96-97`: after a successful sale at
  50 000 000 mesos (`getTradeWants().setMesoWanted(fiftyMill)`, `:62-63`) whose meso side is ledgered exactly as
  above, the bot runs `generateGiftCardCode()` then `createCompleteNXCode(nxCode)`.
  Those go straight to `soloMapling/server/NXCodeManager.java` (`createCompleteNXCode(String, int)` `:85`,
  `createCompleteNXCode(String)` `:100` defaulting to 10 000, `generateGiftCardCode()` `:138`), which INSERT a
  gift-card row into the DB. There is no debit and no ledger entry, so the NX is conjured onto the player.
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TutorialBot.java:357-361` mints two 25 000 NX codes the
  same way as part of a free admin grant (no player payment at all).

These two are the only bot-visible value handed to a player that is not drawn from holdings.

### Not defects (recorded as by design)

- **Starter-handout trade items.** `TutorialBot`'s trade offer is minted into the trade window on purpose — a
  starter handout, not a payment: `TutorialBot.java:411-421`
  (`BotTradeCommands.addItemToTrade` / `addCleanEquipToTrade` -> `generateCleanItemWithQty` /
  `generateCleanItemEquip`). The 1B meso it offers at `:409` is the contrast and is holdings-bound and
  ledgered through `Trade.setMeso` / `BotHoldings.noteMesoSpent`, refunded by `BotHoldings.noteMesoRefund`
  when the trade is cancelled (`server/Trade.java:179`).
- **Pantomime sprays.** A spray where no player pays and the bot itself owns and re-loots the conjured prop
  moves no value — e.g. `GachaBot`'s roulette spray: `GachaBot.java:86-87` builds the drops with
  `CustomReactor.createReactorDropList` and pops them with `CustomReactor.gachaPop`
  (`MapVFX/CustomReactor.java:267`), both on the fabricated (`conjured = true`) path, and `GachaBot.java:98`
  re-loots them itself with `botLootOwnerItems`, which credits a prop nowhere. These are the shape a fab
  faucet is allowed to take.
- **Sunk merchant income.** A bot-owned `HiredMerchant`'s proceeds stay sunk by design (finding 4, closed by
  commit `9f7aca06`; see
  `../decisions/06-casino-and-ambient-merchant-boundaries.md`), with `PlayerShop` as the ledgered contrast: a
  stall's sale proceeds *are* value the bot receives, so they go through `BotHoldings.noteMesoEarned`.

## What lands

**Nothing — RECORDED AND DEFERRED.** No code changes. This record exists so the rule is settled and the two
defects (the `DropGameBot` paid prize and the NX code mints) do not have to be re-derived when the gambling
side is picked up. Ticket 12's `BotHoldings.payOut` / `payOutItem` and the `MapItem.conjured` provenance flag
(landed in `1dfc85a4589d7451820fbc541302f16fef2d3390`, `Mark fabricated bot drops as props and pay casino pots
from the house`) are the mechanism the drop-game prize would use to pay out of a real stock instead of minting.
