package io.github.pgallo.blackboard.scenario;

import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;

import java.util.List;
import java.util.Map;

/** A sensor report. {@code label} is ground truth (target label or "clutter") and never reaches the blackboard. */
public record Detection(String id, String sensor, int scan, double t, double x, double y,
                        double vx, double vy, double signature, String label) {

    public static final double PRIOR_BELIEF = 0.5;

    public Hypothesis toHypothesis() {
        return new Hypothesis(id, Level.RAW_DETECTION, 0, PRIOR_BELIEF,
                Map.of("t", t, "x", r(x), "y", r(y), "vx", r(vx), "vy", r(vy), "sig", r(signature)),
                Map.of("sensor", sensor, "scan", Integer.toString(scan)), List.of(), "");
    }

    private static double r(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
