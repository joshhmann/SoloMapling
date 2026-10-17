package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.function.IntUnaryOperator;

/**
 * The accrual arithmetic behind ticket 07 rule 2: what a bot earns while nobody is watching is applied to
 * its character silently, atomically and with its stats re-derived, and the only thing an observer ever
 * sees is the new level in the bot's next spawn packet.
 * <p>
 * Kept a pure function so the multi-level, level-cap and overflow behaviour can be verified without a
 * character, a map or a server.
 */
public final class OffscreenProgression {

    private OffscreenProgression() {
    }

    /**
     * @param level      the bot's level before the accrual
     * @param exp          the bot's exp before the accrual
     * @param gain       exp earned while unobserved (no gain at all is a no-op)
     * @param levelCap   the level the bot must not pass (the bot grind cap, not the server cap)
     * @param expNeededForLevel the exp table to level up from a given level
     * @return the level and exp to write, plus how many levels were crossed
     */
    public record Gain(int level, int exp, int levelsGained) {
    }

    public static Gain accrue(int level, int exp, long gain, int levelCap, IntUnaryOperator expNeededForLevel) {
        if (gain <= 0) {
            return new Gain(level, exp, 0);
        }
        long total = (long) exp + gain;
        int startLevel = level;
        while (level < levelCap) {
            int need = expNeededForLevel.applyAsInt(level);
            if (need <= 0 || total < need) {
                break;
            }
            total -= need;
            level++;
        }
        return new Gain(level, (int) Math.min(total, Integer.MAX_VALUE), level - startLevel);
    }
}
