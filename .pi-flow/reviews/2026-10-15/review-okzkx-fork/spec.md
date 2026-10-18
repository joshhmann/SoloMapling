# Spec

No formal originating product specification was available. Formal Spec axis unavailable; do not apply the user's later finite-resource and offscreen-capacity goals retrospectively. Parent inspected published commit/archive intent inline after delegated transport failure.

## Intent and usefulness evidence

- Core navigation, combat, grinding, tick scheduling, generic loot, and bot shop purchase implementations have no delta in the scoped comparison. This is not a new autonomy or scalability implementation.
- Localization is substantive: 19 changed dialogue YAML files and a Chinese name pool. PyYAML parses all fork dialogue files. The upstream TrainingBotDialogue.yaml:498 fails with ConstructorError / found unhashable key; the fork repairs that data under this parser. This does not prove the production YAMLbeans loader or client behavior.
- For the other 18 dialogue files, structural comparison found zero per-leaf placeholder changes and an emote moved between two conversation beats.
- The incremental log reader is reusable operational tooling, with normal incremental/dedup/shorter-file rotation paths exercised and same-path replacement limitation recorded in standards.md.
- Windows restart tooling is workstation-specific, including hardcoded JDK/MySQL assumptions and first matching Cosmic.jar process selection. Not run; not a generic Linux deployment solution.

## Documentation cautions, not scored specification findings

- src/main/java/soloMapling/Documents/qa/09-打怪后调度全停与切图失败.md:129 incorrectly says scheduleAtFixedRate exceptions will not terminate repetition. An actual Java 21 probe produced executions=1 and futureDoneAfterException=true. The document itself leaves the global-freeze root cause unconfirmed.
- src/main/java/soloMapling/Documents/code-analysis/18-分布式架构改造可行性分析.md:17-26 overstates client-protocol constraints as proof that internal server distribution is impossible. Its in-process object-coupling observations at lines 28-43 are useful; the categorical conclusion and capacity claims are not benchmark evidence.

Formal Spec findings: unavailable. Published documentation is a navigation aid, not a verified architecture or performance specification.
