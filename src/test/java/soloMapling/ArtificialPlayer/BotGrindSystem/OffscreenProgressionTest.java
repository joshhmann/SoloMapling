package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Ticket 07 rule 2, the accrual half: what an unobserved bot earns is applied in one step, spans as many
// levels as it pays for, stops at the grind cap, and never wraps into a negative exp.
class OffscreenProgressionTest {

    // 100 exp per level, so the arithmetic in each case is readable.
    private static final IntUnaryOperator NEEDS = level -> 100;

    @Test
    void aPartialLevelLeavesTheLevelAlone() {
        OffscreenProgression.Gain gained = OffscreenProgression.accrue(30, 40, 25, 195, NEEDS);

        assertEquals(30, gained.level());
        assertEquals(65, gained.exp());
        assertEquals(0, gained.levelsGained());
    }

    @Test
    void exactlyEnoughExpLevelsUpWithoutLeftover() {
        OffscreenProgression.Gain gained = OffscreenProgression.accrue(30, 60, 40, 195, NEEDS);

        assertEquals(31, gained.level());
        assertEquals(0, gained.exp());
        assertEquals(1, gained.levelsGained());
    }

    @Test
    void aSlowTickCanCrossSeveralLevelsAtOnce() {
        // 30 + 10 exp, 380 more: 100 per level, so level 33 keeps 390 - 300 = 90 exp
        OffscreenProgression.Gain gained = OffscreenProgression.accrue(30, 10, 380, 195, NEEDS);

        assertEquals(33, gained.level());
        assertEquals(90, gained.exp());
        assertEquals(3, gained.levelsGained());
    }

    @Test
    void theGrindCapIsNeverPassedAndTheSurplusExpIsKept() {
        OffscreenProgression.Gain gained = OffscreenProgression.accrue(194, 50, 1_000_000, 195, NEEDS);

        assertEquals(195, gained.level());
        assertEquals(999_950, gained.exp());
        assertEquals(1, gained.levelsGained());
    }

    @Test
    void surplusExpSaturatesInsteadOfWrappingNegative() {
        OffscreenProgression.Gain gained = OffscreenProgression.accrue(195, Integer.MAX_VALUE - 5, 50, 195, NEEDS);

        assertEquals(195, gained.level());
        assertEquals(Integer.MAX_VALUE, gained.exp());
        assertEquals(0, gained.levelsGained());
    }

    @Test
    void noGainIsANoOp() {
        assertEquals(new OffscreenProgression.Gain(42, 7, 0), OffscreenProgression.accrue(42, 7, 0, 195, NEEDS));
        assertEquals(new OffscreenProgression.Gain(42, 7, 0), OffscreenProgression.accrue(42, 7, -900, 195, NEEDS));
    }
}
