package client;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// A character's ring lists are shared between the bot tick thread (which builds spawn packets
// through PacketCreator.addRingLook -> Character.getCrushRings, a call that sorts) and whichever
// thread registers a ring (Character.addCrushRing). getCrushRings() used to sort the live field and
// return it, so a write landing during that sort -- or during a caller's walk of the returned list --
// threw ConcurrentModificationException out of ArrayList.sort. Both properties the fix rests on are
// pinned here: the getter must never expose the live field, and sorting must never touch a list a
// writer can reach. The first two tests reproduce the structural modification deterministically
// instead of racing threads.
class CharacterRingListConcurrencyTest {

    private static final int CRUSH_RING_ITEM_ID = 1112400;      // <= 1112012: a crush ring
    private static final int FRIENDSHIP_RING_ITEM_ID = 1112800; // > 1112012: a friendship ring

    // The crash frame: the caller holds the list the getter handed out and walks it while another
    // thread registers a ring. With a live field the iterator throws ConcurrentModificationException;
    // with a snapshot it must not even see the new ring.
    @Test
    void crushRingGetterHandsOutAListAConcurrentWriteCannotBreak() {
        Character chr = new Character();
        chr.addCrushRing(ring(1));

        List<Ring> rings = chr.getCrushRings();
        Iterator<Ring> it = rings.iterator();
        chr.addCrushRing(ring(2));

        int seen = 0;
        while (it.hasNext()) {
            it.next();
            seen++;
        }

        assertEquals(1, seen);
        assertEquals(2, chr.getCrushRings().size());
    }

    // The sort itself: the ring's own compareTo stands in for the writer thread, so the write lands
    // inside ArrayList.sort every time rather than once in a few runs. Sorting the live field lets
    // that write move the field's modCount under Arrays.sort and throws; sorting a private copy
    // cannot be affected by it.
    @Test
    void crushRingSortIgnoresWritesToTheSharedField() {
        Character chr = new Character();
        boolean[] written = {false};
        // every ring hooks compareTo, so whichever element Arrays.sort (TimSort's binary insertion of
        // the pivots at index >= 1) compares first is the writer, as one writer thread would be
        chr.addCrushRing(comparingRing(chr, written, 3));
        chr.addCrushRing(comparingRing(chr, written, 1));
        chr.addCrushRing(comparingRing(chr, written, 2));

        List<Ring> sorted = chr.getCrushRings();

        assertEquals(3, sorted.size());          // the ring the hook added landed in the field, not here
        assertEquals(1, sorted.get(0).getRingId());
        assertEquals(2, sorted.get(1).getRingId());
        assertEquals(3, sorted.get(2).getRingId());
        assertEquals(4, chr.getCrushRings().size());
    }

    private static Ring comparingRing(Character chr, boolean[] written, int ringId) {
        return new Ring(ringId, ringId + 100000, ringId + 200000, CRUSH_RING_ITEM_ID, "p" + ringId) {
            @Override
            public int compareTo(Ring other) {
                if (!written[0]) {
                    written[0] = true;
                    chr.addCrushRing(ring(1000));
                }
                return super.compareTo(other);
            }
        };
    }

    // Same shape as the first test, over the friendship list, which PacketCreator.addRingLook reaches
    // through the very next line of the same spawn-packet build.
    @Test
    void friendshipRingGetterHandsOutAListAConcurrentWriteCannotBreak() {
        Character chr = new Character();
        chr.addFriendshipRing(ring(FRIENDSHIP_RING_ITEM_ID, 1));

        List<Ring> rings = chr.getFriendshipRings();
        Iterator<Ring> it = rings.iterator();
        chr.addFriendshipRing(ring(FRIENDSHIP_RING_ITEM_ID, 2));

        int seen = 0;
        while (it.hasNext()) {
            it.next();
            seen++;
        }

        assertEquals(1, seen);
        assertEquals(2, chr.getFriendshipRings().size());
    }

    // The real race, in-process: writers registering rings while a reader keeps building the list the
    // spawn packet would walk. Threads are joined before anything is asserted, so the outcome is
    // deterministic even though the interleaving is not.
    @Test
    void concurrentWritesAndReadsNeitherThrowNorLoseRings() throws Exception {
        final Character chr = new Character();
        final int writers = 4;
        final int ringsPerWriter = 50;
        final AtomicReference<Throwable> readerFailure = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 5000; i++) {
                    for (Ring r : chr.getCrushRings()) {
                        if (r == null) {
                            throw new IllegalStateException("ring list held a hole");
                        }
                    }
                }
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        });
        reader.start();

        Thread[] writeThreads = new Thread[writers];
        for (int w = 0; w < writers; w++) {
            final int base = w * ringsPerWriter;
            writeThreads[w] = new Thread(() -> {
                for (int i = 0; i < ringsPerWriter; i++) {
                    chr.addCrushRing(ring(base + i + 1));
                }
            });
            writeThreads[w].start();
        }

        for (Thread t : writeThreads) {
            t.join();
        }
        reader.join();

        assertNull(readerFailure.get(), "reader thread failed: " + readerFailure.get());
        assertEquals(writers * ringsPerWriter, chr.getCrushRings().size());
    }

    private static Ring ring(int ringId) {
        return ring(CRUSH_RING_ITEM_ID, ringId);
    }

    private static Ring ring(int itemId, int ringId) {
        return new Ring(ringId, ringId + 100000, ringId + 200000, itemId, "p" + ringId);
    }
}
