# 04: Population bots get a roster identity of their own

**What to build:** Decouple population bots (today's wave 8 training cohorts and wave 9 town presence) from
`BotStore.planSlot`. Migration `026-botroster.sql` (Liquibase changeSet 26 in `db/changelog-tables.xml`) adds the
roster columns: `Status` (ACTIVE/DORMANT), `Personality` (JSON: seed, activity weights, chattiness),
`PlayWindowStart`, `PlayWindowLen`, `SessionMinutes`, `LastLoginAt`, `LastLogoutAt`, `HomeMapId`, `Activity`,
`ActivityState` (JSON), `Persona` (JSON). Roster ids get their own id range so they never collide with staff
slots; staff (FM, casino, tutorial, drop game, OPQ lobby) keep `planSlot` unchanged. Existing wave 8/9 rows
are imported as the initial roster.

**Blocked by:** 01.

**Status:** done (2026-10-18) — pending the real-boot migration check below

- [x] Migration applies on a fresh DB and on a DB with existing `botstore` rows.
- [x] Existing population rows become roster members with their name/level/items intact.
- [x] A roster bot can be loaded by roster id alone, with no wave/task/ordinal.
- [x] Staff bots still adopt by `planSlot` exactly as before (existing `BotStoreTest` green).

## What was built (as landed)

Design change from the ticket text: the roster columns live in their own `botroster` table, not in `botstore`.
`botstore`'s write path is the ledger's durability boundary (ticket 13 of the old series) and none of these
columns hold value, so nothing here touches `BotStore.save`/`writeRow`.

| Anchor | Change |
| --- | --- |
| `db/tables/026-botroster.sql`, `db/changelog-tables.xml` changeSet 26 | `botroster` (Status, Personality, Persona, PlayWindowStart/Len, SessionMinutes, HomeMapId, Activity, ActivityState, LastLoginAt, LastLogoutAt, EnrolledAt) and an `INSERT ... SELECT` that enrols every stored wave 8/9 bot, keeping its slot (its items are stored under that character id) |
| `soloMapling/ArtificialPlayer/BotRoster.java` | members by slot alone (`member`, `members`, `active`, `isMember`), `save` (upsert), `enroll` (idempotent), `nextNewSlot` (above every roster-block slot in use); `POPULATION_WAVES = {8, 9}` |
| `soloMapling/ArtificialPlayer/BotStore.java` | `FIRST_ROSTER_SLOT` (above the whole boot plan: `FIRST_PLANNED_SLOT + 65 * SLOTS_PER_WAVE`), `isRosterSlot`, `waveOf`. Roster slots count as planned, so `BotGeneration.createBot` already adopts a roster slot from its row - loading by slot needs no new path |
| `soloMapling/ArtificialPlayer/BotStoreReport.java` | `!roster status` shows roster counts, and a roster line per bot (status, home, activity, last login/logout) |
| `src/test/.../BotRosterTest.java` | 6 tests: migration constants pinned to the store's slot layout, roster slots above the plan, member by slot, next new slot, empty roster, enrol/save binding |

Scope decisions:
- Henesys fillers, JQ and social bots of waves 3-6 stay on the boot plan for now (they are placed scenery more
  than players); only waves 8 and 9 are the roster. Revisit when the director (ticket 08) lands.
- Waves 8/9 still spawn these bots at boot exactly as before until ticket 08 replaces them; the roster rows are
  inert until then.
- `HomeMapId`/`Activity` are blank for enrolled bots; ticket 06 writes them on logout.

## Verification

- Unit: `BotRosterTest` 6/6; `BotStoreTest` 32/32 and `BotStoreReportTest` 4/4 unchanged.
- Migration on real data (scratch schema `cosmic_t04_scratch` = a copy of the sandbox store's 3491 rows):
  enrolled **2508** = wave 8 (2390) + wave 9 (118); waves 1-7 (974 rows) and the 9 on-demand rows untouched;
  every member's `EnrolledAt = CreatedAt` and `LastLogoutAt = SavedAt`; levels 2-95.
