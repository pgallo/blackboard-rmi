package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Level;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Precondition of a knowledge source, expressed as data so that a remote KS can declare it
 * at registration time and the controller can evaluate it centrally against the journal.
 *
 * @param contextTag tag whose value partitions activations (one pending and one running
 *                   activation per partition); {@link #BY_ID} partitions by hypothesis id;
 *                   null means one partition per level
 * @param gain       relative value of the work this trigger represents, used by the rating
 */
public record TriggerPattern(Level level, Set<ChangeEvent.Type> types, Map<String, String> tagEquals,
                             String contextTag, boolean ignoreOwnWrites, double gain) implements Serializable {

    public static final String BY_ID = "@id";

    public TriggerPattern {
        types = Set.copyOf(types);
        tagEquals = Map.copyOf(tagEquals);
    }

    public static TriggerPattern on(Level level) {
        return new TriggerPattern(level, EnumSet.allOf(ChangeEvent.Type.class), Map.of(), null, false, 1.0);
    }

    public TriggerPattern createdOnly() {
        return new TriggerPattern(level, Set.of(ChangeEvent.Type.CREATED), tagEquals, contextTag, ignoreOwnWrites, gain);
    }

    public TriggerPattern whereTag(String key, String value) {
        Map<String, String> m = new TreeMap<>(tagEquals);
        m.put(key, value);
        return new TriggerPattern(level, types, m, contextTag, ignoreOwnWrites, gain);
    }

    public TriggerPattern keyedBy(String tag) {
        return new TriggerPattern(level, types, tagEquals, tag, ignoreOwnWrites, gain);
    }

    public TriggerPattern ignoringOwnWrites() {
        return new TriggerPattern(level, types, tagEquals, contextTag, true, gain);
    }

    public TriggerPattern gain(double g) {
        return new TriggerPattern(level, types, tagEquals, contextTag, ignoreOwnWrites, g);
    }

    public boolean matches(ChangeEvent e, String ksName) {
        if (e.level() != level || !types.contains(e.type())) return false;
        // Without this, a KS that annotates the hypotheses it is triggered by would trigger itself forever.
        if (ignoreOwnWrites && ksName.equals(e.author())) return false;
        for (Map.Entry<String, String> t : tagEquals.entrySet()) {
            if (!t.getValue().equals(e.tag(t.getKey()))) return false;
        }
        return true;
    }

    public String contextKey(ChangeEvent e) {
        if (contextTag == null) return level.name();
        String v = BY_ID.equals(contextTag) ? e.hypothesisId() : e.tag(contextTag);
        return level.name() + "/" + (v == null ? "-" : v);
    }
}
