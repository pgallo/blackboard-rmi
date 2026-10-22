package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.control.Activation;
import io.github.pgallo.blackboard.control.KnowledgeSource;
import io.github.pgallo.blackboard.control.KsContext;
import io.github.pgallo.blackboard.control.KsSpec;
import io.github.pgallo.blackboard.control.TriggerPattern;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.domain.Beliefs;
import io.github.pgallo.blackboard.scenario.Sensor;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Track to Object: cross-sensor track-to-track association and state fusion.
 * Association is incremental (a confirmed track joins the nearest compatible object or
 * founds a new one) and sticky (once tagged, a track stays with its object), which keeps
 * object identities stable for the levels above. Fusion runs as a single partition because
 * association is a global decision over all tracks.
 */
public final class Fuser implements KnowledgeSource {

    public static final String NAME = "Fuser";
    static final double GATE_SIGMAS = 3.5;
    /** Members not updated within this window of the newest one keep their evidence but stop steering the state. */
    static final double FRESH_S = 15.0;

    private final Map<String, Sensor> sensors;

    public Fuser(List<Sensor> sensors) {
        this.sensors = sensors.stream().collect(Collectors.toMap(Sensor::id, Function.identity()));
    }

    @Override
    public KsSpec spec() {
        return new KsSpec(NAME, List.of(TriggerPattern.on(Level.TRACK).whereTag(Tracks.STATUS, Tracks.CONFIRMED)
                .ignoringOwnWrites().gain(1.2)), 3.0);
    }

    @Override
    public String execute(Activation a, KsContext ctx) throws RemoteException {
        Map<String, Hypothesis> tracks = new LinkedHashMap<>();
        ctx.query(Level.TRACK, Tracks.STATUS, Tracks.CONFIRMED).forEach(t -> tracks.put(t.id(), t));
        Map<String, Hypothesis> objects = new TreeMap<>();
        ctx.query(Level.OBJECT).forEach(o -> objects.put(o.id(), o));

        Set<String> touched = new LinkedHashSet<>();
        int joined = 0, founded = 0;
        for (String trackId : a.hypothesisIds()) {
            Hypothesis tr = tracks.get(trackId);
            if (tr == null) continue;
            String objId = tr.tag(Tracks.OBJECT);
            if (objId == null) {
                objId = objects.values().stream().filter(o -> o.support().contains(trackId))
                        .map(Hypothesis::id).findFirst().orElse(null);
                if (objId == null) objId = associate(tr, objects, tracks);
                if (objId == null) {
                    Hypothesis o = refresh(new Hypothesis(Tracks.derive("O", trackId), Level.OBJECT, 0, 0.0,
                            Map.of(), Map.of(), List.of(trackId), ""), List.of(tr));
                    objId = ctx.create(o).current().id();
                    founded++;
                } else {
                    joined++;
                }
                String finalObj = objId;
                ctx.update(trackId, t -> t.withTag(Tracks.OBJECT, finalObj));
                objects.put(objId, ctx.update(objId, o -> o.addSupport(trackId)).current());
            }
            touched.add(objId);
        }
        int refreshed = 0;
        for (String objId : touched) {
            var out = ctx.update(objId, o -> {
                List<Hypothesis> members = o.support().stream().map(tracks::get).filter(java.util.Objects::nonNull).toList();
                return members.isEmpty() ? null : refresh(o, members);
            });
            if (out.changed()) refreshed++;
        }
        return "joined " + joined + ", founded " + founded + ", refreshed " + refreshed;
    }

    /**
     * Nearest compatible object, or null. Linkage is to the nearest member track, compared at
     * the latest time both have data, with a gate that widens with the extrapolation interval.
     * Comparing against members rather than the fused estimate keeps one stale or noisy member
     * from dragging the object away from the tracks that should join it.
     */
    String associate(Hypothesis tr, Map<String, Hypothesis> objects, Map<String, Hypothesis> tracks) {
        String sensor = tr.tag(Tracks.SENSOR);
        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (Hypothesis o : objects.values()) {
            List<Hypothesis> members = o.support().stream().map(tracks::get).filter(java.util.Objects::nonNull).toList();
            // Two tracks of the same sensor that overlap in time are, by construction, two objects.
            boolean overlap = members.stream().anyMatch(m -> sensor.equals(m.tag(Tracks.SENSOR))
                    && m.num("t") > tr.num("t0") && tr.num("t") > m.num("t0"));
            if (overlap) continue;
            for (Hypothesis m : members) {
                double score = linkScore(tr, m);
                if (score <= 1.0 && score < bestScore) {
                    bestScore = score;
                    best = o.id();
                }
            }
        }
        return best;
    }

    /** Normalised track-to-track distance: at most 1.0 inside both gates. */
    double linkScore(Hypothesis a, Hypothesis b) {
        double t = Math.min(a.num("t"), b.num("t"));
        double dt = Math.max(Math.abs(a.num("t") - t), Math.abs(b.num("t") - t));
        double sa = sensors.get(a.tag(Tracks.SENSOR)).posSd(), sb = sensors.get(b.tag(Tracks.SENSOR)).posSd();
        double speed = Math.max(Tracks.speed(a), Tracks.speed(b));
        double velErr = 0.05 * speed + 2.0;
        double gate = GATE_SIGMAS * Math.sqrt(sa * sa + sb * sb + Math.pow(velErr * dt, 2));
        double dPos = Math.hypot(Tracks.predX(a, t) - Tracks.predX(b, t), Tracks.predY(a, t) - Tracks.predY(b, t));
        double dVel = Math.hypot(a.num("vx") - b.num("vx"), a.num("vy") - b.num("vy"));
        double velTol = 0.25 * speed + 10.0;
        return Math.max(dPos / gate, dVel / velTol);
    }

    /** Recomputes the fused state, class vote and base belief from the member tracks. */
    Hypothesis refresh(Hypothesis o, List<Hypothesis> members) {
        double t = members.stream().mapToDouble(m -> m.num("t")).max().orElseThrow();
        double t0 = members.stream().mapToDouble(m -> m.num("t0")).min().orElseThrow();
        double wSum = 0, x = 0, y = 0, vwSum = 0, vx = 0, vy = 0;
        Map<String, Double> votes = new TreeMap<>();
        Set<String> sensorIds = new java.util.TreeSet<>();
        List<Double> beliefs = new ArrayList<>();
        for (Hypothesis m : members) {
            Sensor s = sensors.get(m.tag(Tracks.SENSOR));
            sensorIds.add(s.id());
            beliefs.add(m.belief());
            String cls = m.tag(Tracks.CLASS);
            if (cls != null) votes.merge(cls, m.num(Tracks.CLASS_CONF, 0.0) * m.belief(), Double::sum);
            // Extrapolating a dropped track for minutes would drag the estimate off the object.
            if (t - m.num("t") > FRESH_S) continue;
            double w = 1.0 / (s.posSd() * s.posSd());
            double vw = 1.0 / (s.velFactor() * s.velFactor());
            x += w * Tracks.predX(m, t);
            y += w * Tracks.predY(m, t);
            wSum += w;
            vx += vw * m.num("vx");
            vy += vw * m.num("vy");
            vwSum += vw;
        }
        Map<String, Double> num = new LinkedHashMap<>();
        num.put("t", t);
        num.put("t0", t0);
        num.put("x", Tracks.round(x / wSum, 10));
        num.put("y", Tracks.round(y / wSum, 10));
        num.put("vx", Tracks.round(vx / vwSum, 100));
        num.put("vy", Tracks.round(vy / vwSum, 100));
        num.put("nSensors", (double) sensorIds.size());
        Hypothesis h = o.with(num).withTag("sensors", String.join(",", sensorIds));
        if (!votes.isEmpty()) {
            double total = votes.values().stream().mapToDouble(Double::doubleValue).sum();
            Map.Entry<String, Double> top = votes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            h = h.withTag(Tracks.CLASS, top.getKey()).with(Tracks.CLASS_CONF, Tracks.round(top.getValue() / total, 100));
        }
        double base = Beliefs.noisyOr(beliefs.stream().mapToDouble(Double::doubleValue).toArray());
        return Beliefs.withBase(h, Beliefs.round(base));
    }
}
