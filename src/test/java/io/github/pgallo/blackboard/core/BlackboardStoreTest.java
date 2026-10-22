package io.github.pgallo.blackboard.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackboardStoreTest {

    private final BlackboardStore board = new BlackboardStore();

    @Test
    void createStampsVersionOneAndRejectsDuplicates() {
        WriteResult r = board.create(Hypothesis.of("T/1", Level.TRACK, 0.4), "ks-a");
        assertEquals(WriteResult.Status.CREATED, r.status());
        assertEquals(1L, r.current().version());
        assertEquals("ks-a", r.current().author());

        WriteResult again = board.create(Hypothesis.of("T/1", Level.TRACK, 0.9), "ks-b");
        assertEquals(WriteResult.Status.EXISTS, again.status());
        assertEquals(0.4, again.current().belief(), 0.0, "first writer wins");
        assertEquals(1L, board.head(), "a rejected create leaves no journal entry");
    }

    @Test
    void compareAndSetSucceedsOnlyOnTheVersionThatWasRead() {
        Hypothesis v1 = board.create(Hypothesis.of("O/1", Level.OBJECT, 0.5), "a").current();

        WriteResult ok = board.compareAndSet(v1.withBelief(0.7), "b");
        assertEquals(WriteResult.Status.UPDATED, ok.status());
        assertEquals(2L, ok.current().version());

        // A second writer that also read v1 loses, and is handed the current value.
        WriteResult stale = board.compareAndSet(v1.withBelief(0.1), "c");
        assertEquals(WriteResult.Status.CONFLICT, stale.status());
        assertEquals(0.7, stale.current().belief(), 0.0);
        assertEquals(2L, stale.current().version());
        assertEquals(1L, board.stats().casConflicts());
    }

    @Test
    void identicalContentIsSuppressedWithoutVersionBumpOrEvent() {
        Hypothesis v1 = board.create(Hypothesis.of("O/1", Level.OBJECT, 0.5).withTag("k", "v"), "a").current();
        long head = board.head();
        WriteResult r = board.compareAndSet(v1.withTag("k", "v"), "b");
        assertEquals(WriteResult.Status.UNCHANGED, r.status());
        assertEquals(1L, r.current().version());
        assertEquals(head, board.head());
        assertEquals(1L, board.stats().suppressedNoOps());
    }

    @Test
    void casOnMissingHypothesisAndLevelChangeAreRejected() {
        assertEquals(WriteResult.Status.NOT_FOUND, board.compareAndSet(Hypothesis.of("X", Level.TRACK, 0.1), "a").status());
        Hypothesis v1 = board.create(Hypothesis.of("X", Level.TRACK, 0.1), "a").current();
        Hypothesis moved = new Hypothesis("X", Level.OBJECT, v1.version(), 0.1, v1.num(), v1.tags(), v1.support(), "");
        assertThrows(IllegalArgumentException.class, () -> board.compareAndSet(moved, "a"));
    }

    @Test
    void journalIsTotallyOrderedAndPageable() {
        Hypothesis a = board.create(Hypothesis.of("A", Level.RAW_DETECTION, 0.5), "x").current();
        board.create(Hypothesis.of("B", Level.TRACK, 0.5).withTag("sensor", "S1"), "x");
        board.compareAndSet(a.with("t", 1.0), "y");

        List<ChangeEvent> all = board.eventsSince(0, 100);
        assertEquals(3, all.size());
        for (int i = 0; i < all.size(); i++) assertEquals(i + 1L, all.get(i).seq());
        assertEquals(ChangeEvent.Type.UPDATED, all.get(2).type());
        assertEquals("y", all.get(2).author());
        assertEquals("S1", all.get(1).tag("sensor"), "events carry a tag snapshot for trigger matching");

        assertEquals(List.of(all.get(1)), board.eventsSince(1, 1));
        assertTrue(board.eventsSince(3, 10).isEmpty());
    }

    @Test
    void queriesFilterByLevelAndTag() {
        board.create(Hypothesis.of("T/2", Level.TRACK, 0.5).withTag("sensor", "S2"), "x");
        board.create(Hypothesis.of("T/1", Level.TRACK, 0.5).withTag("sensor", "S1"), "x");
        board.create(Hypothesis.of("O/1", Level.OBJECT, 0.5), "x");
        assertEquals(List.of("T/1", "T/2"), board.query(Level.TRACK).stream().map(Hypothesis::id).toList());
        assertEquals(List.of("T/2"), board.query(Level.TRACK, "sensor", "S2").stream().map(Hypothesis::id).toList());
        assertEquals(1, board.getAll(List.of("O/1", "missing")).size());
        assertNull(board.get("missing"));
    }

    @Test
    void hypothesisValidatesBeliefAndAttributes() {
        assertThrows(IllegalArgumentException.class, () -> Hypothesis.of("x", Level.TRACK, 1.2));
        assertThrows(IllegalArgumentException.class, () -> Hypothesis.of("x", Level.TRACK, 0.5).with("v", Double.NaN));
        assertEquals(1.0, Hypothesis.of("x", Level.TRACK, 0.5).withBelief(3).belief(), 0.0, "withBelief clamps");
        Hypothesis h = Hypothesis.of("x", Level.TRACK, 0.5).addSupport("a").addSupport("b").addSupport("a");
        assertEquals(List.of("a", "b"), h.support(), "support links are a set in insertion order");
    }

    /**
     * Many threads increment the same counter attribute through the CAS loop. With
     * optimistic concurrency and retry there are no lost updates, and every successful
     * write produced exactly one version.
     */
    @Test
    void casLoopHasNoLostUpdatesUnderContention() throws Exception {
        board.create(Hypothesis.of("C", Level.OBJECT, 0.5).with("n", 0), "init");
        int threads = 8, perThread = 200;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    for (int k = 0; k < perThread; k++) {
                        Cas.Outcome o = Cas.update(board, "C", "w", h -> h.with("n", h.num("n") + 1), 10_000);
                        assertEquals(WriteResult.Status.UPDATED, o.status());
                        conflicts.addAndGet(o.conflicts());
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        go.countDown();
        for (Thread t : ts) t.join();
        Hypothesis c = board.get("C");
        assertEquals(threads * perThread, (int) c.num("n"));
        assertEquals(threads * perThread + 1L, c.version());
        assertEquals(conflicts.get(), (int) board.stats().casConflicts());
    }

    @Test
    void casLoopPreservesConcurrentAnnotationsFromOtherWriters() throws Exception {
        board.create(Hypothesis.of("O", Level.OBJECT, 0.8), "fuser");
        // Writer A reads, then writer B commits in between; A's mutation is re-applied to B's value.
        AtomicInteger calls = new AtomicInteger();
        Cas.Outcome o = Cas.update(board, "O", "critic", h -> {
            if (calls.getAndIncrement() == 0) {
                board.compareAndSet(board.get("O").withTag("class", "VESSEL"), "classifier");
            }
            return h.withTag("critique", "ok");
        });
        assertEquals(WriteResult.Status.UPDATED, o.status());
        assertEquals(1, o.conflicts());
        assertEquals("VESSEL", board.get("O").tag("class"));
        assertEquals("ok", board.get("O").tag("critique"));
    }

    @Test
    void listenersRunAfterCommitAndMayReadBack() {
        List<Long> seen = new ArrayList<>();
        board.addListener(() -> seen.add(board.head()));
        board.create(Hypothesis.of("A", Level.TRACK, 0.5), "x");
        assertEquals(List.of(1L), seen);
    }
}
