package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.BlackboardStore;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the controller synchronously with a fake clock and in-process callbacks, so lease
 * expiry, timeouts and reassignment are tested deterministically, without sleeping.
 */
class ControllerTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private BlackboardStore board;
    private Controller control;
    private final KsSpec echo = new KsSpec("Echo", List.of(TriggerPattern.on(Level.RAW_DETECTION).keyedBy("sensor")), 1);

    /** Records dispatches; completes nothing unless told to. */
    static final class FakeWorker implements KsWorker {
        final List<Activation> received = new ArrayList<>();
        boolean unreachable;

        @Override
        public void dispatch(Activation a) throws RemoteException {
            if (unreachable) throw new ConnectException("connection refused");
            received.add(a);
        }
    }

    @BeforeEach
    void setUp() {
        board = new BlackboardStore();
        ControllerConfig cfg = new ControllerConfig(1000, 250, 5000, 300, 3, 1000, Set.of("Echo"));
        control = new Controller(board, cfg, now::get);
    }

    private void post(String id, String sensor) {
        board.create(Hypothesis.of(id, Level.RAW_DETECTION, 0.5).withTag("sensor", sensor), "ingest");
    }

    private ActivationResult ok() {
        return new ActivationResult(true, 0, 0, 0, 0, "");
    }

    private List<TraceEntry.Kind> kinds() {
        return control.trace().stream().map(TraceEntry::kind).toList();
    }

    @Test
    void dispatchesOneActivationPerContextAndSerialisesTheSameContext() {
        FakeWorker w1 = new FakeWorker(), w2 = new FakeWorker();
        control.register(echo, w1, "e1");
        control.register(echo, w2, "e2");
        post("D/S1/0", "S1");
        post("D/S2/0", "S2");
        control.step();
        assertEquals(1, w1.received.size());
        assertEquals(1, w2.received.size());
        assertFalse(w1.received.get(0).contextKey().equals(w2.received.get(0).contextKey()));

        // New S1 evidence while the S1 activation is running waits for it, even though no worker is idle anyway.
        Activation s1 = (w1.received.get(0).contextKey().endsWith("S1") ? w1 : w2).received.get(0);
        String s1Worker = w1.received.get(0) == s1 ? "e1" : "e2";
        post("D/S1/1", "S1");
        control.step();
        assertEquals(2, w1.received.size() + w2.received.size());
        control.complete(s1Worker, s1.id(), s1.attempt(), ok());
        control.step();
        assertEquals(3, w1.received.size() + w2.received.size());
        Activation next = (s1Worker.equals("e1") ? w1 : w2).received.get(1);
        assertEquals(List.of("D/S1/1"), next.hypothesisIds());
    }

    @Test
    void newKnowledgeSourceTypeReplaysTheJournal() {
        post("D/S1/0", "S1");
        post("D/S1/1", "S1");
        FakeWorker w = new FakeWorker();
        control.register(echo, w, "late-joiner");
        control.step();
        assertEquals(List.of("D/S1/0", "D/S1/1"), w.received.get(0).hypothesisIds());
    }

    @Test
    void deadWorkerLeaseExpiresAndItsWorkIsReassigned() {
        FakeWorker dead = new FakeWorker(), alive = new FakeWorker();
        control.register(echo, dead, "dead");
        post("D/S1/0", "S1");
        control.step();
        assertEquals(1, dead.received.size());
        Activation lost = dead.received.get(0);

        control.register(echo, alive, "alive");
        for (int i = 0; i < 5; i++) {        // 'alive' heartbeats, 'dead' stays silent
            now.addAndGet(250);
            assertTrue(control.heartbeat("alive"));
            control.step();
        }
        assertTrue(kinds().contains(TraceEntry.Kind.LEASE_EXPIRED));
        assertEquals(List.of("alive"), control.liveWorkers());
        assertEquals(1, alive.received.size());
        Activation retry = alive.received.get(0);
        assertEquals(lost.id(), retry.id(), "same activation record");
        assertEquals(2, retry.attempt());
        assertEquals(lost.hypothesisIds(), retry.hypothesisIds());
        assertFalse(control.heartbeat("dead"), "an expired worker must re-register");

        // The zombie finally answers: its result is ignored, the retry's result counts.
        control.complete("dead", lost.id(), lost.attempt(), ok());
        assertTrue(kinds().contains(TraceEntry.Kind.LATE));
        control.complete("alive", retry.id(), retry.attempt(), ok());
        assertTrue(kinds().contains(TraceEntry.Kind.DONE));
    }

    @Test
    void unreachableCallbackRemovesWorkerAndReassignsImmediately() {
        FakeWorker broken = new FakeWorker(), good = new FakeWorker();
        broken.unreachable = true;
        control.register(echo, broken, "a-broken");   // sorts first, so it is picked first
        control.register(echo, good, "b-good");
        post("D/S1/0", "S1");
        control.step();
        assertEquals(List.of("b-good"), control.liveWorkers());
        control.step();
        assertEquals(1, good.received.size());
        assertEquals(2, good.received.get(0).attempt());
    }

    @Test
    void stuckActivationTimesOutAndIsRetriedElsewhere() {
        FakeWorker hung = new FakeWorker(), other = new FakeWorker();
        control.register(echo, hung, "hung");
        post("D/S1/0", "S1");
        control.step();
        control.register(echo, other, "other");
        for (int i = 0; i < 22; i++) {             // both keep heartbeating, 'hung' never completes
            now.addAndGet(250);
            control.heartbeat("hung");
            control.heartbeat("other");
            control.step();
        }
        assertTrue(kinds().contains(TraceEntry.Kind.TIMED_OUT));
        assertEquals(1, other.received.size());
        assertEquals(2, other.received.get(0).attempt());
    }

    @Test
    void failingActivationIsRetriedThenAbandoned() {
        FakeWorker w = new FakeWorker();
        control.register(echo, w, "w");
        post("D/S1/0", "S1");
        for (int i = 0; i < 3; i++) {
            control.step();
            Activation a = w.received.get(i);
            control.complete("w", a.id(), a.attempt(), ActivationResult.error("boom"));
        }
        control.step();
        assertEquals(3, w.received.size());
        assertTrue(kinds().contains(TraceEntry.Kind.ABANDONED));
        assertEquals(0, control.agendaSize());
    }

    @Test
    void terminatesOnQuiescenceOnlyAfterInputClosedAndRequiredKsPresent() {
        post("D/S1/0", "S1");
        control.closeInput();
        now.addAndGet(1000);
        control.step();
        assertNull(control.termination(), "Echo has not registered yet");

        FakeWorker w = new FakeWorker();
        control.register(echo, w, "w");
        control.step();
        Activation a = w.received.get(0);
        now.addAndGet(10);
        control.heartbeat("w");
        control.complete("w", a.id(), a.attempt(), ok());
        control.step();
        assertNull(control.termination(), "quiet period not over");
        now.addAndGet(300);
        control.heartbeat("w");
        control.step();
        assertEquals(Controller.Termination.QUIESCENT, control.termination());
    }

    @Test
    void deadlineTerminatesEvenWithPendingWork() {
        post("D/S1/0", "S1");
        control.setDeadline(now.get() + 100);
        control.step();
        assertNull(control.termination());
        now.addAndGet(100);
        control.step();
        assertEquals(Controller.Termination.DEADLINE, control.termination());
    }

    @Test
    void focusFollowsTheLevelOfTheLatestChange() {
        control.register(echo, new FakeWorker(), "w");
        board.create(Hypothesis.of("T/1", Level.TRACK, 0.5), "x");
        control.step();
        assertEquals(Level.TRACK, control.focus());
        board.create(Hypothesis.of("O/1", Level.OBJECT, 0.5), "x");
        control.step();
        assertEquals(Level.OBJECT, control.focus());
    }

    @Test
    void measuredCostReplacesThePriorEstimate() {
        FakeWorker w = new FakeWorker();
        control.register(echo, w, "w");
        post("D/S1/0", "S1");
        control.step();
        now.addAndGet(41);
        control.complete("w", w.received.get(0).id(), 1, ok());
        // EWMA from prior 1 ms towards 41 ms: 0.7 * 1 + 0.3 * 41 = 13
        assertTrue(control.ksSummary().get("Echo").contains("cost 13.0 ms"), control.ksSummary().get("Echo"));
    }
}
