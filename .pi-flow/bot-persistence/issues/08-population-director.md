# 08: Population director — bots log in and out on a daily curve

**What to build:** A scheduler that replaces waves 8 and 9 for population bots. Every ~30-60 s it computes
`target(now) = peak × curve(hour, weekday)` from `config.yaml` (peak, curve points, timezone) and logs bots in
or out to approach it: logins prefer offline ACTIVE bots whose play window covers now, spread across the
interval so they trickle in; logouts pick bots whose session is spent or who are outside their window while
the world is above target. Staff waves 1-7 are unchanged. GM: `!roster curve` (preview today's target),
`!roster login|logout <name>`, a flat-target test mode.

**Blocked by:** 07.

**Status:** open

- [ ] With a fake clock, online count tracks the curve within a tolerance over a simulated day.
- [ ] The same bots recur at similar hours across simulated days ("regulars").
- [ ] Logins/logouts are spread, never a burst of hundreds in one tick.
- [ ] `SPAWN_BOTS_ON_STARTUP` still boots staff; population comes from the director.
