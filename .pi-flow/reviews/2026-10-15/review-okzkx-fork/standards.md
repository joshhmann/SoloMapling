# Standards

Comparison: 47b61b5381118ebdc1064ddbd0b1c32f75778871...0c72821e011961766efbe8c60fc98960dbfd9c2f. Parent recovered this axis inline after reviewer transport failures. No application source changed.

## High — localized names lack an end-to-end encoding contract

Evidence: config.yaml:306 retains US-ASCII; src/main/java/constants/string/CharsetConstants.java:39 merely adds GBK as an option. Actual pool encoding probe maps 1,242 eligible Chinese names to four ASCII replacement sequences. src/main/java/soloMapling/FreeMarket/FMShopDescGen.java:347-354 limits fallback by Java characters, while src/main/java/tools/PacketCreator.java:3842-3846 copies at most 12 encoded bytes. Three current pool bases produce 13-byte GBK suffixed names, for example 小透明法师100. Consequence: localized identity is lost under the default setting, and fallback names can disagree with their party representation after byte truncation. Fix: explicitly configure the matching client/server charset and reject or generate names within the encoded-byte budget; do not silently truncate a valid identity. The fixed-width party-field idea is still useful.

## Medium — bot party guards override configured beginner policy

Evidence: src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:84-91 and src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBot.java:119-124 reject all sub-10 bots. src/main/java/net/server/channel/handlers/PartyOperationHandler.java:83-87 instead honors USE_PARTY_FOR_STARTERS and inviter-level compatibility. Consequence: enabling beginner parties does not enable the corresponding bot path. Fix: reuse the same eligibility policy. Default false configuration is unaffected by this specific mismatch.

## Medium — incremental log reader misses same-path replacements

Evidence: .claude/skills/read-server-log/scripts/read_new_errors.py:237-240 detects rotation using only pathname or decreasing size. Actual CLI probe replaced the active log with a longer file at the same path; rotated=false, newErrors=0, and the fresh error was skipped before the saved offset. Consequence: useful diagnostic tool can omit new errors after rotation/restart. Fix: retain file identity or a suitable replacement fingerprint and reset the offset on replacement.

## Medium — all IOExceptions lose warning diagnostics

Evidence: src/main/java/client/Client.java:242 excludes every IOException from warning logging. This follows the fork's broad local logging policy, but correctness/operability judgment: IOException is broader than expected socket disconnects, so unrelated I/O failures lose their cause. Fix: narrow classification to known benign disconnects and retain actionable diagnostics for other failures.

## Useful changes, separately

- src/main/java/client/processor/npc/FredrickProcessor.java:96,196: null timestamp and orphan storage guards; missing timestamp maps to zero elapsed days, a policy to review when porting.
- src/main/java/net/server/task/RankingLoginTask.java:73: null last-login guard.
- src/main/java/net/server/world/World.java:1781: synchronized HP-decrease map snapshot.
- src/main/java/net/server/world/World.java:238-243: five-minute rather than hourly character autosaves. CharacterAutosaverTask still filters by isLoggedin; this is not durable bot simulation.
- src/main/java/net/server/PlayerStorage.java:54-73: duplicate-name warning is useful observability, not collision prevention; insertion still overwrites.
- src/main/java/tools/PacketCreator.java:3842-3858: fixed byte-width party name field is a sound direction, subject to valid encoded names.

Findings: 4; worst High. No full server/client/DB/WZ run or whole-project build. These are selective-reuse concerns, not a request to implement fixes now.
