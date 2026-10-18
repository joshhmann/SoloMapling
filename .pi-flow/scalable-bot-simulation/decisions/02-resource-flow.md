# Ticket 02: Where do current bot resources enter, leave, and persist?

Type: research
Status: resolved

## Question

Where do current bot inventory, mesos, items, and equipment changes enter, leave, and persist?

## Answer

Bot holdings are entry-poor, exit-synthetic, and persistence-gated. Kills generate real bot-owned world drops (`BotAttackEffects:20-230`), but bot pickup is removal-only and bypasses `Character.pickupItem` credit (`DropCommands:230-293`; `GrindLoot:1-224`). Spending is synthetic: FM `botBuy` credits sellers/decrements stock without charging the bot (`PlayerShop:708-742`; `HiredMerchant:833-882`), trade offers use `setMesoBot`/generated items with native completion skipping bots (`BotTradeCommands:1-300`; `Trade:323-410`), and despawn drops unsaved deltas (`BotGeneration:185-197`). `saveCharToDB` persists mesos/inventory (`Character:8297-8703`) but early-returns when not logged in and autosave covers only logged-in characters; no bot login path was found. Exceptions credit owners/DB tables, not bot holdings: merchant mesos and NX codes. Full findings: `agent://ResourceFlow`.
