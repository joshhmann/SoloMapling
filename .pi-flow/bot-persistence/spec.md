# Spec: a living bot population (persistent roster, sessions, memory, learned movement)

Status: draft (2026-10-18) — decisions below were settled by grilling the owner on 2026-10-18.

## Problem statement

The scalable-bot-simulation series (`../scalable-bot-simulation/`) made a bot's *numbers* durable
(`BotStore`: name, level, EXP, mesos, job, look, items incl. worn gear, map, position), then spent tickets
10-13 on economy accounting. The world still behaves like a boot script:

- **Population is a fixed boot plan.** `EnvironmentManager.environmentLoadStartup` runs waves 1-9 once
  (`Server.java:966`); nothing ever logs a bot in or out. Wave 8 (training cohorts, `EnvironmentManager.java:188-208`)
  and wave 9 (town presence, `TownPresence.yaml`) are the "players".
- **Identity is a spawn position.** `BotStore.planSlot(wave, task, ordinal)` keys the row; the bot's type is
  deliberately not stored (`025-botstore.sql:11-13`). GM spawns use `nextDynamicSlots`, are never adopted and
  overwrite old rows.
- **Bots don't resume.** `restorePlacement` only applies if the wave's map equals the stored map, so a trainer
  saved on a field map comes back in town; all per-type state (`TrainingBot` phase, train map, home) is in memory.
- **Bots don't remember.** Chat is keyword matching onto YAML lines (`SocialBot.handleDialogueChoice`);
  `InteractionTracker` is an in-memory spam counter. No LLM/HTTP integration exists.
- **Names collide.** The name pool is 1636 entries (`randomRealMaplestoryIGNs.txt`) and duplicates are allowed.
- **Movement never learns.** The live GC engine builds graphs from map physics (`BotPhysicsEngine`,
  `BotNavigationGraphProvider`, cached in `cache/bot-nav/v61/`), but stuck edges and travel warps are
  forgotten; nothing uses how real players move. The legacy engine replays GM recordings
  (`movementDataPackets/`, 26 maps) and is still used by tutorial/JQ/OPQ/drop-game/gacha bots.

## Target experience

Like AzerothCore playerbots: a roster of randomly generated characters who each have a personality and a
habitual play time. They log in and out along a daily curve, pick up exactly where they left off, level only
while online, make new characters, drift away and occasionally come back. When you talk to one it answers like
a real player, remembers what you talked about last time, and may have heard about you from another bot.
Bots get better at moving around maps over time.

## Settled decisions

| Topic | Decision |
| --- | --- |
| Restart / relog | Resume exactly where stopped: map, position, current activity and its parameters. |
| Offline progress | None. A logged-out bot is frozen. |
| Peak online | ~3,300 — today's boot population (3,491 stored rows, 292 in Henesys alone) is the evening peak; nights thin out from it. Settled 2026-10-18 after ticket 01 counted the store. |
| Roster size | ~3x peak (~10,000 registered). |
| Online rhythm | Real-clock daily curve; each bot has its own habitual play window, so "regulars" recur. |
| New bots | Mostly level 1, plus a seeded starting roster of "veterans" across levels so day one isn't all beginners. |
| Churn | A bot that quits goes dormant (row kept); small chance of returning much later. |
| Logout rule | Never mid-commitment (party, trade, follow, conversation): finish, say bye, then log out. |
| Service bots | FM merchants, casino dealers, tutorial guide, drop-game host, OPQ lobby stay fixed "staff" spawned at boot. |
| Role | Stable personality per bot; each session picks an activity from it (grind, hang in town, socialise, ...). |
| Chat | LLM for player-directed chat only; ambient bot-to-bot chatter stays scripted YAML. |
| LLM provider | Any OpenAI-compatible HTTP endpoint (OpenRouter, Ollama, llama.cpp server, ...) set by URL/key/model in config; scripted YAML fallback when unset or down. |
| Persona | Acts like a real MapleStory player (short, lowercase chat style); never mentions being an AI. |
| Memory | Short notes from past chats, per (bot, player), plus gossip: bots pass notes about a player to other bots. |
| Nav learning | Learn from failures, and learn from how real players move (including as general data, e.g. where they go). |

## Design

### 1. Roster identity (decouple population bots from spawn slots)

- Population bots get a **roster id** that is theirs for life. Staff keep `planSlot` exactly as today.
- Migration `026-botroster.sql` (Liquibase changeSet 26) extends `botstore` or adds `botroster` with:
  `Status` (ACTIVE / DORMANT), `Personality` (seed + activity weights + chattiness), `PlayWindowStart`,
  `PlayWindowLen`, `SessionMinutes` (typical), `LastLoginAt`, `LastLogoutAt`, `LastMapId` (already `MapId`),
  `Activity` + `ActivityState` (small JSON: train map, home town, loiter spot), `HomeMapId`, `Persona` (JSON:
  typing style, vibe, interests). Existing rows from wave 8/9 slots are imported as the initial roster.
- Names become unique across the roster. The 1636-name pool is smaller than the roster, so add a name
  generator (pool names + MapleStory-style variants, e.g. suffix digits, `x`-wrapping, recombination) with a
  uniqueness check against the roster.
- Items keep using `inventoryitems` keyed by `20000 + id`; roster ids get their own id range so they never
  collide with staff slots.

### 2. Resume exactly

- Login places the bot at `LastMapId` + `PosX/PosY` regardless of any wave, then restores `Activity` from
  `ActivityState` (e.g. `TrainingBot` re-enters DECIDE with `currentTrainMapId`/`homeMapId` from the row instead
  of "wherever I spawned", `TrainingBot.java:408`). Movement/FSM phase still reconstructs (ticket 04 boundary).
- If the stored map is invalid now (instance/event map, removed), fall back to the bot's home town.
- Logout = the existing `removeBotFromServer` path (it already closes trades, releases claims and saves);
  it additionally writes `Activity`/`ActivityState` and `LastLogoutAt`.

### 3. Population director (replaces waves 8 and 9)

- A scheduler ticks every ~30-60 s and computes `target(now) = peak × curve(hour, weekday)`; the curve and
  peak live in `config.yaml`.
- **Login:** pick dormant-free roster bots whose play window covers now and who are offline, weighted by how
  far below target we are; spread logins over the minute so they trickle in, not all at once.
- **Logout:** a bot whose session length is spent, or which is outside its window while above target, logs
  out — but only when `BotCommitments` says it holds no party/trade/follow/conversation; otherwise it waits
  (with a hard cap) and says a goodbye line first.
- **Lifecycle:** a small daily rate of new characters (mostly level 1 at the beginner start; veterans only when
  seeding an empty roster); a bot that hasn't logged in for a long stretch may roll DORMANT; a dormant bot has
  a small chance per day to return. Roster size is held around the configured multiple of peak.
- **Activity per session:** chosen from the personality weights and the bot's level (grind -> `TrainingBot`,
  town -> `TownWandererBot`, social -> `SocialBot`) via the existing `BotTypeManager.convertBotType`/
  `setAndStartBots`. A resumed session keeps its previous activity.
- GM commands: `!roster status`, `!roster login|logout <name>`, `!roster curve` (preview), `!roster seed`.

### 4. Scaling at ~3,300 online

- Most online bots are offscreen; they run on the existing LOD path (`ObserverTracker`, `CoarseExecutor`).
- Before raising the default peak, run `BotPopulationProof` at ~3,300 and ~4,100 online with
  `BotPerformanceMonitor` attribution and record the sparse / crowded / transition costs. Login and logout are
  new transition costs and must be measured.

### 5. LLM chat

- Interface `BotBrain { reply(ChatContext) ; summarize(Transcript) }` with two providers: one
  OpenAI-compatible `/chat/completions` client (OpenRouter, Ollama, llama.cpp server, LM Studio — chosen by
  `base_url`, `api_key`, `model` in config) and `ScriptedBrain` (today's YAML path, the fallback).
- Only player-directed chat reaches the LLM: the `Dispatcher` already identifies the addressed bot and
  current respondant. `SocialBot`'s keyword path becomes the fallback.
- Calls are async with a global concurrency cap and per-player rate limit; on timeout (~6-8 s) the scripted
  line is used. Replies are clamped to MapleStory chat length and sent after a short "typing" delay.
- Prompt = persona (from the roster row) + the bot's live state (level, job, map, activity, recent events
  via `DialogueContextResolver`) + memory notes about this player (direct first, then hearsay) + the last few
  lines of the current conversation. The persona instructs: short lowercase player chat, stay in character as
  a person playing the game, never mention being an AI.
- Prerequisite fix: `SocialBot.processMessages` appears to discard a polled message whose sender isn't its
  respondant (`SocialBot.java:274-275`); confirm and fix before routing LLM chat through it.

### 6. Memory and gossip

- Table `botmemory (Id, BotId, AboutCharacterId, Note, Source DIRECT|HEARSAY, FromBotId, Hops, CreatedAt, LastUsedAt)`.
- **Write:** when a conversation ends (`SocialBot.doGoodbye`, `checkConversationTimeout`, `resetConversation`),
  the brain summarizes it into at most 2 notes of <=140 chars. Cap per (bot, player) at ~10 notes; when full,
  the oldest notes are merged/summarized into one.
- **Read:** the newest/most-used notes about the player go into the reply prompt; `onFirstInteraction`
  can open with them ("yo didnt u say u were going to orbis").
- **Gossip:** when two online bots share a map (or a scripted bot-to-bot conversation runs), a bot holding a
  DIRECT note about a player may pass it with some chance; the receiver stores it as HEARSAY with `FromBotId`
  and `Hops + 1`. No LLM call needed (copy + attribution); stop at 2 hops; notes about a player only spread
  among bots that have met them or share a map with them.
- Notes are about the owner's own chats on their own server; no export, no external storage beyond the chosen
  LLM endpoint.

### 7. Movement learning

**7a. Learn from failures (GC engine).**
- Persist per-edge outcomes `(mapId, graph version, from foothold, to foothold, edge type) -> attempts,
  successes, stuck, avg duration` in `cache/bot-nav/learned/<map>.json` (or a DB table). Record from the
  existing signals: stale-edge give-up and replan (`BotNavigationManager`), `tickUnstuck`
  (`BotMovementManager.java:818`), and GCTravel warp reasons (`GCTravel.java:246/306/332`).
- The planner multiplies an edge's cost by its observed failure rate; edges past a threshold are disabled for
  that map until a decay window passes. Stuck spots also mark nearby footholds as poor loiter/camp spots.
- Fix while there: the graph cache key includes `snowShoes` but the filename and `loadGraph` checks ignore it
  (`BotNavigationGraphProvider.java:546-549, :568`).

**7b. Learn from real players.**
- Always-on lightweight capture in `MovePlayerHandler` for real players only (not the GM `!move` recorder,
  which is single-user global state): reduce each move packet to foothold-to-foothold transitions (take-off
  foothold, landing foothold, duration, the input fragment).
- A transition the graph lacks, or one players do much faster than the graph's cost, becomes a candidate edge.
  It is validated by re-running the physics sim; validated edges join the graph as `OBSERVED` edges (or are
  stored as replayable fragments when the sim can't reproduce them).
- Aggregate where players go: per-map heatmaps of where players stand/grind and which portals they take,
  used to bias bots' spot choice and travel routes.
- One owner means sparse data; 7b complements 7a and is never required for a map to work.

## Phases

| Phase | Delivers | Depends on |
| --- | --- | --- |
| P0 | Restart smoke test on the real server; `!roster status`-style read-out; fixes: SocialBot discard, snowShoes cache key | — |
| P1 | Roster identity, migration 026, unique names, import of existing rows | P0 |
| P2 | Resume exactly (last map, activity state) | P1 |
| P3 | Population director: daily curve, sessions, commitment-safe logout, creation/dormancy/return, seeding | P2 |
| P4 | Scale proof at ~3,300 / ~4,100 online; tune LOD and login/logout costs | P3 |
| P5 | `BotBrain` + OpenAI-compatible provider + scripted fallback + persona | P1 (persona column) |
| P6 | Memory notes + gossip | P5 |
| N1 | Nav learning from failures | — |
| N2 | Nav learning from real players + heatmaps | N1 |

P5/P6 and N1/N2 can run in parallel with P2-P4.

Tickets (`issues/`): P0 = 01-03 · P1 = 04-05 · P2 = 06 · P3 = 07-10 · P4 = 11 · P5 = 12-13 · P6 = 14-15 ·
N1 = 16 · N2 = 17-18 · chat routing = 19 · client-safe item ids = 20 · shared interaction layer = 21 · whispers = 22 · menu window = 23. Independent starting points: 01, 02, 03, 12.

## Testing

- Unit: roster import from existing slots; unique name generation; resume to last map vs fallback; director
  picks/logouts against a fake clock and curve, commitments block logout; dormancy/return rates; brain
  fallback on timeout; note write/merge caps; gossip hop limit; edge cost penalty and decay; observed-edge
  validation.
- Proof: extend `BotPopulationProof` so phase 2 checks a bot that logged out mid-grind logs back in on the
  same map with the same activity, and a memory note survives restart.
- Manual: talk to a bot, restart, talk again; watch a day's curve with `!roster curve`.

## Out of scope

- More economy work: `../scalable-bot-simulation/decisions/07-drop-game-and-nx-faucets.md` stays deferred;
  no more ledger hardening.
- Porting the legacy recorded-path bots (tutorial/JQ/OPQ/drop-game/gacha) onto the GC engine, and adding
  taxi/ferry/scripted links to `GCWorldGraph` so travel warps less — possible later work, not chosen now.
- Bots as persistent buddy-list or guild members (they have no `characters` row; `BuddyList.java:148`).
- Quest state for bots; LLM for bot-to-bot chatter; offline progression.

## Open questions

1. (Settled 2026-10-18: any OpenAI-compatible URL. Reference endpoint: owner's llama.cpp Qwen3.8-9B at
   `192.168.0.17:8080`, 2 slots, ~0.5 s per reply with thinking off — details in ticket 12.)
2. Curve shape and defaults: peak hour, trough depth, weekend lift, which timezone (server local?).
3. Do roster bots ever open FM shops while online (merchants as players), or is that staff-only forever?
