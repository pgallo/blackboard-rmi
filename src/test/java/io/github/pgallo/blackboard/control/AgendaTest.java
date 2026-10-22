package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgendaTest {

    private final Agenda agenda = new Agenda();

    static ChangeEvent ev(long seq, String id, Level level, double belief, String sensor) {
        return new ChangeEvent(seq, ChangeEvent.Type.CREATED, id, level, 1, belief, Map.of("sensor", sensor), "x");
    }

    @Test
    void triggersForTheSameContextAreCoalescedIntoOneRecord() {
        TriggerPattern p = TriggerPattern.on(Level.RAW_DETECTION).keyedBy("sensor");
        agenda.offer("C", p, ev(1, "D1", Level.RAW_DETECTION, 0.5, "S1"), 0);
        agenda.offer("C", p, ev(2, "D2", Level.RAW_DETECTION, 0.5, "S1"), 0);
        agenda.offer("C", p, ev(3, "D3", Level.RAW_DETECTION, 0.5, "S2"), 0);
        agenda.offer("C", p, ev(4, "D1", Level.RAW_DETECTION, 0.5, "S1"), 0);   // same hypothesis again
        assertEquals(2, agenda.size());
        Ksar s1 = agenda.snapshot().get(0);
        assertEquals("RAW_DETECTION/S1", s1.contextKey());
        assertEquals(2, s1.size(), "only the latest event per hypothesis is kept");
        assertEquals("D2", s1.events().get(0).hypothesisId(), "events stay in journal order");
        assertEquals(4L, s1.events().get(1).seq());
    }

    @Test
    void ratingPrefersHighGainLowCostAndUncertainEvidence() {
        TriggerPattern cheap = TriggerPattern.on(Level.TRACK).gain(1.0);
        Ksar a = agenda.offer("A", cheap, ev(1, "T1", Level.TRACK, 0.5, "S1"), 0);
        Ksar b = agenda.offer("B", cheap, ev(2, "T1", Level.TRACK, 0.5, "S1"), 0);
        assertTrue(Agenda.rate(a, 0, 1.0, Level.OBJECT) > Agenda.rate(b, 0, 4.0, Level.OBJECT), "lower cost wins");

        Ksar sure = agenda.offer("S", cheap, ev(3, "T2", Level.TRACK, 0.95, "S1"), 0);
        Ksar unsure = agenda.offer("U", cheap, ev(4, "T3", Level.TRACK, 0.2, "S1"), 0);
        assertTrue(Agenda.rate(unsure, 0, 1, Level.OBJECT) > Agenda.rate(sure, 0, 1, Level.OBJECT), "more to gain where belief is low");

        Ksar big = agenda.offer("G", TriggerPattern.on(Level.TRACK).gain(3.0), ev(5, "T4", Level.TRACK, 0.5, "S1"), 0);
        assertTrue(Agenda.rate(big, 0, 1, Level.OBJECT) > Agenda.rate(a, 0, 1, Level.OBJECT), "declared gain scales the rating");
    }

    @Test
    void focusOfAttentionBoostsTheLevelInFocus() {
        Ksar k = agenda.offer("A", TriggerPattern.on(Level.OBJECT), ev(1, "O1", Level.OBJECT, 0.5, "S1"), 0);
        double off = Agenda.rate(k, 0, 1, Level.TRACK);
        double on = Agenda.rate(k, 0, 1, Level.OBJECT);
        assertEquals(Agenda.FOCUS_BOOST, on / off, 1e-9);
    }

    @Test
    void agingPreventsStarvation() {
        Ksar k = agenda.offer("A", TriggerPattern.on(Level.OBJECT), ev(1, "O1", Level.OBJECT, 0.5, "S1"), 0);
        assertEquals(2.0, Agenda.rate(k, (long) Agenda.AGING_MS, 1, Level.TRACK) / Agenda.rate(k, 0, 1, Level.TRACK), 1e-9);
    }

    @Test
    void pollBestTakesHighestRatedEligibleRecordAndBreaksTiesByAge() {
        TriggerPattern p = TriggerPattern.on(Level.TRACK);
        agenda.offer("Slow", p, ev(1, "T1", Level.TRACK, 0.5, "S1"), 0);
        agenda.offer("Fast", p, ev(2, "T1", Level.TRACK, 0.5, "S1"), 0);
        agenda.offer("Twin", p, ev(3, "T1", Level.TRACK, 0.5, "S1"), 0);
        Map<String, Double> cost = Map.of("Slow", 10.0, "Fast", 1.0, "Twin", 1.0);

        assertEquals("Fast", agenda.pollBest(0, cost::get, Level.TRACK, k -> true).orElseThrow().ksar().ks());
        assertEquals("Slow", agenda.pollBest(0, cost::get, Level.TRACK, k -> !k.ks().equals("Twin")).orElseThrow().ksar().ks(),
                "ineligible records (busy context, no idle worker) are skipped, not lost");
        assertEquals("Twin", agenda.pollBest(0, cost::get, Level.TRACK, k -> true).orElseThrow().ksar().ks());
        assertTrue(agenda.isEmpty());
    }

    @Test
    void requeueMergesWithNewerPendingRecordKeepingOlderEventsFirst() {
        TriggerPattern p = TriggerPattern.on(Level.RAW_DETECTION).keyedBy("sensor");
        agenda.offer("C", p, ev(1, "D1", Level.RAW_DETECTION, 0.5, "S1"), 0);
        Ksar taken = agenda.pollBest(0, k -> 1.0, Level.TRACK, k -> true).orElseThrow().ksar();
        taken.incrementAttempts();
        agenda.offer("C", p, ev(2, "D2", Level.RAW_DETECTION, 0.5, "S1"), 5);
        agenda.requeue(taken);
        assertEquals(1, agenda.size());
        Ksar merged = agenda.snapshot().get(0);
        assertEquals(taken.id(), merged.id(), "the retried record keeps its identity");
        assertEquals(java.util.List.of("D1", "D2"), merged.events().stream().map(ChangeEvent::hypothesisId).toList());
        assertEquals(1, merged.attempts(), "attempt count survives the merge");
    }
}
