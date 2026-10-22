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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RawDetection to Track, per sensor. Gated global-nearest-neighbour association per scan,
 * followed by an alpha-beta update; unassociated detections start tentative tracks.
 * Activations are partitioned by sensor, so the controller serialises the scans of one
 * sensor (association is order sensitive) while different sensors run in parallel.
 */
public final class Correlator implements KnowledgeSource {

    public static final String NAME = "Correlator";
    static final int CONFIRM_HITS = 3;
    static final double MAX_COAST_S = 30.0;
    static final double GATE_SIGMAS = 3.5;

    private final Map<String, Sensor> sensors;

    public Correlator(List<Sensor> sensors) {
        this.sensors = sensors.stream().collect(Collectors.toMap(Sensor::id, Function.identity()));
    }

    @Override
    public KsSpec spec() {
        return new KsSpec(NAME, List.of(TriggerPattern.on(Level.RAW_DETECTION).createdOnly()
                .keyedBy(Tracks.SENSOR).gain(1.0)), 2.0);
    }

    private record Pair(double score, String track, Hypothesis det) {
    }

    @Override
    public String execute(Activation a, KsContext ctx) throws RemoteException {
        String sensorId = a.events().get(0).tag(Tracks.SENSOR);
        Sensor sensor = sensors.get(sensorId);
        if (sensor == null) throw new IllegalStateException("unknown sensor " + sensorId);

        Map<Double, List<Hypothesis>> scans = new TreeMap<>();
        for (Hypothesis d : ctx.getAll(a.hypothesisIds())) {
            scans.computeIfAbsent(d.num("t"), k -> new ArrayList<>()).add(d);
        }
        Map<String, Hypothesis> tracks = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Hypothesis t : ctx.query(Level.TRACK, Tracks.SENSOR, sensorId)) {
            tracks.put(t.id(), t);
            used.addAll(t.support());
        }

        int associated = 0, started = 0, skipped = 0;
        for (Map.Entry<Double, List<Hypothesis>> scan : scans.entrySet()) {
            double t = scan.getKey();
            // Re-execution after a worker failure skips detections that already made it into a track.
            List<Hypothesis> fresh = scan.getValue().stream().filter(d -> !used.contains(d.id())).toList();
            skipped += scan.getValue().size() - fresh.size();

            List<Pair> pairs = new ArrayList<>();
            for (Hypothesis tr : tracks.values()) {
                double dt = t - tr.num("t");
                if (dt <= 0 || dt > MAX_COAST_S) continue;
                for (Hypothesis d : fresh) {
                    double score = gateScore(tr, d, dt, sensor);
                    if (score <= 1.0) pairs.add(new Pair(score, tr.id(), d));
                }
            }
            pairs.sort(Comparator.comparingDouble(Pair::score).thenComparing(Pair::track));
            Set<String> takenTracks = new HashSet<>();
            Set<String> takenDets = new HashSet<>();
            for (Pair p : pairs) {
                if (takenTracks.contains(p.track()) || takenDets.contains(p.det().id())) continue;
                takenTracks.add(p.track());
                takenDets.add(p.det().id());
                var out = ctx.update(p.track(), cur -> cur.support().contains(p.det().id()) ? null : update(cur, p.det()));
                if (out.current() != null) tracks.put(p.track(), out.current());
                associated++;
            }
            for (Hypothesis d : fresh) {
                if (takenDets.contains(d.id())) continue;
                var r = ctx.create(newTrack(d));
                tracks.put(r.current().id(), r.current());
                started++;
            }
            fresh.forEach(d -> used.add(d.id()));
        }
        return sensorId + ": " + associated + " assoc, " + started + " new" + (skipped > 0 ? ", " + skipped + " already done" : "");
    }

    /**
     * Normalised gate score: at most 1.0 inside the position and velocity gates. The position
     * gate grows with the coast time, because velocity error (not position noise) dominates
     * the prediction error after a few seconds.
     */
    static double gateScore(Hypothesis tr, Hypothesis d, double dt, Sensor s) {
        double speed = Tracks.speed(tr);
        double velSd = s.velSd(speed);
        double posGate = GATE_SIGMAS * Math.sqrt(2 * s.posSd() * s.posSd() + Math.pow(velSd * dt, 2));
        double dPos = Math.hypot(d.num("x") - Tracks.predX(tr, d.num("t")), d.num("y") - Tracks.predY(tr, d.num("t")));
        double velGate = 4 * velSd + 5.0;
        double dVel = Math.hypot(d.num("vx") - tr.num("vx"), d.num("vy") - tr.num("vy"));
        return Math.max(dPos / posGate, dVel / velGate);
    }

    static Hypothesis newTrack(Hypothesis d) {
        Hypothesis h = new Hypothesis(Tracks.derive("T", d.id()), Level.TRACK, 0, 0.0,
                Map.of("t", d.num("t"), "t0", d.num("t"), "x", d.num("x"), "y", d.num("y"),
                        "vx", d.num("vx"), "vy", d.num("vy"), "hits", 1.0, "sigSum", d.num("sig")),
                Map.of(Tracks.SENSOR, d.tag(Tracks.SENSOR), Tracks.STATUS, Tracks.TENTATIVE),
                List.of(d.id()), "");
        return Beliefs.withBase(h, trackBase(1));
    }

    /** Alpha-beta style update with gains that start at 1/hits and settle at a floor. */
    static Hypothesis update(Hypothesis tr, Hypothesis d) {
        double t = d.num("t");
        int hits = (int) tr.num("hits") + 1;
        double alpha = Math.max(0.4, 1.0 / hits);
        double beta = Math.max(0.2, 1.0 / hits);
        double px = Tracks.predX(tr, t), py = Tracks.predY(tr, t);
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("x", Tracks.round(px + alpha * (d.num("x") - px), 100));
        m.put("y", Tracks.round(py + alpha * (d.num("y") - py), 100));
        m.put("vx", Tracks.round(tr.num("vx") + beta * (d.num("vx") - tr.num("vx")), 100));
        m.put("vy", Tracks.round(tr.num("vy") + beta * (d.num("vy") - tr.num("vy")), 100));
        m.put("hits", (double) hits);
        m.put("sigSum", Tracks.round(tr.num("sigSum") + d.num("sig"), 100));
        Hypothesis h = tr.with(m).addSupport(d.id())
                .withTag(Tracks.STATUS, hits >= CONFIRM_HITS ? Tracks.CONFIRMED : Tracks.TENTATIVE);
        return Beliefs.withBase(h, trackBase(hits));
    }

    /** Each associated detection is independent evidence that the track is real. */
    static double trackBase(int hits) {
        return Beliefs.round(1.0 - Math.pow(0.65, hits));
    }
}
