package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Pure-registry tests for the claim/slot/section math the grind system leans on to spread bots
// across spots (and, past the cap, to level sharers instead of stacking them). The registry is
// global static state, so every test uses its own map id.
class BotSpotClaimsTest {

    private static final Field CLAIMS_FIELD = claimsField();

    private static Field claimsField() {
        try {
            Field field = BotSpotClaims.class.getDeclaredField("CLAIMS");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the registry still has an entry for a map at all (not just "no holders"): internals, by design. */
    private static boolean mapEntryExists(int mapId) throws Exception {
        return ((Map<?, ?>) CLAIMS_FIELD.get(null)).containsKey(mapId);
    }

    @Test
    void releaseAllForFreesABotEverywhereAndLeavesOtherHoldersAlone() throws Exception {
        int mapA = 990_010;
        int mapB = 990_011;
        assertEquals(0, BotSpotClaims.claim(mapA, 0, 4, 31));
        assertEquals(0, BotSpotClaims.claim(mapB, 7, 4, 31)); // same bot, another spot: slots are per spot
        assertEquals(1, BotSpotClaims.claim(mapB, 7, 4, 32)); // a different bot takes the spot's free slot

        // despawn: every claim of the departed bot goes at once, whichever map it was on
        assertEquals(2, BotSpotClaims.releaseAllFor(31));

        assertEquals(0, BotSpotClaims.holders(mapA, 0));
        assertEquals(1, BotSpotClaims.holders(mapB, 7));
        // the map it was the only holder on is pruned, not left as an empty shell the registry keeps
        assertFalse(mapEntryExists(mapA));
        assertTrue(mapEntryExists(mapB));
        // the freed slot is handed to the next bot (slot 0 of mapA is free again)
        assertEquals(0, BotSpotClaims.claim(mapA, 0, 4, 33));
        assertEquals(0, BotSpotClaims.claim(mapB, 7, 4, 34)); // ...and the slot 31 freed on mapB too
        // a second release for the same bot (removeBotFromServer can be reached twice) is a no-op
        assertEquals(0, BotSpotClaims.releaseAllFor(31));

        BotSpotClaims.release(mapA, 0, 33);
        BotSpotClaims.release(mapB, 7, 32);
        BotSpotClaims.release(mapB, 7, 34);
    }

    @Test
    void releaseAllForAnUnknownBotReleasesNothing() throws Exception {
        int map = 990_012;
        assertEquals(0, BotSpotClaims.claim(map, 2, 2, 41));

        assertEquals(0, BotSpotClaims.releaseAllFor(999_999));

        assertEquals(1, BotSpotClaims.holders(map, 2));
        assertTrue(mapEntryExists(map)); // the registry was left exactly as it was
        assertEquals(1, BotSpotClaims.claim(map, 2, 2, 42)); // slot 0 still taken by 41
        BotSpotClaims.release(map, 2, 41);
        BotSpotClaims.release(map, 2, 42);
    }

    @Test
    void capacityIsEnforcedAndSquatBypassesIt() {
        int map = 990_001;
        assertEquals(0, BotSpotClaims.claim(map, 0, 2, 11));
        assertEquals(1, BotSpotClaims.claim(map, 0, 2, 12));
        // spot full at capacity -> refused
        assertEquals(-1, BotSpotClaims.claim(map, 0, 2, 13));
        // the saturated-share path registers with an uncapped claim: always lands, lowest free slot
        assertEquals(2, BotSpotClaims.claim(map, 0, Integer.MAX_VALUE, 13));
        assertEquals(3, BotSpotClaims.holders(map, 0));
        BotSpotClaims.release(map, 0, 11);
        BotSpotClaims.release(map, 0, 12);
        BotSpotClaims.release(map, 0, 13);
        assertEquals(0, BotSpotClaims.holders(map, 0));
    }

    @Test
    void reclaimBySameBotReturnsExistingSlot() {
        int map = 990_002;
        int slot = BotSpotClaims.claim(map, 5, 3, 42);
        assertEquals(slot, BotSpotClaims.claim(map, 5, 3, 42));
        assertEquals(1, BotSpotClaims.holders(map, 5));
        BotSpotClaims.release(map, 5, 42);
    }

    @Test
    void slotsAreStableWhenAnotherHolderLeaves() {
        int map = 990_003;
        assertEquals(0, BotSpotClaims.claim(map, 1, 3, 21));
        assertEquals(1, BotSpotClaims.claim(map, 1, 3, 22));
        assertEquals(2, BotSpotClaims.claim(map, 1, 3, 23));
        BotSpotClaims.release(map, 1, 22); // middle holder leaves
        // survivors keep their slots (their sections must never shift under them)
        assertEquals(0, BotSpotClaims.claim(map, 1, 3, 21));
        assertEquals(2, BotSpotClaims.claim(map, 1, 3, 23));
        // a newcomer takes the freed middle slot
        assertEquals(1, BotSpotClaims.claim(map, 1, 3, 24));
        BotSpotClaims.release(map, 1, 21);
        BotSpotClaims.release(map, 1, 23);
        BotSpotClaims.release(map, 1, 24);
    }

    @Test
    void sectionsPartitionTheSpanWithoutOverlapOrGaps() {
        int minX = -600, maxX = 400, cap = 3;
        int prevEnd = minX;
        for (int slot = 0; slot < cap; slot++) {
            int[] band = BotSpotClaims.section(minX, maxX, slot, cap);
            assertEquals(prevEnd, band[0]); // starts where the previous band ended (no gap/overlap)
            assertTrue(band[1] > band[0]);
            prevEnd = band[1];
        }
        assertEquals(maxX, prevEnd); // bands cover the whole span
        // capacity 1 = the whole span
        int[] whole = BotSpotClaims.section(minX, maxX, 0, 1);
        assertEquals(minX, whole[0]);
        assertEquals(maxX, whole[1]);
    }
}
