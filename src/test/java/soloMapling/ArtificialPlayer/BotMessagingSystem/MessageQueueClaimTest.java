package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageQueueClaimTest {

    private static Character player(int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Player" + id);
        return chr;
    }

    private static Predicate<ChatMessage> from(Character player) {
        return m -> m.getSender().getId() == player.getId();
    }

    @Test
    void aBotTakesOnlyItsOwnPlayersLineAndLeavesTheOthers() {
        Character alice = player(1001);
        Character bob = player(1002);
        LinkedBlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>();
        ChatMessage bobsLine = new ChatMessage(bob, "hi bob's bot");
        ChatMessage alicesLine = new ChatMessage(alice, "hi alice's bot");
        queue.add(bobsLine);
        queue.add(alicesLine);

        // Bob's line is at the head; a plain poll would hand it to Alice's bot, which would then drop it.
        assertSame(alicesLine, MessageQueue.claim(queue, from(alice)));
        assertEquals(List.of(bobsLine), new ArrayList<>(queue));
        assertSame(bobsLine, MessageQueue.claim(queue, from(bob)));
        assertTrue(queue.isEmpty());
    }

    @Test
    void nothingWantedLeavesTheQueueUntouched() {
        LinkedBlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>();
        ChatMessage line = new ChatMessage(player(1003), "anyone?");
        queue.add(line);

        assertNull(MessageQueue.claim(queue, from(player(1004))));
        assertEquals(List.of(line), new ArrayList<>(queue));
    }

    @Test
    void aLineTwoBotsBothWantGoesToExactlyOne() throws InterruptedException {
        Character carol = player(1005);
        for (int round = 0; round < 200; round++) {
            ConcurrentLinkedQueue<ChatMessage> queue = new ConcurrentLinkedQueue<>();
            queue.add(new ChatMessage(carol, "line " + round));
            AtomicInteger winners = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> bots = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Thread bot = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (MessageQueue.claim(queue, from(carol)) != null) {
                        winners.incrementAndGet();
                    }
                });
                bot.start();
                bots.add(bot);
            }
            start.countDown();
            for (Thread bot : bots) {
                bot.join();
            }
            assertEquals(1, winners.get(), "round " + round);
        }
    }

    @Test
    void pollForWaitsForItsOwnLineAndTimesOutOnOthers() throws InterruptedException {
        Character dave = player(1006);
        Character erin = player(1007);
        MessageQueue queue = MessageQueue.getInstance();
        ChatMessage erinsLine = new ChatMessage(erin, "for erin's bot");
        queue.addMessage("secondary", erinsLine);
        try {
            long started = System.nanoTime();
            assertNull(queue.pollFor("secondary", from(dave), 150, TimeUnit.MILLISECONDS));
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(140));

            Thread late = new Thread(() -> queue.addMessage("secondary", new ChatMessage(dave, "late")));
            late.start();
            ChatMessage got = queue.pollFor("secondary", from(dave), 2, TimeUnit.SECONDS);
            assertEquals("late", got.getContent());
            assertSame(erinsLine, queue.pollFor("secondary", from(erin), 0, TimeUnit.MILLISECONDS));
        } finally {
            queue.getQueue("secondary").removeIf(m -> m.getSender() == dave || m.getSender() == erin);
        }
    }
}
