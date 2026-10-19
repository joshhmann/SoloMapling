package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRosterLifecycleTest {

    private static final BotRosterLifecycle.Rates RATES =
            new BotRosterLifecycle.Rates(7200, 40, 0.5, 300, 0.002, 0.01);

    private static List<BotRoster.Member> roster(int active) {
        List<BotRoster.Member> members = new ArrayList<>();
        for (int i = 0; i < active; i++) {
            members.add(BotRoster.Member.enrolled(BotStore.FIRST_ROSTER_SLOT + i, 0));
        }
        return members;
    }

    private static int count(List<BotRoster.Member> members, BotRoster.Status status) {
        return (int) members.stream().filter(m -> m.status() == status).count();
    }

    /** Lives a roster through {@code days}, an hourly step at a time, like the director drives it. */
    private static int[] live(List<BotRoster.Member> members, int days, Random rng) {
        int made = 0, veterans = 0, quits = 0, returns = 0;
        int next = BotStore.FIRST_ROSTER_SLOT + members.size();
        for (int hour = 0; hour < days * 24; hour++) {
            BotRosterLifecycle.Step step = BotRosterLifecycle.plan(members, Set.of(), RATES, 1.0, rng);
            Set<Integer> quit = new HashSet<>(step.quit());
            Set<Integer> back = new HashSet<>(step.returned());
            for (int i = 0; i < members.size(); i++) {
                BotRoster.Member m = members.get(i);
                if (quit.contains(m.botId())) {
                    members.set(i, m.withStatus(BotRoster.Status.DORMANT));
                } else if (back.contains(m.botId())) {
                    members.set(i, m.withStatus(BotRoster.Status.ACTIVE));
                }
            }
            for (int i = 0; i < step.newPlayers() + step.veterans(); i++) {
                members.add(BotRoster.Member.enrolled(next++, 0));
            }
            made += step.newPlayers();
            veterans += step.veterans();
            quits += quit.size();
            returns += back.size();
        }
        return new int[]{made, veterans, quits, returns};
    }

    @Test
    void aSmallRosterIsFilledWithVeteransThenGrowsWithBeginnersToItsSize() {
        List<BotRoster.Member> members = roster(2508);
        Random rng = new Random(4);
        int[] firstWeek = live(members, 7, rng);
        assertTrue(firstWeek[1] > 900, "veterans fill the gap first: " + firstWeek[1]);
        assertTrue(count(members, BotRoster.Status.ACTIVE) >= 3550, "half the size within a week");

        int[] year = live(members, 358, rng);
        int active = count(members, BotRoster.Status.ACTIVE);
        assertTrue(Math.abs(active - 7200) <= 150, "the active roster settles at its size: " + active);
        assertTrue(count(members, BotRoster.Status.DORMANT) > 300, "some players quit and stay quit");
        assertTrue(year[3] > 100, "and some come back: " + year[3]);
        assertTrue(year[0] > 1000, "beginners keep arriving: " + year[0]);
    }

    @Test
    void nobodyQuitsWhileOnlineAndAFullRosterMakesNoOne() {
        List<BotRoster.Member> members = roster(7200);
        Set<Integer> everyoneOnline = new HashSet<>();
        members.forEach(m -> everyoneOnline.add(m.botId()));
        BotRosterLifecycle.Step step = BotRosterLifecycle.plan(members, everyoneOnline,
                new BotRosterLifecycle.Rates(7200, 40, 0.5, 300, 0.5, 0.01), 24, new Random(1));
        assertEquals(0, step.quit().size());
        assertEquals(0, step.newPlayers() + step.veterans());
    }

    @Test
    void aReturningPlayerKeepsEverythingButItsStatus() {
        BotRoster.Member before = BotRoster.Member.enrolled(BotStore.planSlot(8, 2, 3), 99L)
                .withSession(new BotRoster.Activity("grind", 100000000, java.util.Map.of()), 500L)
                .withStatus(BotRoster.Status.DORMANT);
        BotRoster.Member after = before.withStatus(BotRoster.Status.ACTIVE);
        assertEquals(before.withStatus(BotRoster.Status.ACTIVE), after);
        assertEquals(before.homeMapId(), after.homeMapId());
        assertEquals(before.enrolledAt(), after.enrolledAt());
        assertEquals("grind", after.activity());
    }

    @Test
    void fractionalRatesStillHappenOnAverage() {
        Random rng = new Random(2);
        int total = 0;
        for (int i = 0; i < 24_000; i++) {
            total += BotRosterLifecycle.draws(40 / 24.0, rng);
        }
        assertEquals(40_000, total, 600);
    }
}
