package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotPersonalityTest {

    @Test
    void traitsAreAFunctionOfTheSeedAndSurviveTheirColumns() {
        BotPersonality.Traits a = BotPersonality.generate(557241, BotPersonality.Activity.GRIND);
        BotPersonality.Traits b = BotPersonality.generate(557241, BotPersonality.Activity.GRIND);
        assertEquals(a, b);
        assertEquals(1.0, a.grind() + a.town() + a.social(), 1e-9);
        BotPersonality.Traits back = BotPersonality.parse(a.toJson(), a.playWindowStart(), a.playWindowLen(),
                a.sessionMinutes());
        assertEquals(a.seed(), back.seed());
        assertEquals(a.grind(), back.grind(), 1e-3);
        assertEquals(a.chattiness(), back.chattiness(), 1e-3);
        assertNull(BotPersonality.parse(null, 0, 0, 0));
    }

    @Test
    void aHabitTiltsTheMixSoAMigratedPopulationKeepsItsCharacter() {
        double grinders = 0, townies = 0;
        for (long seed = 0; seed < 2000; seed++) {
            grinders += BotPersonality.generate(seed, BotPersonality.Activity.GRIND).grind();
            townies += BotPersonality.generate(seed, BotPersonality.Activity.TOWN).town();
        }
        assertTrue(grinders / 2000 > 0.6, "grinders mostly grind: " + grinders / 2000);
        assertTrue(townies / 2000 > 0.4, "town bots mostly hang in town: " + townies / 2000);
    }

    @Test
    void sessionsFollowTheWeights() {
        BotPersonality.Traits traits = new BotPersonality.Traits(1, 0.6, 0.1, 0.3, 0.5, 0, 120, 60);
        Map<BotPersonality.Activity, Integer> counts = new EnumMap<>(BotPersonality.Activity.class);
        Random rng = new Random(3);
        for (int i = 0; i < 20_000; i++) {
            counts.merge(BotPersonality.choose(traits, rng), 1, Integer::sum);
        }
        assertEquals(0.6, counts.get(BotPersonality.Activity.GRIND) / 20_000.0, 0.02);
        assertEquals(0.1, counts.get(BotPersonality.Activity.TOWN) / 20_000.0, 0.02);
        assertEquals(0.3, counts.get(BotPersonality.Activity.SOCIAL) / 20_000.0, 0.02);
    }

    @Test
    void mostPlayersPlayInTheEveningAndSessionsFitTheirWindow() {
        int evening = 0;
        for (long seed = 0; seed < 5000; seed++) {
            BotPersonality.Traits t = BotPersonality.generate(seed, null);
            assertTrue(t.playWindowStart() >= 0 && t.playWindowStart() < 24 * 60);
            assertTrue(t.sessionMinutes() <= t.playWindowLen() && t.sessionMinutes() >= 40);
            if (t.playWindowStart() >= 17 * 60 && t.playWindowStart() < 21 * 60) {
                evening++;
            }
        }
        assertEquals(0.55, evening / 5000.0, 0.03);
    }

    @Test
    void recordedNamesAreTheBotTypesOwnSnapshotNames() {
        // BotSM.activitySnapshot's default name: the class name without "Bot", lowercased.
        assertEquals(BotPersonality.Activity.TOWN,
                BotPersonality.Activity.fromRecorded("TownWandererBot".replace("Bot", "").toLowerCase(Locale.ROOT)));
        assertEquals(BotPersonality.Activity.SOCIAL,
                BotPersonality.Activity.fromRecorded("SocialBot".replace("Bot", "").toLowerCase(Locale.ROOT)));
        assertEquals(BotPersonality.Activity.GRIND, BotPersonality.Activity.fromRecorded("grind"));
        assertNull(BotPersonality.Activity.fromRecorded(""));
        assertEquals(BotTypeManager.BotType.TRAINING_BOT, BotPersonality.Activity.GRIND.type);
    }
}
