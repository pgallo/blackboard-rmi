package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.control.Activation;
import io.github.pgallo.blackboard.control.KnowledgeSource;
import io.github.pgallo.blackboard.control.KsContext;
import io.github.pgallo.blackboard.control.KsSpec;
import io.github.pgallo.blackboard.control.TriggerPattern;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.domain.Beliefs;
import io.github.pgallo.blackboard.domain.ObjState;
import io.github.pgallo.blackboard.domain.ObjectClass;
import io.github.pgallo.blackboard.domain.SituationRules;
import io.github.pgallo.blackboard.domain.Zone;

import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Object to Situation with the rule base in {@link SituationRules}. Only credible objects
 * (belief at least {@link #CREDIBLE}) take part, so the Critic's penalties propagate
 * upward: when an object is discredited, the situations that depended on it are retracted
 * (kept on the board with status RETRACTED, for provenance) instead of silently deleted.
 */
public final class SituationAssessor implements KnowledgeSource {

    public static final String NAME = "SituationAssessor";
    public static final double CREDIBLE = 0.5;
    public static final String ACTIVE = "ACTIVE";
    public static final String RETRACTED = "RETRACTED";

    private final Zone zone;

    public SituationAssessor(Zone zone) {
        this.zone = zone;
    }

    @Override
    public KsSpec spec() {
        return new KsSpec(NAME, List.of(TriggerPattern.on(Level.OBJECT).gain(1.5)), 2.0);
    }

    @Override
    public String execute(Activation a, KsContext ctx) throws RemoteException {
        List<Hypothesis> credible = ctx.query(Level.OBJECT).stream().filter(o -> o.belief() >= CREDIBLE).toList();
        Map<String, Hypothesis> byId = credible.stream().collect(Collectors.toMap(Hypothesis::id, Function.identity()));
        double t = credible.stream().mapToDouble(o -> o.num("t")).max().orElse(0);
        List<ObjState> states = credible.stream().map(o -> state(o, t)).toList();

        Set<String> found = new HashSet<>();
        int asserted = 0, retracted = 0;
        for (SituationRules.Finding f : SituationRules.evaluate(states, zone)) {
            String id = f.key();
            found.add(id);
            double minMember = f.members().stream().mapToDouble(m -> byId.get(m).belief()).min().orElse(0);
            double belief = Beliefs.round(f.confidence() * minMember);
            Hypothesis proposal = new Hypothesis(id, Level.SITUATION, 0, 0.0, f.metrics(),
                    Map.of("type", f.type(), "status", ACTIVE), f.members(), "");
            var created = ctx.create(Beliefs.withBase(proposal.with("confidence", f.confidence()), belief));
            if (created.status() == io.github.pgallo.blackboard.core.WriteResult.Status.CREATED) {
                asserted++;
                continue;
            }
            var out = ctx.update(id, cur -> Beliefs.withBase(cur.with(f.metrics()).with("confidence", f.confidence())
                    .withTag("status", ACTIVE), belief));
            if (out.changed() && RETRACTED.equals(created.current().tag("status"))) asserted++;
        }
        for (Hypothesis s : ctx.query(Level.SITUATION)) {
            if (found.contains(s.id()) || RETRACTED.equals(s.tag("status"))) continue;
            ctx.update(s.id(), cur -> Beliefs.withBase(cur, 0.0).withTag("status", RETRACTED));
            retracted++;
        }
        return "credible objects " + credible.size() + ", situations " + found.size()
                + (asserted > 0 ? ", asserted " + asserted : "") + (retracted > 0 ? ", retracted " + retracted : "");
    }

    static ObjState state(Hypothesis o, double t) {
        String cls = o.tag(Tracks.CLASS);
        ObjectClass c = cls != null && o.num(Tracks.CLASS_CONF, 0) >= Tracks.CLASS_THRESHOLD ? ObjectClass.valueOf(cls) : null;
        return new ObjState(o.id(), c, Tracks.predX(o, t), Tracks.predY(o, t), o.num("vx"), o.num("vy"), o.belief());
    }
}
