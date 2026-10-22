package io.github.pgallo.blackboard.domain;

/** Kinematic and class state of one object, as seen by the situation rules. */
public record ObjState(String id, ObjectClass cls, double x, double y, double vx, double vy, double belief) {

    public double speed() {
        return Math.hypot(vx, vy);
    }
}
