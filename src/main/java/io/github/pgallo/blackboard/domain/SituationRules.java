package io.github.pgallo.blackboard.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rule base for situation assessment. Pure functions over object states, so the same rules
 * run in the SituationAssessor (over fused estimates) and in the ground-truth reference used by
 * the end-to-end test (over true states).
 */
public final class SituationRules {

    public static final double FORMATION_RANGE_M = 2000;
    public static final double INCURSION_HORIZON_S = 300;
    public static final double RENDEZVOUS_CPA_M = 500;
    public static final double RENDEZVOUS_HORIZON_S = 600;

    private SituationRules() {
    }

    public record Finding(String type, List<String> members, double confidence, Map<String, Double> metrics) {

        public String key() {
            return "S/" + type + "/" + String.join("+", members);
        }
    }

    public static List<Finding> evaluate(List<ObjState> objects, Zone zone) {
        List<ObjState> objs = objects.stream().sorted(Comparator.comparing(ObjState::id)).toList();
        List<Finding> out = new ArrayList<>();
        out.addAll(formations(objs));
        out.addAll(incursions(objs, zone));
        out.addAll(rendezvous(objs));
        out.sort(Comparator.comparing(Finding::key));
        return out;
    }

    /** Groups of same-class objects flying close together with matching velocity (connected components). */
    static List<Finding> formations(List<ObjState> objs) {
        int n = objs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                ObjState a = objs.get(i), b = objs.get(j);
                if (a.cls() == null || a.cls() != b.cls()) continue;
                double d = Kinematics.dist(a.x(), a.y(), b.x(), b.y());
                double dv = Math.hypot(a.vx() - b.vx(), a.vy() - b.vy());
                double tol = 0.15 * Math.max(a.speed(), b.speed()) + 5.0;
                if (d < FORMATION_RANGE_M && dv < tol) parent[find(parent, i)] = find(parent, j);
            }
        }
        Map<Integer, List<ObjState>> groups = new TreeMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(objs.get(i));
        List<Finding> out = new ArrayList<>();
        for (List<ObjState> g : groups.values()) {
            if (g.size() < 2) continue;
            out.add(new Finding("FORMATION", g.stream().map(ObjState::id).sorted().toList(), 0.95,
                    Map.of("size", (double) g.size())));
        }
        return out;
    }

    static List<Finding> incursions(List<ObjState> objs, Zone zone) {
        List<Finding> out = new ArrayList<>();
        for (ObjState o : objs) {
            double eta = Kinematics.timeToEnter(o, zone.cx(), zone.cy(), zone.radius());
            if (eta <= INCURSION_HORIZON_S) {
                // Closer in time means more confident: 0.9 inside, 0.7 at the horizon.
                double conf = 0.9 - 0.2 * eta / INCURSION_HORIZON_S;
                out.add(new Finding("ZONE_INCURSION", List.of(o.id()), conf, Map.of("eta", Math.round(eta * 10) / 10.0)));
            }
        }
        return out;
    }

    /** Two surface objects of different classes converging on the same point. */
    static List<Finding> rendezvous(List<ObjState> objs) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < objs.size(); i++) {
            for (int j = i + 1; j < objs.size(); j++) {
                ObjState a = objs.get(i), b = objs.get(j);
                if (a.cls() == null || b.cls() == null || !a.cls().surface || !b.cls().surface || a.cls() == b.cls()) continue;
                double now = Kinematics.dist(a.x(), a.y(), b.x(), b.y());
                Kinematics.Cpa c = Kinematics.cpa(a, b);
                if (now > RENDEZVOUS_CPA_M && c.distance() < RENDEZVOUS_CPA_M
                        && c.tcpa() > 0 && c.tcpa() < RENDEZVOUS_HORIZON_S) {
                    out.add(new Finding("RENDEZVOUS", List.of(a.id(), b.id()), 0.85,
                            Map.of("cpa", Math.round(c.distance()) * 1.0, "tcpa", Math.round(c.tcpa()) * 1.0)));
                }
            }
        }
        return out;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }
}
