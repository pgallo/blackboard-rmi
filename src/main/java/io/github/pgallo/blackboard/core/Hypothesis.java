package io.github.pgallo.blackboard.core;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable hypothesis. Writers read a value, derive a new one with the {@code with*}
 * methods and submit it with compare-and-set. The derived value keeps the version it was
 * read at, which is exactly what the store compares against.
 *
 * @param num     numeric attributes (position, velocity, filter covariance, counters)
 * @param tags    symbolic attributes (sensor, class label, situation type, critique)
 * @param support ids of the lower-level hypotheses this one was derived from (provenance)
 */
public record Hypothesis(String id, Level level, long version, double belief,
                         Map<String, Double> num, Map<String, String> tags,
                         List<String> support, String author) implements Serializable {

    public Hypothesis {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(level, "level");
        if (!(belief >= 0.0 && belief <= 1.0)) {
            throw new IllegalArgumentException("belief outside [0,1] for " + id + ": " + belief);
        }
        num.forEach((k, v) -> {
            if (v == null || !Double.isFinite(v)) throw new IllegalArgumentException(id + "." + k + " = " + v);
        });
        // Sorted maps give stable printing; the wrappers are serializable.
        num = Collections.unmodifiableMap(new TreeMap<>(num));
        tags = Collections.unmodifiableMap(new TreeMap<>(tags));
        support = List.copyOf(support);
        author = author == null ? "" : author;
    }

    public static Hypothesis of(String id, Level level, double belief) {
        return new Hypothesis(id, level, 0, belief, Map.of(), Map.of(), List.of(), "");
    }

    public double num(String key) {
        Double v = num.get(key);
        if (v == null) throw new IllegalStateException(id + " has no attribute " + key);
        return v;
    }

    public double num(String key, double fallback) {
        return num.getOrDefault(key, fallback);
    }

    public String tag(String key) {
        return tags.get(key);
    }

    public Hypothesis withBelief(double b) {
        return new Hypothesis(id, level, version, Math.max(0.0, Math.min(1.0, b)), num, tags, support, author);
    }

    public Hypothesis with(String key, double value) {
        Map<String, Double> m = new TreeMap<>(num);
        m.put(key, value);
        return new Hypothesis(id, level, version, belief, m, tags, support, author);
    }

    public Hypothesis with(Map<String, Double> values) {
        Map<String, Double> m = new TreeMap<>(num);
        m.putAll(values);
        return new Hypothesis(id, level, version, belief, m, tags, support, author);
    }

    public Hypothesis withTag(String key, String value) {
        Map<String, String> m = new TreeMap<>(tags);
        m.put(key, value);
        return new Hypothesis(id, level, version, belief, num, m, support, author);
    }

    public Hypothesis withSupport(Collection<String> ids) {
        return new Hypothesis(id, level, version, belief, num, tags, List.copyOf(new LinkedHashSet<>(ids)), author);
    }

    public Hypothesis addSupport(String supportId) {
        if (support.contains(supportId)) return this;
        List<String> s = new java.util.ArrayList<>(support);
        s.add(supportId);
        return new Hypothesis(id, level, version, belief, num, tags, s, author);
    }

    /** Equality of everything a reader can observe, ignoring version and author. */
    public boolean sameContent(Hypothesis o) {
        return level == o.level && Double.compare(belief, o.belief) == 0
                && num.equals(o.num) && tags.equals(o.tags) && support.equals(o.support);
    }

    Hypothesis stamped(long newVersion, String newAuthor) {
        return new Hypothesis(id, level, newVersion, belief, num, tags, support, newAuthor);
    }
}
