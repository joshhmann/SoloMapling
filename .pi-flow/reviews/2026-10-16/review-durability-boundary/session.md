# Review session: durability boundary (ticket 04)

## Target
Repository: /root/SoloMapling (git, branch `main`).
Change under review: the uncommitted working tree implementing frontier ticket
04-durability-boundary (the observed/offscreen state split and its promotion reconstruction).

## Fixed point
`9b7aed75` ("Give bots real holdings, a per-map reward budget, and cost attribution") — the round-1 commit
and the last commit before this work. No commit exists for this change yet; it is all in the working tree.

## Comparison
- `git -C /root/SoloMapling diff 9b7aed75` — 1 modified file, 6 insertions(+), 7 deletions(-):
  - src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java
- 2 untracked new files:
  - src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotTransitionBoundary.java
  - src/test/java/soloMapling/ArtificialPlayer/GCMoveSystem/BotTransitionBoundaryTest.java
- Excluded from review: `.pi-flow/` (the review workspace, outside the reviewed tree at the time; it now lives at /root/SoloMapling/.pi-flow), build output,
  and the sandbox-only throwaway probe (never committed).

## Evidence sources
- Ticket (acceptance criteria): /root/SoloMapling/.pi-flow/scalable-bot-simulation/issues/04-durability-boundary.md
- Specification: /root/SoloMapling/.pi-flow/scalable-bot-simulation/spec.md
- Investigation decisions: /root/SoloMapling/.pi-flow/scalable-bot-simulation/decisions/03-offscreen-durability.md
  (durable vs reconstructible split), 01/02/05 for the accounting the split relies on.
- Architecture records: /root/SoloMapling/.pi-flow/docs/adr/2026-10-16-offscreen-simulation-contract.md,
  /root/SoloMapling/.pi-flow/docs/adr/2026-10-16-finite-autonomous-bot-resources.md
- Round-1 review record for the same frontier (same standards baseline, same repo idioms):
  /root/SoloMapling/.pi-flow/reviews/2026-10-16/review-scalable-bot-frontier/{standards,spec,synthesis}.md
- Repository standards: no AGENTS.md / CLAUDE.md / CONTRIBUTING.md / .editorconfig in /root/SoloMapling;
  the standards axis therefore uses the repo's own idioms in the touched packages plus the smell baseline.

## Verification already recorded before review
- `/root/SoloMapling` (no wz/ data): `mvn -o test` → 1944 tests, 1 failure + 4 errors, all five the known
  WZ-data failures (CoarseExecutorTest x2, GCMoveBakeTest x2, GCWorldGraphTest x1, `Map data not found` from
  BotNavigationMapLoader). Same set as the pristine baseline; the 7 new tests pass.
- Full-WZ sandbox /tmp/solomapling-e2e (wz/ + MySQL): full suite → 1945 tests, 0 failures, 0 errors, BUILD SUCCESS.
- Red-before proof: with the pre-change body restored (coarse fields cleared inline, plain
  resetEntryStateAfterTeleport), the new test class fails 23 field assertions in one test — the stale
  swept-touch anchor being the live defect; the durable-set assertions pass before and after by design.
- Throwaway real-WZ probe: promotion against real map 100000000 geometry (338 footholds) snapped the bot from
  y=232 to the rail at y=244 and re-seeded the swept-touch anchor at the landed position with the bot's map id.

## Axis outputs
- standards.md — reviewer task, Standards axis
- spec.md — spec task, Spec axis
- synthesis.md — written by the session owner from both, axes kept separate
