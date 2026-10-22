package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.control.Activation;
import io.github.pgallo.blackboard.control.KnowledgeSource;
import io.github.pgallo.blackboard.control.KsContext;
import io.github.pgallo.blackboard.control.KsSpec;
import io.github.pgallo.blackboard.control.TriggerPattern;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.domain.ObjectClass;

import java.rmi.RemoteException;
import java.util.List;

/**
 * Naive-Bayes classification of confirmed tracks from smoothed speed and mean signature.
 * The confidence is rounded to two decimals on purpose: it keeps the classifier from
 * re-publishing a "new" posterior on every kinematic update, which would trigger the
 * whole upper half of the system for no gain.
 */
public final class Classifier implements KnowledgeSource {

    public static final String NAME = "Classifier";

    @Override
    public KsSpec spec() {
        return new KsSpec(NAME, List.of(TriggerPattern.on(Level.TRACK).whereTag(Tracks.STATUS, Tracks.CONFIRMED)
                .keyedBy(Tracks.SENSOR).ignoringOwnWrites().gain(0.8)), 1.0);
    }

    @Override
    public String execute(Activation a, KsContext ctx) throws RemoteException {
        StringBuilder note = new StringBuilder();
        for (String id : a.hypothesisIds()) {
            var out = ctx.update(id, Classifier::classify);
            if (out.changed()) {
                Hypothesis h = out.current();
                note.append(id).append("=").append(h.tag(Tracks.CLASS)).append("(").append(h.num(Tracks.CLASS_CONF)).append(") ");
            }
        }
        return note.toString().trim();
    }

    record Posterior(ObjectClass best, double confidence) {
    }

    static Posterior posterior(double speed, double sigMean, int n) {
        ObjectClass[] classes = ObjectClass.values();
        double[] ll = new double[classes.length];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < classes.length; i++) {
            ll[i] = classes[i].logLikelihood(speed, sigMean, n);
            max = Math.max(max, ll[i]);
        }
        double sum = 0;
        int best = 0;
        for (int i = 0; i < classes.length; i++) {
            ll[i] = Math.exp(ll[i] - max);
            sum += ll[i];
            if (ll[i] > ll[best]) best = i;
        }
        return new Posterior(classes[best], ll[best] / sum);
    }

    static Hypothesis classify(Hypothesis tr) {
        if (!Tracks.CONFIRMED.equals(tr.tag(Tracks.STATUS))) return null;
        int n = (int) tr.num("hits");
        Posterior p = posterior(Tracks.speed(tr), tr.num("sigSum") / n, n);
        return tr.withTag(Tracks.CLASS, p.best().name()).with(Tracks.CLASS_CONF, Tracks.round(p.confidence(), 100));
    }
}
