# Call graphs

Source-backed chains; details and anchors in architecture.md and data-flow.md.

## Lifecycle
Server startup → BotClientHandler.initHeadlessBotClient → EnvironmentManager startup waves → BotGeneration.createBot → load Character template ID 2 → register/place/decorate → BotTypeManager → concrete BotSM → BotTickService.register → virtual-thread tick → updateState.

## Observed terrain movement
Concrete FSM / GrindStrategy / BotWanderSystem → GCMovement.move/follow → GCMovementDriver → BotNavigationManager → cached geometry graph from BotNavigationGraphProvider → BotMovementManager → BotPhysicsEngine → Character.setPosition → BotMovementManager.sendMovementPacket → PacketCreator.movePlayer → MapleMap.broadcastMessage.

## Legacy movement
FSM → MovementCommands.pathFinderBeta/pathFinderAware → MapGraph + PathFinder recording route → BotMoveStreamHelper → BotMove → updatePositionBot → map.moveBot → PacketCreator.movePlayer.

## World travel
GCMovement.travel → GCTravel polling → GCWorldGraph BFS → GCMovement.move(trigger) → GCPortals.enter / taxi or curated warp → Character.changeMap. Recovery can bypass traversal with direct warp.

## Monster attack
TrainingBot GRIND → shared combatTickAll (250 ms) → TrainingBot.combatTick → GrindBrain.tick → CAMP/PATROL/ROAM/STACK strategy → EngageBeat.engageAndSwing → BotAttackDriver.botAttack → job/weapon BotAttackConfig + BotAttackProfile → BotDamageModel.rollLine → BotAttackEffects melee/ranged/magicStrike → PacketCreator attack broadcast → Monster.damage → MapleMap.killMonster(withDrops=false) → Monster.killBy (normal EXP attribution) + BotAttackEffects.dropMobLoot.

Actual driver targets are selected independently of the approach target. Cosmetic BotCommandsPack.BotAttack swings instead send empty-target packets and never enter this mutation chain.

## Chat, trades, and parties
GeneralChatHandler → MessageQueue → Dispatcher → SocialBot.onFirstInteraction/updateState → YAML selection → BotTiming delayed beats → SocialCommands.BotSpeak/BotEmote.

Trade.inviteTrade → BotTradeQueue → BotTradeLogic.checkTradeQueue → BotTradeCommands.acceptTradeInvite → Trade.visitTrade → BotTradeSM → BotTradeCommands.confirmTrade → Trade.completeTrade.

PartyOperationHandler → BotPartyQueue → BotPartyCommands → InviteCoordinator.answerInvite → Party.joinParty → world party update → optional SocialBot-to-FollowerBot conversion.

## OPQ and pickups
OPQOrchestrator synchronized reactor claim → OPQBot legacy approach → BotAttack.basicSwing + CustomReactor.hitReactor → Reactor.setState + triggerReactor broadcast → explicit dropItemAtReactor → DropCommands removal → local objective count → newly spawned delivery stack.

GrindLoot / DropCommands → pickup packet + MapleMap.pickItemDrop → map object removal only; does not reach inventory or mesos credit.
