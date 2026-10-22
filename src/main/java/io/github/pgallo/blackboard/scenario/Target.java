package io.github.pgallo.blackboard.scenario;

import io.github.pgallo.blackboard.domain.ObjState;
import io.github.pgallo.blackboard.domain.ObjectClass;

/**
 * A moving emitter. Real targets are seen by every sensor; an artifact ({@code onlySensor}
 * set) is a coherent false target produced by one sensor, the kind of thing that survives
 * single-sensor tracking and has to be caught by cross-sensor corroboration.
 */
public record Target(String label, ObjectClass cls, double x0, double y0, double vx, double vy, String onlySensor) {

    public boolean real() {
        return onlySensor == null;
    }

    public ObjState at(double t) {
        return new ObjState(label, cls, x0 + vx * t, y0 + vy * t, vx, vy, 1.0);
    }
}
