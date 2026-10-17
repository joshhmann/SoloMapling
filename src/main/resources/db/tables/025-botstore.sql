-- Durable store for the simulated bot population.
--
-- A bot is not a player: it has no login session, so it never passes the `loggedIn` gate that guards
-- character saves, and its identity is not something a player can recreate. It therefore gets its own
-- table instead of a row in `characters`, which also keeps bot ids out of the `characters`
-- AUTO_INCREMENT range - an inserted bot row would push the next real character past the bot id
-- threshold and get it classified as a bot everywhere.
--
-- BotId is the bot's creation slot, so a bot's character id stays BOT_ID_BASE + BotId (20100, 20101, ...)
-- for its whole life and a slot is re-adopted by the bot that takes that slot in the next run's spawn
-- order. The bot's type is deliberately not stored: the spawn wave that adopts a slot still owns which
-- type the bot is, so a bot re-homed by a config change does not come back claiming a role this run did
-- not give it.
--
-- Names are deliberately NOT unique: bot names come from a fixed pool that reshuffles when it runs out
-- (FMShopDescGen.getRandomIGN), the pool is smaller than one environment load, and duplicate names are
-- harmless because BotId is the identity. A unique key here would reject every bot past the first pool
-- wrap instead.
CREATE TABLE botstore
(
    BotId      INT UNSIGNED NOT NULL AUTO_INCREMENT,
    Name       VARCHAR(13)  NOT NULL,
    Tier       VARCHAR(8)   NOT NULL,
    Level      INT UNSIGNED NOT NULL,
    Exp        INT UNSIGNED NOT NULL,
    Meso       INT UNSIGNED NOT NULL,
    Job        INT UNSIGNED NOT NULL,
    Skin       INT UNSIGNED NOT NULL,
    Gender     TINYINT UNSIGNED NOT NULL,
    Hair       INT UNSIGNED NOT NULL,
    Face       INT UNSIGNED NOT NULL,
    MapId      INT UNSIGNED NOT NULL,
    PosX       INT          NOT NULL,
    PosY       INT          NOT NULL,
    EquipSlots TINYINT UNSIGNED NOT NULL,
    UseSlots   TINYINT UNSIGNED NOT NULL,
    SetupSlots TINYINT UNSIGNED NOT NULL,
    EtcSlots   TINYINT UNSIGNED NOT NULL,
    CreatedAt  BIGINT       NOT NULL DEFAULT '0',
    SavedAt    BIGINT       NOT NULL DEFAULT '0',
    PRIMARY KEY (BotId)
) AUTO_INCREMENT = 100;
