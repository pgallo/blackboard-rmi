package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Level;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Pending activation records and the opportunistic rating used to pick the next one.
 * Invariant: at most one pending record per (knowledge source, context key).
 * Not thread safe; the controller guards it.
 */
public final class Agenda {

    /** Multiplier for records triggered at the level currently in focus. */
    static final double FOCUS_BOOST = 1.5;
    /** Waiting time after which a record's rating has doubled (starvation guard). */
    static final double AGING_MS = 500.0;

    private record Key(String ks, String ctx) {
    }

    private final Map<Key, Ksar> pending = new LinkedHashMap<>();
    private long nextId = 1;

    public Ksar offer(String ks, TriggerPattern pattern, ChangeEvent e, long now) {
        String ctx = pattern.contextKey(e);
        Ksar k = pending.computeIfAbsent(new Key(ks, ctx),
                key -> new Ksar(nextId++, ks, ctx, pattern.level(), pattern.gain(), now));
        k.add(e);
        return k;
    }

    /** Puts a record back (worker died, timed out or failed), absorbing any newer pending record for the same context. */
    public void requeue(Ksar k) {
        Key key = new Key(k.ks(), k.contextKey());
        Ksar newer = pending.remove(key);
        if (newer != null) k.absorbNewer(newer);
        pending.put(key, k);
    }

    /**
     * Rating = expected belief gain / estimated cost, scaled by focus of attention and age.
     * Expected gain grows with the uncertainty of the triggering hypotheses (there is more to
     * gain where belief is low) and sub-linearly with the amount of coalesced evidence.
     */
    public static double rate(Ksar k, long now, double costMs, Level focus) {
        double uncertainty = 1.0 - k.meanBelief() + 0.1;
        double expectedGain = k.gain() * uncertainty * (Math.log(1 + k.size()) / Math.log(2));
        double focusFactor = k.level() == focus ? FOCUS_BOOST : 1.0;
        double aging = 1.0 + Math.max(0, now - k.createdAt()) / AGING_MS;
        return expectedGain / Math.max(costMs, 0.5) * focusFactor * aging;
    }

    /** Removes and returns the best-rated eligible record. Ties go to the older record. */
    public Optional<Rated> pollBest(long now, ToDoubleFunction<String> costOf, Level focus, Predicate<Ksar> eligible) {
        Optional<Rated> best = pending.values().stream()
                .filter(eligible)
                .map(k -> new Rated(k, rate(k, now, costOf.applyAsDouble(k.ks()), focus)))
                .max(Comparator.comparingDouble(Rated::rating).thenComparing(r -> -r.ksar().id()));
        best.ifPresent(r -> pending.remove(new Key(r.ksar().ks(), r.ksar().contextKey())));
        return best;
    }

    public record Rated(Ksar ksar, double rating) {
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }

    public List<Ksar> snapshot() {
        return List.copyOf(pending.values());
    }
}
