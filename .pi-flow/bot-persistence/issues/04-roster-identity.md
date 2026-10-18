# 04: Population bots get a roster identity of their own

**What to build:** Decouple population bots (today's wave 8 training cohorts and wave 9 town presence) from
`BotStore.planSlot`. Migration `026-botroster.sql` (Liquibase changeSet 26 in `db/changelog-tables.xml`) adds the
roster columns: `Status` (ACTIVE/DORMANT), `Personality` (JSON: seed, activity weights, chattiness),
`PlayWindowStart`, `PlayWindowLen`, `SessionMinutes`, `LastLoginAt`, `LastLogoutAt`, `HomeMapId`, `Activity`,
`ActivityState` (JSON), `Persona` (JSON). Roster ids get their own id range so they never collide with staff
slots; staff (FM, casino, tutorial, drop game, OPQ lobby) keep `planSlot` unchanged. Existing wave 8/9 rows
are imported as the initial roster.

**Blocked by:** 01.

**Status:** open

- [ ] Migration applies on a fresh DB and on a DB with existing `botstore` rows.
- [ ] Existing population rows become roster members with their name/level/items intact.
- [ ] A roster bot can be loaded by roster id alone, with no wave/task/ordinal.
- [ ] Staff bots still adopt by `planSlot` exactly as before (existing `BotStoreTest` green).
