-- The roster: population bots that are players with a life of their own, not positions in the boot plan
-- (bot-persistence ticket 04). A roster bot keeps its botstore row - identity, progression, holdings - and
-- this table adds what makes it a player: whether it still plays, its personality and persona, when it
-- usually plays, where home is, and what it was doing when it logged out.
--
-- Kept apart from botstore on purpose: botstore's write path is the ledger's durability boundary, and none of
-- these columns hold value.
--
-- BotId is the same slot as botstore.BotId (character id = BOT_ID_BASE + BotId). Bots enrolled by this
-- migration keep their boot-plan slot, because their items are stored under that character id; new roster
-- bots take slots above the whole boot plan (BotStore.FIRST_ROSTER_SLOT).
CREATE TABLE botroster
(
    BotId           INT UNSIGNED      NOT NULL,
    Status          VARCHAR(8)        NOT NULL DEFAULT 'ACTIVE',
    Personality     TEXT              NULL,
    Persona         TEXT              NULL,
    PlayWindowStart SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PlayWindowLen   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    SessionMinutes  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    HomeMapId       INT UNSIGNED      NOT NULL DEFAULT 0,
    Activity        VARCHAR(16)       NOT NULL DEFAULT '',
    ActivityState   TEXT              NULL,
    LastLoginAt     BIGINT            NOT NULL DEFAULT 0,
    LastLogoutAt    BIGINT            NOT NULL DEFAULT 0,
    EnrolledAt      BIGINT            NOT NULL DEFAULT 0,
    PRIMARY KEY (BotId)
);

-- Enrol the population the boot plan already has: waves 8 (training cohorts) and 9 (town presence).
-- 8292 is BotStore.FIRST_PLANNED_SLOT and 65536 is BotStore.SLOTS_PER_WAVE; BotRosterTest pins both.
INSERT INTO botroster (BotId, EnrolledAt, LastLogoutAt)
SELECT BotId, CreatedAt, SavedAt
FROM botstore
WHERE BotId >= 8292
  AND FLOOR((BotId - 8292) / 65536) IN (8, 9);
