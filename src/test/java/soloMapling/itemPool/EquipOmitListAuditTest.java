package soloMapling.itemPool;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.NXItemPool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** bot-persistence ticket 20: the generated broken-sprite block is read, and the NX overlay honours it. */
class EquipOmitListAuditTest {

    @Test
    void theGeneratedBrokenSpriteIdsAreOmitted() {
        EquipOmitList.load();
        // The four zmzeng/solomapling-plugin found crashing the client in heal / alert poses.
        for (int id : new int[]{1082065, 1082066, 1082067, 1332037}) {
            assertTrue(EquipOmitList.isOmitted(id), id + " must be omitted");
        }
        assertTrue(EquipOmitList.isOmitted(1002186), "an info-only cap");
        assertTrue(EquipOmitList.isOmitted(1302033), "the hand-written flag list still loads");
        assertFalse(EquipOmitList.isOmitted(1002187), "a sound cap next to it is not");
    }

    @Test
    void theNxOverlayNeverHandsOutAnOmittedCap() {
        EquipOmitList.load();
        NXItemPool.load();
        int drawn = 0;
        for (int i = 0; i < 4000; i++) {
            for (int gender = 0; gender <= 1; gender++) {
                Integer id = NXItemPool.getRandom("caps", gender);
                if (id != null) {
                    drawn++;
                    assertFalse(EquipOmitList.isOmitted(id), "caps handed out omitted " + id);
                }
            }
        }
        assertTrue(drawn > 0, "the caps pool loaded");
    }
}
