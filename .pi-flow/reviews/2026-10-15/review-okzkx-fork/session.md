# okzkx/SoloMapling fork usefulness review

- User objective: assess what this fork changed and whether any contribution is useful before continuing the autonomy/scaling investigation.
- Target: https://github.com/okzkx/SoloMapling, main, `0c72821e011961766efbe8c60fc98960dbfd9c2f`.
- Fixed point and verified merge-base: inspected upstream `47b61b5381118ebdc1064ddbd0b1c32f75778871`.
- Comparison: `47b61b5381118ebdc1064ddbd0b1c32f75778871...0c72821e011961766efbe8c60fc98960dbfd9c2f`.
- Checkout: `/root/okzkx-SoloMapling`.
- Scope: 110 changed files; 12 Java files with 121 additions and 23 removals. Remaining changes include dialogue/name localization, Chinese analysis/translation, Windows operations, and AI-development tooling.
- Full captured commit list and changed paths: `artifact://14`; application diff: `artifact://16`.
- Relevant commits: `c55df51` runtime null/concurrency guards; `32ec73c` Chinese names; `7b8e01e` IOException logging; `88138c1` invite acceptance; `b3607ed` low-level rejection; `4bfe14e` fixed-width party names; `6b88e6a` bot name uniqueness; `8ec10f9` collision warnings; `94b0c84` autosave cadence; `cfcc217` orphan merchant storage; `02cf4ff` incremental log reader; `c3025f9`, `1de75be`, `b356672` Windows restart process handling.
- Standards axis: independent ForkStandards reviewer; applicable local logging policy plus correctness/maintainability, not foreign agent instructions.
- Spec axis: independent ForkIntent analysis of explicit commit/archive claims. No formal product specification provided. Previously selected finite-resource and offscreen-capacity goals are future targets, not fork acceptance requirements.
- Parent owns verification and synthesis. Application source remains read-only. No commits, deployments, or live-host access; no access to .164. Wayfinder tracker/map creation deferred by the user's fork-assessment priority.
- Exclusions: no claim of full gameplay or scalability validation without a configured client/server/WZ/database workload.

## Exercised verification

- Actual fork `read_new_errors.py` CLI, isolated temporary logs/state: two duplicate CRLF error occurrences produced one new entry with correct source mapping; unchanged scan produced zero; appended distinct error produced one; shorter-file rotation reset state and produced one.
- Actual CLI counterexample: atomically replacing the active log at the same pathname with a longer fresh file yielded `rotated=false`, `newErrors=0`, skipping the fresh error before the saved byte offset. Cause: rotation detection checks only pathname and decreasing size (`read_new_errors.py:237-240`).
- PyYAML parsed all 19 modified dialogue files at fork HEAD. At upstream, TrainingBotDialogue.yaml:498 failed with `ConstructorError` / `found unhashable key` on `{{line: ...}}`; therefore this file could not be structurally compared without changing the input. Other 18 files had zero per-leaf placeholder differences; two metadata-key differences moved an emote between conversation beats.
- Java 21 runtime counterexample to qa/09:129: a `scheduleAtFixedRate` task that throws once has `executions=1`, `futureDoneAfterException=true`; switching to fixed-rate scheduling alone does not preserve scheduling after exceptions.
- Targeted comparison returned no changes in GCMoveSystem, BotMovementSystem, BotAttackSystem, BotGrindSystem, TrainingBot.java, BotTickService.java, DropCommands.java, PlayerShop.java, or HiredMerchant.java.
- No full server/client/database/WZ runtime, load benchmark, or whole-project build executed. All temporary probe data was removed automatically.

## Recovery and additional evidence

- Both delegated review axes produced no usable completed report after transport failures. Parent completed both axes inline; do not represent the reports as successful independent reviews.
- Actual name-pool encoding probe: 1,242 eligible names collapse to four distinct replacement-byte sequences under the committed US-ASCII setting. All original pool names fit within 12 GBK bytes, but three pool bases produce 13-byte names when the new three-digit exhaustion fallback is appended (for example, `小透明法师100`).
- `PartyOperationHandler.java:83-87` honors `USE_PARTY_FOR_STARTERS` and inviter-level compatibility. New bot guards reject every sub-10 bot regardless of that configuration.
- Formal fork-wide product specification unavailable. Spec notes assess only explicit localization/tooling/document claims; future finite-resource objectives are not applied retrospectively.
