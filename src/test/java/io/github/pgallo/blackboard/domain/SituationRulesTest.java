package io.github.pgallo.blackboard.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SituationRulesTest {

    private final Zone zone = new Zone("Z", 0, 0, 1000);

    private static ObjState o(String id, ObjectClass c, double x, double y, double vx, double vy) {
        return new ObjState(id, c, x, y, vx, vy, 1.0);
    }

    private List<String> keys(List<ObjState> objs) {
        return SituationRules.evaluate(objs, zone).stream().map(SituationRules.Finding::key).toList();
    }

    @Test
    void formationGroupsSameClassMoversTransitively() {
        List<ObjState> objs = List.of(
                o("a", ObjectClass.AIRCRAFT, 50000, 0, 200, 0),
                o("b", ObjectClass.AIRCRAFT, 50000, 1500, 200, 5),
                o("c", ObjectClass.AIRCRAFT, 50000, 3000, 198, 0),       // 3 km from a, but linked through b
                o("d", ObjectClass.AIRCRAFT, 50000, 4500, -200, 0),      // close to c but opposite heading
                o("v", ObjectClass.VESSEL, 50000, 6000, 6, 0));
        assertEquals(List.of("S/FORMATION/a+b+c"), keys(objs));
    }

    @Test
    void incursionFiresInsideOrWithinHorizonOnly() {
        assertEquals(List.of("S/ZONE_INCURSION/in"), keys(List.of(o("in", ObjectClass.VESSEL, 100, 0, 1, 0))));
        assertEquals(List.of("S/ZONE_INCURSION/soon"), keys(List.of(o("soon", ObjectClass.AIRCRAFT, 20000, 0, -100, 0))));
        assertTrue(keys(List.of(o("late", ObjectClass.VESSEL, 20000, 0, -10, 0))).isEmpty(), "eta 1900 s is beyond the horizon");
        assertTrue(keys(List.of(o("away", ObjectClass.AIRCRAFT, 2000, 0, 100, 0))).isEmpty());
        assertTrue(keys(List.of(o("miss", ObjectClass.AIRCRAFT, 5000, 3000, -100, 0))).isEmpty());
    }

    @Test
    void rendezvousNeedsTwoDifferentSurfaceClassesConverging() {
        ObjState v = o("v", ObjectClass.VESSEL, 10000, 10000, 6, 0);
        ObjState b = o("b", ObjectClass.FASTBOAT, 11200 + 18 * 0, 13000, 0, -15);   // meets v near (11200, 10000) after ~200 s
        assertEquals(List.of("S/RENDEZVOUS/b+v"), keys(List.of(v, b)));
        ObjState b2 = o("b2", ObjectClass.VESSEL, 11200, 13000, 0, -15);
        assertTrue(keys(List.of(v, b2)).isEmpty(), "same class");
        ObjState far = o("f", ObjectClass.FASTBOAT, 30000, 13000, 0, -15);
        assertTrue(keys(List.of(v, far)).isEmpty(), "miss distance too large");
    }

    @Test
    void cpaAndTimeToEnterGeometry() {
        Kinematics.Cpa c = Kinematics.cpa(o("a", null, 0, 0, 10, 0), o("b", null, 1000, 100, -10, 0));
        assertEquals(50.0, c.tcpa(), 1e-9);
        assertEquals(100.0, c.distance(), 1e-9);
        assertEquals(9.0, Kinematics.timeToEnter(o("x", null, 1000, 0, -100, 0), 0, 0, 100), 1e-9);
        assertEquals(0.0, Kinematics.timeToEnter(o("x", null, 10, 0, 5, 0), 0, 0, 100), 0.0);
    }

    @Test
    void noisyOrCombinesIndependentEvidence() {
        assertEquals(0.75, Beliefs.noisyOr(0.5, 0.5), 1e-12);
        assertEquals(0.0, Beliefs.noisyOr(), 0.0);
    }
}
