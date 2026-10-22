package io.github.pgallo.blackboard.scenario;

import io.github.pgallo.blackboard.app.Truth;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioGeneratorTest {

    @Test
    void sameSeedSameScenario() {
        assertEquals(ScenarioGenerator.generate(7), ScenarioGenerator.generate(7));
        assertTrue(!ScenarioGenerator.generate(7).detections().equals(ScenarioGenerator.generate(8).detections()));
    }

    @Test
    void detectionsAreTimeOrderedUniqueAndIncludeClutterAndMisses() {
        Scenario s = ScenarioGenerator.generate(11);
        List<Detection> d = s.detections();
        for (int i = 1; i < d.size(); i++) assertTrue(d.get(i - 1).t() <= d.get(i).t());
        assertEquals(d.size(), d.stream().map(Detection::id).distinct().count());
        long clutter = d.stream().filter(x -> x.label().equals("clutter")).count();
        assertTrue(clutter > 100, "clutter " + clutter);
        long real = s.targets().stream().filter(Target::real).count();
        long maxReal = real * ScenarioGenerator.DEFAULT_SCANS * s.sensors().size();
        long seenReal = d.stream().filter(x -> !x.label().equals("clutter") && !x.label().equals("G1")).count();
        assertTrue(seenReal < maxReal, "some detections are missed");
        assertTrue(d.stream().filter(x -> x.label().equals("G1")).allMatch(x -> x.sensor().equals("S2")),
                "the artifact is produced by a single sensor");
    }

    @Test
    void expectedSituationsAreStableAcrossSeeds() {
        List<String> expected = List.of("FORMATION[A1+A2]", "RENDEZVOUS[B1+V1]", "ZONE_INCURSION[A3]");
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(expected, Truth.expectedSituations(ScenarioGenerator.generate(seed)), "seed " + seed);
        }
    }

    @Test
    void detectionsMapToRawDetectionHypothesesWithoutGroundTruth() {
        Detection d = ScenarioGenerator.generate(3).detections().get(0);
        var h = d.toHypothesis();
        assertEquals(d.id(), h.id());
        assertEquals(d.sensor(), h.tag("sensor"));
        assertTrue(!h.tags().containsKey("label") && !h.tags().containsValue(d.label()));
    }
}
