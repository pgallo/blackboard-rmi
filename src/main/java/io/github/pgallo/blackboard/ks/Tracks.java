package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.core.Hypothesis;

/** Attribute names and helpers shared by the knowledge sources. */
final class Tracks {

    static final String SENSOR = "sensor";
    static final String STATUS = "status";
    static final String CONFIRMED = "CONFIRMED";
    static final String TENTATIVE = "TENTATIVE";
    static final String CLASS = "class";
    static final String CLASS_CONF = "classConf";
    static final String OBJECT = "object";

    /** Minimum classification confidence for a class label to be used by downstream rules. */
    static final double CLASS_THRESHOLD = 0.6;

    private Tracks() {
    }

    static double predX(Hypothesis h, double t) {
        return h.num("x") + h.num("vx") * (t - h.num("t"));
    }

    static double predY(Hypothesis h, double t) {
        return h.num("y") + h.num("vy") * (t - h.num("t"));
    }

    static double speed(Hypothesis h) {
        return Math.hypot(h.num("vx"), h.num("vy"));
    }

    /** Derived id of the next level: "D/S1/03/07" becomes "T/S1/03/07", and so on. */
    static String derive(String prefix, String id) {
        return prefix + id.substring(id.indexOf('/'));
    }

    static double round(double v, double scale) {
        return Math.round(v * scale) / scale;
    }
}
