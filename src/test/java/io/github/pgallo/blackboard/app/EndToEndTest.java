package io.github.pgallo.blackboard.app;

import io.github.pgallo.blackboard.control.Controller;
import io.github.pgallo.blackboard.control.TraceEntry;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full local runs over real RMI (in-process registry, exported remote objects, RMI callbacks):
 * the system must reach quiescence with exactly the situations the ground-truth reference expects.
 */
class EndToEndTest {

    private static final List<String> EXPECTED = List.of("FORMATION[A1+A2]", "RENDEZVOUS[B1+V1]", "ZONE_INCURSION[A3]");

    @Test
    void localRunConvergesToExpectedSituations() throws Exception {
        LocalRun.Result r = LocalRun.run(LocalRun.Options.defaults(7));
        assertEquals(Controller.Termination.QUIESCENT, r.termination());
        assertEquals(EXPECTED, Truth.expectedSituations(r.scenario()));
        assertEquals(EXPECTED, Truth.activeSituations(r.board(), r.scenario(), 0.5));

        // Every knowledge source took part.
        Set<String> active = r.controller().trace().stream().filter(e -> e.kind() == TraceEntry.Kind.DONE)
                .map(TraceEntry::ks).collect(Collectors.toSet());
        assertEquals(Set.of("Correlator", "Classifier", "Fuser", "SituationAssessor", "Critic"), active);

        // The single-sensor artifact was found and discredited by the Critic, and any situation built on it retracted.
        Hypothesis ghost = r.board().query(Level.OBJECT).stream()
                .filter(o -> "G1".equals(Truth.objectLabels(r.board(), r.scenario()).get(o.id()))).findFirst().orElseThrow();
        assertTrue(ghost.tag("critique").contains("uncorroborated"), ghost.tag("critique"));
        assertTrue(ghost.belief() < 0.5);
        assertTrue(r.board().query(Level.SITUATION).stream().filter(s -> s.support().contains(ghost.id()))
                .allMatch(s -> "RETRACTED".equals(s.tag("status"))));

        // Provenance: every active situation can be traced down to raw detections.
        for (Hypothesis s : r.board().query(Level.SITUATION)) {
            if (!"ACTIVE".equals(s.tag("status"))) continue;
            for (Hypothesis o : r.board().getAll(s.support())) {
                for (Hypothesis t : r.board().getAll(o.support())) {
                    assertTrue(!t.support().isEmpty() && t.support().stream().allMatch(d -> d.startsWith("D/")));
                }
            }
        }
    }

    @Test
    void otherSeedsConvergeToo() throws Exception {
        for (long seed : new long[]{1, 42}) {
            LocalRun.Result r = LocalRun.run(LocalRun.Options.defaults(seed));
            assertEquals(Controller.Termination.QUIESCENT, r.termination(), "seed " + seed);
            assertEquals(EXPECTED, Truth.activeSituations(r.board(), r.scenario(), 0.5), "seed " + seed);
        }
    }

    @Test
    void hungWorkerIsDetectedByLeaseAndItsActivationReassigned() throws Exception {
        LocalRun.Result r = LocalRun.run(LocalRun.Options.defaults(7).withFault(3).withPace(20));
        List<TraceEntry> trace = r.controller().trace();
        int expiredAt = -1;
        for (int i = 0; i < trace.size() && expiredAt < 0; i++) {
            TraceEntry e = trace.get(i);
            if (e.kind() == TraceEntry.Kind.LEASE_EXPIRED && "Correlator-2".equals(e.worker())) expiredAt = i;
        }
        assertTrue(expiredAt >= 0, "the hung worker's lease expired");
        // The record that was in flight on the hung worker is requeued right after its lease expires.
        TraceEntry requeued = trace.get(expiredAt + 1);
        assertEquals(TraceEntry.Kind.REQUEUED, requeued.kind());
        assertEquals("Correlator", requeued.ks());
        assertTrue(trace.stream().anyMatch(e -> e.kind() == TraceEntry.Kind.DONE && e.activationId() == requeued.activationId()
                && e.attempt() == 2 && "Correlator-1".equals(e.worker())), "the lost activation completes on the surviving worker");
        assertEquals(Controller.Termination.QUIESCENT, r.termination());
        assertEquals(EXPECTED, Truth.activeSituations(r.board(), r.scenario(), 0.5));
    }
}
