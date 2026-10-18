package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotNavigationGraphCacheKeyTest {

    private static final int EL_NATH_ICE = 211040000;

    private static BotNavigationGraphProvider.GraphCacheKey key(boolean snowShoes) {
        return new BotNavigationGraphProvider.GraphCacheKey(EL_NATH_ICE, 140, 123, snowShoes);
    }

    private static BotNavigationGraph graph(boolean snowShoes) {
        return new BotNavigationGraph(EL_NATH_ICE, BotNavigationGraphProvider.GRAPH_VERSION,
                new BotMovementProfile(140, 123, snowShoes), List.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    @Test
    void snowshoeAndPlainGraphsGetTheirOwnFiles() {
        assertNotEquals(BotNavigationGraphProvider.graphFile(key(true)),
                BotNavigationGraphProvider.graphFile(key(false)));
        // Plain files keep their old name, so an existing cache stays valid.
        assertEquals(EL_NATH_ICE + "-s140-j123.bin",
                BotNavigationGraphProvider.graphFile(key(false)).getFileName().toString());
    }

    @Test
    void eachGraphLoadsOnlyAsItsOwnKey() {
        assertTrue(BotNavigationGraphProvider.isGraphFor(graph(false), key(false)));
        assertTrue(BotNavigationGraphProvider.isGraphFor(graph(true), key(true)));
        // A snowshoe graph saved under the old shared name must not be loaded as the plain graph, or the reverse.
        assertFalse(BotNavigationGraphProvider.isGraphFor(graph(true), key(false)));
        assertFalse(BotNavigationGraphProvider.isGraphFor(graph(false), key(true)));
    }
}
