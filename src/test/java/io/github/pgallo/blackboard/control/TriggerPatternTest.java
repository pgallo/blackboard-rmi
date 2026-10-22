package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerPatternTest {

    static ChangeEvent ev(ChangeEvent.Type type, String id, Level level, Map<String, String> tags, String author) {
        return new ChangeEvent(1, type, id, level, 1, 0.5, tags, author);
    }

    @Test
    void matchesOnLevelAndEventType() {
        TriggerPattern p = TriggerPattern.on(Level.RAW_DETECTION).createdOnly();
        assertTrue(p.matches(ev(ChangeEvent.Type.CREATED, "D/1", Level.RAW_DETECTION, Map.of(), "ingest"), "Correlator"));
        assertFalse(p.matches(ev(ChangeEvent.Type.UPDATED, "D/1", Level.RAW_DETECTION, Map.of(), "ingest"), "Correlator"));
        assertFalse(p.matches(ev(ChangeEvent.Type.CREATED, "T/1", Level.TRACK, Map.of(), "ingest"), "Correlator"));
    }

    @Test
    void tagConstraintsMustAllHold() {
        TriggerPattern p = TriggerPattern.on(Level.TRACK).whereTag("status", "CONFIRMED").whereTag("sensor", "S1");
        assertTrue(p.matches(ev(ChangeEvent.Type.UPDATED, "T", Level.TRACK, Map.of("status", "CONFIRMED", "sensor", "S1"), "x"), "k"));
        assertFalse(p.matches(ev(ChangeEvent.Type.UPDATED, "T", Level.TRACK, Map.of("status", "TENTATIVE", "sensor", "S1"), "x"), "k"));
        assertFalse(p.matches(ev(ChangeEvent.Type.UPDATED, "T", Level.TRACK, Map.of("status", "CONFIRMED"), "x"), "k"));
    }

    @Test
    void ownWritesCanBeIgnoredToAvoidSelfTriggering() {
        TriggerPattern p = TriggerPattern.on(Level.OBJECT).ignoringOwnWrites();
        assertFalse(p.matches(ev(ChangeEvent.Type.UPDATED, "O", Level.OBJECT, Map.of(), "Critic"), "Critic"));
        assertTrue(p.matches(ev(ChangeEvent.Type.UPDATED, "O", Level.OBJECT, Map.of(), "Fuser"), "Critic"));
        assertTrue(TriggerPattern.on(Level.OBJECT).matches(ev(ChangeEvent.Type.UPDATED, "O", Level.OBJECT, Map.of(), "Critic"), "Critic"));
    }

    @Test
    void contextKeyPartitionsActivations() {
        ChangeEvent e = ev(ChangeEvent.Type.CREATED, "D/S2/01/03", Level.RAW_DETECTION, Map.of("sensor", "S2"), "ingest");
        assertEquals("RAW_DETECTION", TriggerPattern.on(Level.RAW_DETECTION).contextKey(e));
        assertEquals("RAW_DETECTION/S2", TriggerPattern.on(Level.RAW_DETECTION).keyedBy("sensor").contextKey(e));
        assertEquals("RAW_DETECTION/D/S2/01/03", TriggerPattern.on(Level.RAW_DETECTION).keyedBy(TriggerPattern.BY_ID).contextKey(e));
        assertEquals("RAW_DETECTION/-", TriggerPattern.on(Level.RAW_DETECTION).keyedBy("missing").contextKey(e));
    }

    @Test
    void specReturnsFirstMatchingPattern() {
        TriggerPattern a = TriggerPattern.on(Level.TRACK).whereTag("status", "CONFIRMED").gain(2);
        TriggerPattern b = TriggerPattern.on(Level.TRACK).gain(1);
        KsSpec spec = new KsSpec("K", List.of(a, b), 1);
        assertEquals(a, spec.match(ev(ChangeEvent.Type.UPDATED, "T", Level.TRACK, Map.of("status", "CONFIRMED"), "x")).orElseThrow());
        assertEquals(b, spec.match(ev(ChangeEvent.Type.UPDATED, "T", Level.TRACK, Map.of(), "x")).orElseThrow());
        assertTrue(spec.match(ev(ChangeEvent.Type.UPDATED, "O", Level.OBJECT, Map.of(), "x")).isEmpty());
    }
}
