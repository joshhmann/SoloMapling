# 12: BotBrain — pluggable LLM with a scripted fallback

**What to build:** `BotBrain { reply(ChatContext); summarize(Transcript) }` with two providers selected in
`config.yaml`: one OpenAI-compatible HTTP client (`POST {base_url}/chat/completions`) that covers OpenRouter,
Ollama (`http://localhost:11434/v1`), llama.cpp server and LM Studio alike, and `ScriptedBrain` wrapping today's
YAML path. Config: `base_url`, `api_key` (optional; sent as `Authorization: Bearer`, needed for OpenRouter, ignored
locally), `model`, optional extra headers (OpenRouter's `HTTP-Referer`/`X-Title`), `max_tokens`, `temperature`,
concurrency cap, timeout. The API key is read from config or an env var and never logged.
Config also takes `extra_body`, a map merged verbatim into every request body, so server-specific knobs need no
code (llama.cpp's `chat_template_kwargs: {enable_thinking: false}`, `top_k`). Replies are cleaned before use: any
`<think>...</think>` block or leading "Thinking Process:" text is stripped, surrounding quotes removed, and an empty
result after cleaning counts as a failure (scripted fallback).

**Reference endpoint (owner's, 2026-10-18):** llama.cpp server at `http://192.168.0.17:8080/v1`, model
`Qwen3.8-9B-Distill-Heretic-Uncensored-Q8_0`, 2 parallel slots, 128K context. Without
`enable_thinking: false` it spends the whole token budget on visible reasoning (~8 s, garbage in chat); with it,
a persona reply took 0.49 s from this host (84 prompt / 12 completion tokens). Sampling it was tested with:
`temperature 1.0, top_p 0.95, top_k 20, max_tokens 40-64`. With 2 slots, the concurrency cap defaults to 2 and
excess chat waits in the queue until the timeout, then falls back. Calls are async on their own pool with a global
concurrency cap, a per-player rate limit, and a timeout (~6-8 s) that falls back to the scripted line. Nothing
on the bot tick or packet thread ever blocks on the network.

**Blocked by:** none (can run alongside 04-11).

**Status:** done (2026-10-18) — OpenRouter and Ollama not exercised (no key / no server here)

- [x] With no endpoint configured, behaviour is identical to today.
- [x] A slow or dead endpoint falls back within the timeout; the cap bounds concurrent calls.
- [x] Provider, URL, key, model, cap and timeout come from config.
- [x] With `enable_thinking` omitted against a thinking model, no reasoning text ever reaches chat.
- [ ] The same client works unchanged against OpenRouter, Ollama and llama.cpp server (manual check of each, recorded).

## What was built (as landed)

| Anchor | Change |
| --- | --- |
| `soloMapling/ArtificialPlayer/BotBrainSystem/BotBrain.java` | `complete(playerId, turns) -> CompletableFuture<Optional<String>>`; empty always means "use the scripted line"; `BotBrain.SCRIPTED` never answers |
| `.../OpenAICompatibleBrain.java` | `POST {base}/chat/completions` on virtual threads; one deadline covers the wait for a slot and the request; fair `Semaphore` cap; per-player minimum interval; Bearer key from config or env `SOLOMAPLING_LLM_API_KEY`; extra headers; `describe()` counters for the GM read-out |
| `.../ReplyCleaner.java` | strips `<think>` blocks (closed or not), refuses a bare "Thinking Process:" reply, drops quotes / a `Name:` prefix / extra lines, clamps to the 70-char chat box at a word |
| `.../Json.java` | dependency-free writer/parser for the two fixed shapes (no JSON library in the pom; the cached Jackson/Gson are 2018-19 builds) |
| `.../BotBrains.java` | builds the brain from `config.yaml`; blank `BOT_LLM_BASE_URL` = scripted |
| `config/ServerConfig.java`, `config.yaml` | `BOT_LLM_*` fields; the shipped default URL is blank; `BOT_LLM_EXTRA_BODY.chat_template_kwargs` defaults to `{"enable_thinking": false}`; an invalid fragment is logged and skipped |
| `client/command/commands/gm4/BrainCommand.java` | `!brain status`, `!brain ask <line>` (generic persona, shows latency) |
| tests | `OpenAICompatibleBrainTest` (10, against an in-process HTTP server: body/headers, no-key, leaked reasoning, slow, 500, unreachable, cap, queued-past-deadline, rate limit, config) and `ReplyCleanerTest` (5) |

Not wired into any bot yet: routing chat to it is ticket 13.

## Verification

- `mvn -o test` (2026-10-18): `Tests run: 2093, Failures: 1, Errors: 4` — only the five known WZ-missing failures.
- The shipped `config.yaml` parses: blank URL -> `scripted (no LLM endpoint configured)`, extra body read as
  `{chat_template_kwargs={"enable_thinking": false}}`.
- Live, owner's llama.cpp (`192.168.0.17:8080`, Qwen3.8-9B Q8, 2 slots), 5 lines with a bot persona:
  thinking off -> 5/5 in character, all five in 1927 ms (avg 1326 ms each), e.g. `are you a bot` ->
  `nope just a hunter lv34 farming henies`; thinking left on -> 5/5 fell back (`empty after cleaning`), no
  reasoning text reached a reply. Content was confidently wrong at times (`get to new hoon`), which is ticket 13's
  live-state/game-facts prompt, not this client.
