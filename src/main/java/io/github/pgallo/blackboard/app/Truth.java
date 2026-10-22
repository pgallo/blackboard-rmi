package io.github.pgallo.blackboard.app;

import io.github.pgallo.blackboard.core.BlackboardStore;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.scenario.Scenario;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Evaluation only: follows support links down to raw detections and maps each object to
 * the ground-truth label that most of its detections came from. The knowledge sources
 * never see labels; this is what the end-to-end test and the demo report use to score the run.
 */
public final class Truth {

    private Truth() {
    }

    public static Map<String, String> objectLabels(BlackboardStore board, Scenario scenario) {
        Map<String, String> labelByDet = scenario.labelByDetection();
        Map<String, String> out = new TreeMap<>();
        for (Hypothesis o : board.query(Level.OBJECT)) {
            Map<String, Integer> votes = new HashMap<>();
            for (Hypothesis t : board.getAll(o.support())) {
                for (String d : t.support()) votes.merge(labelByDet.getOrDefault(d, "?"), 1, Integer::sum);
            }
            out.put(o.id(), votes.entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse("?"));
        }
        return out;
    }

    /** Situation signatures "TYPE[label+label]" of every ACTIVE situation with belief at least {@code minBelief}. */
    public static List<String> activeSituations(BlackboardStore board, Scenario scenario, double minBelief) {
        Map<String, String> labels = objectLabels(board, scenario);
        return board.query(Level.SITUATION).stream()
                .filter(s -> "ACTIVE".equals(s.tag("status")) && s.belief() >= minBelief)
                .map(s -> signature(s.tag("type"), s.support().stream().map(labels::get).filter(Objects::nonNull).toList()))
                .sorted().toList();
    }

    public static List<String> expectedSituations(Scenario scenario) {
        return scenario.expectedSituations().stream().map(f -> signature(f.type(), f.members())).sorted().toList();
    }

    static String signature(String type, List<String> members) {
        return type + "[" + members.stream().sorted().collect(Collectors.joining("+")) + "]";
    }
}
