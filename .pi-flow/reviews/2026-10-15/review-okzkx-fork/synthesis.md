# Fork usefulness: selective reuse, not a new base

Compared upstream 47b61b5381118ebdc1064ddbd0b1c32f75778871 with okzkx/SoloMapling 0c72821e011961766efbe8c60fc98960dbfd9c2f. User requested stopping deeper investigation if little relevant value; assessment stops here. Application source unchanged.

## Useful scope

Most additions are Chinese localization, analysis documents, Windows operations, and AI-development tooling. Small reusable server changes include null/orphan guards, synchronized HP-decrease snapshot, shorter real-player autosave interval, duplicate-name diagnostics, and fixed-byte-width party serialization. Log reader is useful with replacement detection repaired. No changes found in the inspected core navigation/combat/grinding/tick/economy implementations; no new simulation framework or measured scalability improvement established.

## Standards

1. **High:** config.yaml:306 still selects US-ASCII while the fork introduces Chinese names; actual pool encoding collapses 1,242 names into four byte sequences. FMShopDescGen.java (full path src/main/java/soloMapling/FreeMarket/FMShopDescGen.java):347-354 also permits 13-byte GBK fallback names, while src/main/java/tools/PacketCreator.java:3842-3846 copies at most 12 bytes. Fix charset/client agreement and encoded-byte validation rather than silently truncating identity.
2. **Medium:** src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:84-91 and src/main/java/soloMapling/ArtificialPlayer/BotTypes/FollowerBot.java:119-124 unconditionally reject sub-10 bots, unlike configurable player eligibility. Reuse the existing policy.
3. **Medium:** .claude/skills/read-server-log/scripts/read_new_errors.py:237-240 misses same-path replacements when the new file is at least as large as the saved offset. Reproduced actual CLI loss of a new error. Track file replacement identity/fingerprint.
4. **Medium:** src/main/java/client/Client.java:242 suppresses warnings for all IOException instances. Narrow suppression to expected benign disconnects so other I/O failure causes remain diagnosable.

Detailed axis evidence: [standards.md](standards.md).

## Spec

Formal originating specification unavailable; not scored. Commit/archive intent was reviewed, not substituted with future user requirements. Documents are useful navigation material but contain incorrect fixed-rate scheduler advice and overconfident distribution claims; do not adopt those as implementation proof. See [spec.md](spec.md).

## Verification

Actual incremental log-reader CLI exercised normal scanning, duplicate suppression, append, shorter-file rotation, and same-path replacement failure. PyYAML parsed all 19 modified fork dialogue files; upstream TrainingBotDialogue.yaml:498 has malformed data under that parser. Other 18 files preserve per-leaf placeholders, with one emote moved. Java 21 scheduler exception probe disproved the fixed-rate survival claim. Actual name-pool encoding checked. No full game/client/database/WZ runtime, benchmark, or full build.

Both delegated axes failed to deliver usable completed reports; parent recovered the analysis inline. No successful independent sign-off claimed.

Recommended next action: keep the existing upstream baseline for autonomy/scaling work; selectively port small fixes only when relevant. Do not merge or investigate the whole fork further now. Wayfinder remains deferred, not silently executed.

Summary: Standards 4 findings, worst High; Spec unavailable.
Review result: changes requested
