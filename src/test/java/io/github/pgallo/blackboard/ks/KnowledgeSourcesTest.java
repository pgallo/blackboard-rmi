package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.control.Activation;
import io.github.pgallo.blackboard.control.KsContext;
import io.github.pgallo.blackboard.core.BlackboardStore;
import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.domain.Beliefs;
import io.github.pgallo.blackboard.domain.ObjectClass;
import io.github.pgallo.blackboard.scenario.Detection;
import io.github.pgallo.blackboard.scenario.ScenarioGenerator;
import io.github.pgallo.blackboard.scenario.Sensor;
import org.junit.jupiter.api.Test;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Knowledge-source actions run directly against an in-memory board. */
class KnowledgeSourcesTest {

    private final BlackboardStore board = new BlackboardStore();
    private final List<Sensor> sensors = ScenarioGenerator.defaultSensors();

    private Activation activationFor(String ks, List<String> ids) {
        List<ChangeEvent> events = ids.stream().map(id -> {
            Hypothesis h = board.get(id);
            return new ChangeEvent(0, ChangeEvent.Type.CREATED, id, h.level(), h.version(), h.belief(), h.tags(), "ingest");
        }).toList();
        return new Activation(1, 1, ks, "ctx", events, 1.0);
    }

    private String run(io.github.pgallo.blackboard.control.KnowledgeSource ks, List<String> ids) throws RemoteException {
        return ks.execute(activationFor(ks.spec().name(), ids), new KsContext(board, ks.spec().name()));
    }

    private void detect(String id, String sensor, double t, double x, double y, double vx, double vy, double sig) {
        board.create(new Detection(id, sensor, (int) (t / 10), t, x, y, vx, vy, sig, "?").toHypothesis(), "ingest");
    }

    @Test
    void correlatorBuildsAndConfirmsATrackAndIgnoresAFarDetection() throws RemoteException {
        Correlator c = new Correlator(sensors);
        for (int k = 0; k < 4; k++) {
            detect("D/S1/0" + k + "/00", "S1", 10.0 * k, 1000 + 70 * k, 2000, 7, 0, 28);
            detect("D/S1/0" + k + "/01", "S1", 10.0 * k, 30000 - 1900 * k, 9000, -190, 0, 12);
        }
        detect("D/S1/04/00", "S1", 40, 15000, 15000, 0, 0, 5);   // clutter
        for (int k = 0; k < 4; k++) run(c, List.of("D/S1/0" + k + "/00", "D/S1/0" + k + "/01"));
        run(c, List.of("D/S1/04/00"));

        Hypothesis vessel = board.get("T/S1/00/00");
        Hypothesis aircraft = board.get("T/S1/00/01");
        assertEquals("CONFIRMED", vessel.tag("status"));
        assertEquals(4, vessel.support().size(), "support links are the provenance of the track");
        assertEquals(4.0, aircraft.num("hits"), 0.0);
        assertEquals(-190.0, aircraft.num("vx"), 1.0);
        assertEquals("TENTATIVE", board.get("T/S1/04/00").tag("status"));
        assertTrue(vessel.belief() > board.get("T/S1/04/00").belief());
    }

    @Test
    void correlatorReExecutionIsIdempotent() throws RemoteException {
        Correlator c = new Correlator(sensors);
        for (int k = 0; k < 3; k++) detect("D/S2/0" + k + "/00", "S2", 10.0 * k + 3, 500 + 70 * k, 500, 7, 0, 28);
        List<String> ids = List.of("D/S2/00/00", "D/S2/01/00", "D/S2/02/00");
        run(c, ids);
        long head = board.head();
        Hypothesis before = board.get("T/S2/00/00");
        String note = run(c, ids);   // the activation is delivered again after a worker failure
        assertEquals(head, board.head(), "no new journal entries");
        assertEquals(before, board.get("T/S2/00/00"));
        assertTrue(note.contains("3 already done"), note);
    }

    @Test
    void classifierLabelsConfirmedTracksFromSpeedAndSignature() {
        assertEquals(ObjectClass.AIRCRAFT, Classifier.posterior(185, 12, 5).best());
        assertEquals(ObjectClass.VESSEL, Classifier.posterior(6, 27, 5).best());
        assertEquals(ObjectClass.FASTBOAT, Classifier.posterior(17, 7, 5).best());
        assertTrue(Classifier.posterior(17, 7, 10).confidence() > 0.9);
        Hypothesis tentative = Hypothesis.of("T", Level.TRACK, 0.5).withTag("status", "TENTATIVE");
        assertNull(Classifier.classify(tentative));
    }

    private void track(String id, String sensor, double t0, double t, double x, double y, double vx, double vy,
                       String cls, double belief) {
        Hypothesis h = new Hypothesis(id, Level.TRACK, 0, belief,
                Map.of("t0", t0, "t", t, "x", x, "y", y, "vx", vx, "vy", vy, "hits", 5.0, "sigSum", 60.0, "classConf", 0.95),
                Map.of("sensor", sensor, "status", "CONFIRMED", "class", cls), List.of(), "");
        board.create(h, "Correlator");
    }

    @Test
    void fuserMergesTracksOfDifferentSensorsAndSeparatesDistinctObjects() throws RemoteException {
        Fuser f = new Fuser(sensors);
        track("T/S1/00/01", "S1", 0, 40, 10000, 10000, 190, 0, "AIRCRAFT", 0.9);
        track("T/S2/00/01", "S2", 3, 43, 10600, 10050, 192, 2, "AIRCRAFT", 0.8);
        track("T/S3/00/01", "S3", 6, 46, 10000, 11300, 190, 0, "AIRCRAFT", 0.8);   // 1.3 km abeam: wingman
        run(f, List.of("T/S1/00/01", "T/S2/00/01", "T/S3/00/01"));

        List<Hypothesis> objects = board.query(Level.OBJECT);
        assertEquals(2, objects.size());
        Hypothesis lead = board.get("O/S1/00/01");
        assertEquals(List.of("T/S1/00/01", "T/S2/00/01"), lead.support());
        assertEquals(2.0, lead.num("nSensors"), 0.0);
        assertEquals("AIRCRAFT", lead.tag("class"));
        assertEquals(Beliefs.noisyOr(0.9, 0.8), lead.belief(), 1e-6);
        assertEquals("O/S1/00/01", board.get("T/S2/00/01").tag("object"), "track carries a back-reference");
    }

    @Test
    void criticPenalisesUncorroboratedAndConflictingObjectsAndComposesWithBase() throws RemoteException {
        track("T/S2/00/05", "S2", 3, 63, 0, 0, 16, 0, "FASTBOAT", 0.9);
        track("T/S1/00/07", "S1", 0, 60, 9000, 0, 6, 0, "VESSEL", 0.9);
        track("T/S3/00/07", "S3", 6, 66, 9036, 0, 6, 0, "FASTBOAT", 0.9);
        new Fuser(sensors).execute(activationFor("Fuser", List.of("T/S2/00/05", "T/S1/00/07", "T/S3/00/07")),
                new KsContext(board, "Fuser"));
        run(new Critic(), List.of());

        Hypothesis ghost = board.get("O/S2/00/05");
        assertEquals("uncorroborated", ghost.tag("critique"));
        assertEquals(0.9 * (1 - Critic.UNCORROBORATED_PENALTY), ghost.belief(), 1e-6);
        Hypothesis mixed = board.get("O/S1/00/07");
        assertTrue(mixed.tag("critique").startsWith("class-conflict"), mixed.tag("critique"));
        assertEquals(mixed.num("base") * (1 - Critic.CLASS_CONFLICT_PENALTY), mixed.belief(), 1e-6);

        // The Fuser refreshing the object later keeps the Critic's penalty (belief = base x (1 - penalty)).
        Hypothesis refreshed = Beliefs.withBase(ghost, 0.95);
        board.compareAndSet(refreshed, "Fuser");
        assertEquals(0.95 * (1 - Critic.UNCORROBORATED_PENALTY), board.get("O/S2/00/05").belief(), 1e-6);
    }

    @Test
    void situationAssessorAssertsAndRetractsWithProvenance() throws RemoteException {
        SituationAssessor sa = new SituationAssessor(ScenarioGenerator.DEFAULT_ZONE);
        Hypothesis o = new Hypothesis("O/X", Level.OBJECT, 0, 0.9,
                Map.of("t0", 0.0, "t", 100.0, "x", 20000.0, "y", 26000.0, "vx", 0.0, "vy", -16.0, "classConf", 0.9),
                Map.of("class", "FASTBOAT"), List.of("T/a", "T/b"), "");
        board.create(o, "Fuser");
        run(sa, List.of());
        Hypothesis s = board.get("S/ZONE_INCURSION/O/X");
        assertNotNull(s);
        assertEquals("ACTIVE", s.tag("status"));
        assertEquals(List.of("O/X"), s.support());

        // The Critic discredits the object: the situation is retracted, not deleted.
        board.compareAndSet(Beliefs.withPenalty(board.get("O/X").with("base", 0.9), 0.7), "Critic");
        run(sa, List.of());
        Hypothesis retracted = board.get("S/ZONE_INCURSION/O/X");
        assertEquals("RETRACTED", retracted.tag("status"));
        assertEquals(0.0, retracted.belief(), 0.0);
        assertTrue(retracted.version() > s.version());
    }
}
