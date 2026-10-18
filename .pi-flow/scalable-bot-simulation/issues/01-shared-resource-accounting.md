# 01: Shared bot resource accounting path

**What to build:** A bot's inventory and mesos move through a real accounting path: loot pickup posts credit, shop/merchant purchases debit mesos and grant the acquired goods, and transfers settle explicitly, with defined behavior on insufficient funds or full inventory.

**Blocked by:** None (can start immediately).

**Status:** done

- [x] Bot loot pickup adds the item/mesos to that bot's holdings instead of removing the drop with no credit.
- [x] Bot shop and merchant purchase debits the buyer's mesos and grants the purchased item; insufficient funds refuse the purchase.
- [x] Bot-to-bot and bot-to-player trades settle on both sides rather than only the non-bot side.
- [x] Bot despawn no longer silently discards in-memory holdings; deltas are persisted or explicitly settled.
- [x] Existing player-side behavior (seller credit, stock decrement, sold ledger) is unchanged.

## Comments

Implemented in `/root/SoloMapling` (uncommitted at review time).

- New seam `src/main/java/soloMapling/ArtificialPlayer/BotHoldings.java`: `creditDrop` / `canCreditDrop` / `canHold` /
  `grantItem` / `grantMeso` / `spendMeso` / `noteMesoSpent` / `noteMesoRefund` / `settle`, plus a per-bot ledger of
  credited deltas. Every entry point takes the `Character` explicitly, because the shared bot `Client` has no player
  attached and no `InventoryManipulator` entry point can credit a bot.
- Loot: `BotCommandsPack/DropCommands.pickUpAndCredit` credits first and removes only on a successful credit, inside
  the drop's lock (`lockItem` → re-check `isPickedUp` → credit → `pickItemDrop` → `unlockItem`), the same discipline
  `Character.pickupItem` uses. `BotGrindSystem/GrindLoot` filters through `canCreditDrop`.
- Shops: `PlayerShop.botBuy` and `HiredMerchant.botBuy` debit the buyer, grant the goods (quantity-aware) and refund
  on a full inventory; the refusal paths (`message`, `return false`) match the player-facing ones. Seller credit,
  stock decrement and the sold ledger are untouched.
- Trades: both sides complete (`Trade.completeTrade`); a bot's generated item offer is window-only and is never
  credited back on cancel/swap, so it cannot be minted or duplicated; mesos are real — debited through
  `Trade.setMeso` and ledgered, refunded on cancel, and the payout stays spent after a completed trade.
- Despawn: `BotGeneration.removeBotFromServer` settles the bot while it is still on the map it leaves.

Evidence: `BotHoldingsTest` 7, `BotTradeOfferTest` 4, `DropCommandsPickupTest` 2, `OffscreenRewardBudgetTest` 9 —
all green; full suite 1938 tests with 0 failures in a full-WZ + MySQL sandbox, and only the pre-existing WZ-missing
failures in the sparse working tree. Red-before/green-after proofs exist for the trade mint, the missing ledger
hooks and the unlocked pickup. Full review record:
`.pi-flow/reviews/2026-10-16/review-scalable-bot-frontier/` (`synthesis.md`).
