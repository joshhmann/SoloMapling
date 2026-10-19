package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotNamesTest {

    @AfterEach
    void restoreRealSources() {
        BotNames.useSourcesForTest(null);
    }

    private static void sources(List<String> pool, List<String> realPlayers, List<BotNames.StoredName> stored) {
        BotNames.useSourcesForTest(new BotNames.Sources(() -> pool, () -> realPlayers, () -> stored, new Random(7)));
    }

    @Test
    void thePoolKeepsOnlyLegalNamesOnceEachIgnoringCase() {
        List<String> filtered = BotNames.filterPool(List.of("﻿Sparkshooter", "  Nayrb ", "nayrb", "NAYRB",
                "rubiksmaster123", "ab", "xX_Dark_Xx", "# a comment", "", "Kimchi Lord", "neiljay"));
        assertEquals(List.of("Sparkshooter", "Nayrb", "neiljay"), filtered);
    }

    @Test
    void newNamesAreNeverRepeatedAndNeverARealPlayersName() {
        sources(List.of("Alpha", "Bravo", "Charlie", "Delta"), List.of("bravo"), List.of());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            String name = BotNames.claimNew(30_000 + i);
            assertTrue(seen.add(name.toLowerCase(Locale.ROOT)), "repeated " + name);
            assertNotEquals("bravo", name.toLowerCase(Locale.ROOT));
        }
        assertTrue(BotNames.isTaken("ALPHA") || BotNames.isTaken("charlie") || BotNames.isTaken("delta"));
        assertTrue(BotNames.isTaken("Bravo"), "a real player's name counts as taken");
    }

    @Test
    void anExhaustedPoolStillYieldsLegalUniqueNames() {
        sources(List.of("Maple", "Leaf"), List.of(), List.of());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String name = BotNames.claimNew(40_000 + i);
            assertTrue(BotNames.LEGAL.matcher(name).matches(), name);
            assertTrue(seen.add(name.toLowerCase(Locale.ROOT)), "repeated " + name);
        }
    }

    @Test
    void theOldestStoredHolderKeepsASharedNameAndLaterOnesAreRenamed() {
        sources(List.of("Fresh", "Other"), List.of("Taken"), List.of(
                new BotNames.StoredName(20_200, "Celare", 2_000L),
                new BotNames.StoredName(20_100, "Celare", 1_000L),
                new BotNames.StoredName(20_300, "Taken", 500L)));

        assertEquals("Celare", BotNames.claimStored(20_100, "Celare"), "the oldest keeps it");
        String renamed = BotNames.claimStored(20_200, "Celare");
        assertNotEquals("celare", renamed.toLowerCase(Locale.ROOT));
        String notThePlayers = BotNames.claimStored(20_300, "Taken");
        assertNotEquals("taken", notThePlayers.toLowerCase(Locale.ROOT), "a real player wins over a bot");
        // Asking again is stable: the bot keeps the name it was given.
        assertEquals(renamed, BotNames.claimStored(20_200, renamed));
    }

    @Test
    void aStoredNameNobodyHoldsIsKeptAndThenReserved() {
        sources(List.of("Fresh"), List.of(), List.of());
        assertEquals("Wanderer", BotNames.claimStored(20_500, "Wanderer"));
        assertTrue(BotNames.isTaken("wanderer"));
        assertNotEquals("Wanderer", BotNames.claimNew(20_501));
    }

    @Test
    void aPlayerCannotTakeABotsNameAndANameAPlayerWantsIsNeverGivenToABot() {
        sources(List.of("Wanted", "Spare"), List.of(), List.of(new BotNames.StoredName(20_100, "Celare", 1)));
        assertFalse(BotNames.reserveForPlayer("celare"), "a bot holds it");
        assertTrue(BotNames.reserveForPlayer("Wanted"), "free: reserved for the player");
        assertTrue(BotNames.reserveForPlayer("wanted"), "the same player checking again");
        for (int i = 0; i < 20; i++) {
            assertNotEquals("wanted", BotNames.claimNew(30_000 + i).toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void variantsAreLegal() {
        sources(List.of("Sparkshooter", "Nayrb"), List.of(), List.of());
        BotNames.poolSize();
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String v = BotNames.variant(i % 2 == 0 ? "Sparkshooter" : "Nayrb");
            if (v != null) {
                made.add(v);
                assertTrue(BotNames.LEGAL.matcher(v).matches(), v);
            }
        }
        assertFalse(made.isEmpty());
    }
}
