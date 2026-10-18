# 18: Bots go where players go

**What to build:** Aggregate the ticket-17 capture into per-map heatmaps (where players stand and grind) and
portal-choice counts, persisted with the learned nav data. Bias bot camp/loiter spot choice and travel route
selection toward them, never so hard that all bots pile onto one spot (spot claims still apply).

**Blocked by:** 17.

**Status:** open

- [ ] Heatmaps persist and decay.
- [ ] With heatmap data, bot spot choice shifts toward it; without it, behaviour is unchanged.
