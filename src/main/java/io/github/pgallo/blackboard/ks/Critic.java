package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.control.Activation;
import io.github.pgallo.blackboard.control.KnowledgeSource;
import io.github.pgallo.blackboard.control.KsContext;
import io.github.pgallo.blackboard.control.KsSpec;
import io.github.pgallo.blackboard.control.TriggerPattern;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.domain.Beliefs;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lowers the belief of inconsistent objects. It never raises belief above what the
 * deriving KS computed: it owns only the {@code penalty} component, so its judgement and
 * the Fuser's evidence compose (belief = base x (1 - penalty)) under concurrent writes.
 * Penalties are a pure function of the current state, so they are lifted again when the
 * inconsistency goes away.
 */
public final class Critic implements KnowledgeSource {

    public static final String NAME = "Critic";
    /** All sensors cover the whole area: an object only one of them sees for this long is suspect. */
    static final double CORROBORATION_WINDOW_S = 45.0;
    static final double UNCORROBORATED_PENALTY = 0.7;
    static final double CLASS_CONFLICT_PENALTY = 0.3;
    /** An object none of whose tracks has been updated for this long has been lost. */
    static final double STALE_AFTER_S = 30.0;
    static final double STALE_PENALTY = 0.8;
    /** Two objects closer than this, with compatible velocity, are the same physical object. */
    static final double DUPLICATE_RANGE_M = 500.0;
    static final double DUPLICATE_PENALTY = 0.8;

    @Override
    public KsSpec spec() {
        return new KsSpec(NAME, List.of(TriggerPattern.on(Level.OBJECT).ignoringOwnWrites().gain(1.0)), 1.0);
    }

    record Verdict(double penalty, String critique) {
    }

    @Override
    public String execute(Activation a, KsContext ctx) throws RemoteException {
        List<Hypothesis> objects = ctx.query(Level.OBJECT);
        Set<String> memberIds = objects.stream().flatMap(o -> o.support().stream()).collect(Collectors.toSet());
        Map<String, Hypothesis> tracks = ctx.getAll(memberIds).stream()
                .collect(Collectors.toMap(Hypothesis::id, Function.identity()));
        double now = objects.stream().mapToDouble(o -> o.num("t")).max().orElse(0);
        List<String> changed = new ArrayList<>();
        for (Hypothesis o : objects) {
            Verdict v = judge(o, tracks, now, objects);
            var out = ctx.update(o.id(), cur -> Beliefs.withPenalty(cur, v.penalty()).withTag("critique", v.critique()));
            if (out.changed()) changed.add(o.id() + ":" + v.critique());
        }
        return changed.isEmpty() ? "no change" : String.join(" ", changed);
    }

    /** {@code now} is the latest object time on the board (the picture time). */
    static Verdict judge(Hypothesis o, Map<String, Hypothesis> tracks, double now, List<Hypothesis> objects) {
        List<String> reasons = new ArrayList<>();
        double keep = 1.0;
        if (now - o.num("t") > STALE_AFTER_S) {
            keep *= 1 - STALE_PENALTY;
            reasons.add("stale");
        }
        double age = o.num("t") - o.num("t0");
        if (o.num("nSensors", 1) < 2 && age >= CORROBORATION_WINDOW_S) {
            keep *= 1 - UNCORROBORATED_PENALTY;
            reasons.add("uncorroborated");
        }
        String dup = duplicateOf(o, objects);
        if (dup != null) {
            keep *= 1 - DUPLICATE_PENALTY;
            reasons.add("duplicate-of:" + dup);
        }
        Set<String> classes = new TreeSet<>();
        for (String id : o.support()) {
            Hypothesis t = tracks.get(id);
            if (t != null && t.tag(Tracks.CLASS) != null && t.num(Tracks.CLASS_CONF, 0) >= Tracks.CLASS_THRESHOLD) {
                classes.add(t.tag(Tracks.CLASS));
            }
        }
        if (classes.size() > 1) {
            keep *= 1 - CLASS_CONFLICT_PENALTY;
            reasons.add("class-conflict" + classes);
        }
        return new Verdict(Beliefs.round(1 - keep), reasons.isEmpty() ? "ok" : String.join("+", reasons));
    }

    /**
     * A fragment left over when association split one physical object in two: another object
     * at the same place and velocity that is better established (more sensors, then older,
     * then smaller id). The ordering is total, so exactly one of a pair is penalised.
     */
    static String duplicateOf(Hypothesis o, List<Hypothesis> objects) {
        double t = o.num("t");
        for (Hypothesis other : objects) {
            if (other.id().equals(o.id()) || Math.abs(other.num("t") - t) > STALE_AFTER_S) continue;
            double d = Math.hypot(Tracks.predX(other, t) - o.num("x"), Tracks.predY(other, t) - o.num("y"));
            double dv = Math.hypot(other.num("vx") - o.num("vx"), other.num("vy") - o.num("vy"));
            double tol = 0.25 * Math.max(Tracks.speed(o), Tracks.speed(other)) + 10.0;
            if (d < DUPLICATE_RANGE_M && dv < tol && stronger(other, o)) return other.id();
        }
        return null;
    }

    private static boolean stronger(Hypothesis a, Hypothesis b) {
        int c = Double.compare(a.num("nSensors", 1), b.num("nSensors", 1));
        if (c != 0) return c > 0;
        c = Double.compare(b.num("t0"), a.num("t0"));
        if (c != 0) return c > 0;
        return a.id().compareTo(b.id()) < 0;
    }
}
