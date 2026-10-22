package io.github.pgallo.blackboard.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory blackboard. Reads are lock free over immutable values; writes and the
 * journal append happen under one monitor, so the journal order is the commit order.
 * Listeners are notified after the monitor is released, so a listener may call back
 * into the store without deadlocking.
 */
public final class BlackboardStore implements BlackboardService {

    private final ConcurrentHashMap<String, Hypothesis> hypotheses = new ConcurrentHashMap<>();
    private final List<ChangeEvent> journal = new ArrayList<>();   // guarded by this
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long conflicts;                                        // guarded by this
    private long suppressed;                                       // guarded by this

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public Hypothesis get(String id) {
        return hypotheses.get(id);
    }

    @Override
    public List<Hypothesis> getAll(Collection<String> ids) {
        return ids.stream().map(hypotheses::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Hypothesis> query(Level level) {
        return hypotheses.values().stream().filter(h -> h.level() == level)
                .sorted(Comparator.comparing(Hypothesis::id)).toList();
    }

    @Override
    public List<Hypothesis> query(Level level, String tagKey, String tagValue) {
        return hypotheses.values().stream()
                .filter(h -> h.level() == level && tagValue.equals(h.tag(tagKey)))
                .sorted(Comparator.comparing(Hypothesis::id)).toList();
    }

    /** Local (non-remote) snapshot for reporting. */
    public List<Hypothesis> all() {
        return hypotheses.values().stream().sorted(Comparator.comparing(Hypothesis::id)).toList();
    }

    @Override
    public WriteResult create(Hypothesis h, String author) {
        Hypothesis stored;
        synchronized (this) {
            Hypothesis existing = hypotheses.get(h.id());
            if (existing != null) return new WriteResult(WriteResult.Status.EXISTS, existing);
            stored = h.stamped(1, author);
            hypotheses.put(stored.id(), stored);
            append(ChangeEvent.Type.CREATED, stored);
        }
        notifyListeners();
        return new WriteResult(WriteResult.Status.CREATED, stored);
    }

    @Override
    public WriteResult compareAndSet(Hypothesis proposed, String author) {
        Hypothesis stored;
        synchronized (this) {
            Hypothesis current = hypotheses.get(proposed.id());
            if (current == null) return new WriteResult(WriteResult.Status.NOT_FOUND, null);
            if (current.level() != proposed.level()) {
                throw new IllegalArgumentException("hypothesis " + proposed.id() + " cannot change level");
            }
            if (current.version() != proposed.version()) {
                conflicts++;
                return new WriteResult(WriteResult.Status.CONFLICT, current);
            }
            if (current.sameContent(proposed)) {
                // Suppressing no-op writes is what lets the system reach a fixed point:
                // re-deriving an unchanged conclusion produces no event and triggers nothing.
                suppressed++;
                return new WriteResult(WriteResult.Status.UNCHANGED, current);
            }
            stored = proposed.stamped(current.version() + 1, author);
            hypotheses.put(stored.id(), stored);
            append(ChangeEvent.Type.UPDATED, stored);
        }
        notifyListeners();
        return new WriteResult(WriteResult.Status.UPDATED, stored);
    }

    @Override
    public synchronized List<ChangeEvent> eventsSince(long afterSeq, int max) {
        int from = (int) Math.max(0, afterSeq);
        if (from >= journal.size()) return List.of();
        return List.copyOf(journal.subList(from, Math.min(journal.size(), from + max)));
    }

    public synchronized long head() {
        return journal.size();
    }

    @Override
    public synchronized BoardStats stats() {
        Map<Level, Integer> counts = new EnumMap<>(Level.class);
        for (Level l : Level.values()) counts.put(l, 0);
        hypotheses.values().forEach(h -> counts.merge(h.level(), 1, Integer::sum));
        return new BoardStats(journal.size(), counts, conflicts, suppressed);
    }

    private void append(ChangeEvent.Type type, Hypothesis h) {
        journal.add(new ChangeEvent(journal.size() + 1L, type, h.id(), h.level(), h.version(), h.belief(),
                h.tags(), h.author()));
    }

    private void notifyListeners() {
        for (Runnable l : listeners) l.run();
    }
}
