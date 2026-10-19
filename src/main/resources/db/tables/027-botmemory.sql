-- What bots remember about the players they talk to (bot-persistence ticket 14): short notes written by the bot's
-- brain when a conversation ends. BotId is the bot's slot (character id = BOT_ID_BASE + BotId); AboutCharacterId is
-- the real player's character id. Source DIRECT = the bot was there; HEARSAY = another bot told it (ticket 15),
-- with FromBotId and the number of hops it travelled.
CREATE TABLE botmemory
(
    Id               INT UNSIGNED     NOT NULL AUTO_INCREMENT,
    BotId            INT UNSIGNED     NOT NULL,
    AboutCharacterId INT UNSIGNED     NOT NULL,
    Note             VARCHAR(160)     NOT NULL,
    Source           VARCHAR(8)       NOT NULL DEFAULT 'DIRECT',
    FromBotId        INT UNSIGNED     NOT NULL DEFAULT 0,
    Hops             TINYINT UNSIGNED NOT NULL DEFAULT 0,
    CreatedAt        BIGINT           NOT NULL DEFAULT 0,
    LastUsedAt       BIGINT           NOT NULL DEFAULT 0,
    PRIMARY KEY (Id),
    KEY bot_about (BotId, AboutCharacterId)
);
