package io.github.pgallo.blackboard.domain;

import io.github.pgallo.blackboard.core.Hypothesis;

/**
 * Belief of a hypothesis is derived from components, each owned by exactly one knowledge
 * source: {@code base} (the KS that derives the hypothesis) and {@code penalty} (the Critic).
 * Every writer recomputes {@code belief = base * (1 - penalty)}. Because the compare-and-set
 * loop re-applies the mutation to the fresh value, concurrent writers compose instead of
 * overwriting each other's judgement.
 */
public final class Beliefs {

    public static final String BASE = "base";
    public static final String PENALTY = "penalty";

    private Beliefs() {
    }

    public static Hypothesis withBase(Hypothesis h, double base) {
        return recompute(h.with(BASE, clamp(base)));
    }

    public static Hypothesis withPenalty(Hypothesis h, double penalty) {
        return recompute(h.with(PENALTY, clamp(penalty)));
    }

    public static double penalty(Hypothesis h) {
        return h.num(PENALTY, 0.0);
    }

    private static Hypothesis recompute(Hypothesis h) {
        double b = h.num(BASE, h.belief()) * (1.0 - h.num(PENALTY, 0.0));
        return h.withBelief(round(b));
    }

    /** Noisy-OR of independent pieces of evidence. */
    public static double noisyOr(double... beliefs) {
        double miss = 1.0;
        for (double b : beliefs) miss *= 1.0 - b;
        return 1.0 - miss;
    }

    static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Rounding keeps re-derived values bit-identical, so no-op writes are recognised and suppressed. */
    public static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
