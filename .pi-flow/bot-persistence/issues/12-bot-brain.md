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

**Status:** open

- [ ] With no endpoint configured, behaviour is identical to today.
- [ ] A slow or dead endpoint falls back within the timeout; the cap bounds concurrent calls.
- [ ] Provider, URL, key, model, cap and timeout come from config.
- [ ] With `enable_thinking` omitted against a thinking model, no reasoning text ever reaches chat.
- [ ] The same client works unchanged against OpenRouter, Ollama and llama.cpp server (manual check of each, recorded).
