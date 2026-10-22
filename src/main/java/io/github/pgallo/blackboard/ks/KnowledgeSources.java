package io.github.pgallo.blackboard.ks;

import io.github.pgallo.blackboard.control.KnowledgeSource;
import io.github.pgallo.blackboard.scenario.ScenarioGenerator;

import java.util.List;
import java.util.Set;

/** Factory used by worker processes: one KS type per worker. */
public final class KnowledgeSources {

    public static final List<String> NAMES = List.of(Correlator.NAME, Classifier.NAME, Fuser.NAME,
            SituationAssessor.NAME, Critic.NAME);

    private KnowledgeSources() {
    }

    public static Set<String> required() {
        return Set.copyOf(NAMES);
    }

    public static KnowledgeSource create(String name) {
        return switch (name) {
            case Correlator.NAME -> new Correlator(ScenarioGenerator.defaultSensors());
            case Classifier.NAME -> new Classifier();
            case Fuser.NAME -> new Fuser(ScenarioGenerator.defaultSensors());
            case SituationAssessor.NAME -> new SituationAssessor(ScenarioGenerator.DEFAULT_ZONE);
            case Critic.NAME -> new Critic();
            default -> throw new IllegalArgumentException("unknown knowledge source '" + name + "', expected one of " + NAMES);
        };
    }
}
