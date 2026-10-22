package io.github.pgallo.blackboard.scenario;

import io.github.pgallo.blackboard.domain.ObjState;
import io.github.pgallo.blackboard.domain.SituationRules;
import io.github.pgallo.blackboard.domain.Zone;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A generated scenario: sensors, ground truth, the detection stream and the expected situations. */
public record Scenario(long seed, List<Sensor> sensors, List<Target> targets, Zone zone,
                       double endTime, List<Detection> detections) {

    public Map<String, String> labelByDetection() {
        return detections.stream().collect(Collectors.toMap(Detection::id, Detection::label));
    }

    public List<ObjState> truthAt(double t) {
        return targets.stream().filter(Target::real).map(tg -> tg.at(t)).toList();
    }

    /** Ground-truth reference: the same rule base applied to true states at the end of the run. */
    public List<SituationRules.Finding> expectedSituations() {
        return SituationRules.evaluate(truthAt(endTime), zone);
    }

    public Map<String, Target> targetsByLabel() {
        return targets.stream().collect(Collectors.toMap(Target::label, Function.identity()));
    }
}
