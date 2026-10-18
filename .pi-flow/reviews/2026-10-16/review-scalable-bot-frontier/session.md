# Review session: scalable-bot frontier (tickets 01-03)

## Target
Repository: /root/SoloMapling (git, branch `main`).
Change under review: the uncommitted working tree implementing the frontier tickets
01-shared-resource-accounting, 02-offscreen-reward-budget, 03-cost-attribution.

## Fixed point
`HEAD` = 47b61b5381118ebdc1064ddbd0b1c32f75778871 ("Merge branch 'beta/Training-bots'") — the last commit before this work.
Commits in scope: none yet; the entire change is in the working tree (no commit exists).

## Comparison
- `git -C /root/SoloMapling diff HEAD` — 23 modified files, 575 insertions(+), 57 deletions(-)
- 5 untracked new files:
  - src/main/java/soloMapling/ArtificialPlayer/BotHoldings.java
  - src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenRewardBudget.java
  - src/test/java/soloMapling/ArtificialPlayer/BotHoldingsTest.java
  - src/test/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenRewardBudgetTest.java
  - src/test/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotPerformanceMonitorTest.java
- Excluded from review: `.pi-flow/` (the review workspace, outside the reviewed tree at the time; it now lives at /root/SoloMapling/.pi-flow), `wz/` (sparse-checked out), build output.

## Evidence sources
- Specification: /root/SoloMapling/.pi-flow/scalable-bot-simulation/spec.md
- Tickets (acceptance criteria):
  - /root/SoloMapling/.pi-flow/scalable-bot-simulation/issues/01-shared-resource-accounting.md
  - /root/SoloMapling/.pi-flow/scalable-bot-simulation/issues/02-offscreen-reward-budget.md
  - /root/SoloMapling/.pi-flow/scalable-bot-simulation/issues/03-cost-attribution.md
- Investigation decisions the spec rests on: /root/SoloMapling/.pi-flow/scalable-bot-simulation/decisions/01..05-*.md
- Architecture records: /root/SoloMapling/.pi-flow/docs/adr/2026-10-16-offscreen-simulation-contract.md, /root/SoloMapling/.pi-flow/docs/adr/2026-10-16-finite-autonomous-bot-resources.md
- Repository standards: no AGENTS.md / CLAUDE.md / CONTRIBUTING.md / .editorconfig in /root/SoloMapling; standards axis therefore also uses the repo's own idioms in the touched packages plus the code-review smell baseline.

## Verification already recorded before review
- `sh ./mvnw -o test` in /root/SoloMapling (no wz/ data): 1930 tests, 1 failure + 4 errors — CoarseExecutorTest x2, GCWorldGraphTest x1, GCMoveBakeTest x2, all `Map data not found for 100000000|101000000` from BotNavigationMapLoader.java:28. Identical to the pristine 47b61b5 baseline in /tmp/solomapling-analysis.
- `sh ./mvnw -o test` in a throwaway full-WZ sandbox (/tmp/solomapling-e2e) with a MySQL 8.4 copy of the repo schema/data: 1930 tests, 0 failures, 0 errors, BUILD SUCCESS.
- Throwaway sandbox probe (real WZ map/mob/drop data, real drop_data tables, mysql container) drove `OffscreenRewardBudget.draw` and the real `TrainingBot.accrueAbstractLoot`: one bot banks its map's window (389 mesos + real inventory items on map 100000002); N bots on the same map never exceed one window of mesos and never exceed the per-item allowance.

## Axis outputs
- standards.md — reviewer task, Standards axis
- spec.md — spec task, Spec axis
- synthesis.md — written by the session owner from both, axes kept separate

## Landing
Landed as a single commit on `main` of /root/SoloMapling: `9b7aed75` ("Give bots real holdings, a per-map reward budget,
and cost attribution"), 33 files, working tree clean afterwards. The change set grew past the snapshot above while the
review ran: 26 modified + 7 new files. The standards fix round added the `BotHoldings` ledger locking and meso hooks,
`DropCommands.pickUpAndCredit` drop locking, the trade-offer laundering removal, the monitor's nested-flag and
`recordPathfind` changes, and two extra test classes (`DropCommandsPickupTest`, `BotTradeOfferTest` is the fourth new
test class overall, plus `BotGrindSystem/OffscreenRewardBudgetTest` and `GCMoveSystem/BotPerformanceMonitorTest`).
Headline counts in "Comparison" describe the reviewed snapshot, not the landed commit.

Post-landing re-verification: `mvn -o test` in /root/SoloMapling -> 1938 tests, 1 failure + 4 errors, all in
CoarseExecutorTest (x2), GCMoveBakeTest (x2), GCWorldGraphTest (x1) — the pre-existing WZ-missing set. In the full-WZ +
MySQL sandbox (`/tmp/solomapling-e2e`, synced from the landed tree): 1938 tests, 0 failures, 0 errors.

## Note on scope conflict
The earlier ADR .pi-flow/docs/adr/2026-10-16-finite-autonomous-bot-resources.md states implementation is not authorised, and spec.md lists product source changes as out of scope. The user explicitly superseded both by authorising implementation of tickets 01-03 in /root/SoloMapling (see issue-tracker session history). The Spec axis should therefore treat the tickets as the live requirements, not the ADR's authorisation clause.
