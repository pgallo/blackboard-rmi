package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Knowledge-source activation record on the agenda. Triggers for the same
 * (knowledge source, context) are coalesced into one record, and only the latest event
 * per hypothesis is kept, because a KS always re-reads the current value anyway.
 */
public final class Ksar {

    private final long id;
    private final String ks;
    private final String contextKey;
    private final Level level;
    private final double gain;
    private final long createdAt;
    private final LinkedHashMap<String, ChangeEvent> events = new LinkedHashMap<>();
    private int attempts;

    Ksar(long id, String ks, String contextKey, Level level, double gain, long createdAt) {
        this.id = id;
        this.ks = ks;
        this.contextKey = contextKey;
        this.level = level;
        this.gain = gain;
        this.createdAt = createdAt;
    }

    void add(ChangeEvent e) {
        events.remove(e.hypothesisId());   // re-insert so iteration order follows journal order
        events.put(e.hypothesisId(), e);
    }

    /**
     * Folds a newer pending record for the same context into this (requeued) one. The retried
     * record keeps its id, attempt count and age; the newer triggers follow its own, so
     * journal order is preserved for order-sensitive knowledge sources.
     */
    void absorbNewer(Ksar newer) {
        newer.events.values().forEach(this::add);
    }

    int incrementAttempts() {
        return ++attempts;
    }

    public long id() { return id; }
    public String ks() { return ks; }
    public String contextKey() { return contextKey; }
    public Level level() { return level; }
    public double gain() { return gain; }
    public long createdAt() { return createdAt; }
    public int attempts() { return attempts; }
    public int size() { return events.size(); }

    public List<ChangeEvent> events() {
        return new ArrayList<>(events.values());
    }

    public double meanBelief() {
        return events.values().stream().mapToDouble(ChangeEvent::belief).average().orElse(0.0);
    }
}
