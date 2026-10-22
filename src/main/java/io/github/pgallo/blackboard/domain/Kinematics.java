package io.github.pgallo.blackboard.domain;

/** Constant-velocity geometry in a flat local frame (metres, seconds). */
public final class Kinematics {

    private Kinematics() {
    }

    public static double dist(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    public record Cpa(double tcpa, double distance) {
    }

    /** Closest point of approach of two constant-velocity movers, from now on. */
    public static Cpa cpa(ObjState a, ObjState b) {
        double px = b.x() - a.x(), py = b.y() - a.y();
        double vx = b.vx() - a.vx(), vy = b.vy() - a.vy();
        double vv = vx * vx + vy * vy;
        double t = vv < 1e-9 ? 0.0 : Math.max(0.0, -(px * vx + py * vy) / vv);
        return new Cpa(t, Math.hypot(px + vx * t, py + vy * t));
    }

    /**
     * Time until a constant-velocity mover enters a circle: 0 if inside, +infinity if the
     * ray misses the circle or points away from it.
     */
    public static double timeToEnter(ObjState o, double cx, double cy, double r) {
        double px = o.x() - cx, py = o.y() - cy;
        double c = px * px + py * py - r * r;
        if (c <= 0) return 0.0;
        double a = o.vx() * o.vx() + o.vy() * o.vy();
        double b = 2 * (px * o.vx() + py * o.vy());
        double disc = b * b - 4 * a * c;
        if (a < 1e-9 || disc < 0 || b >= 0) return Double.POSITIVE_INFINITY;
        return (-b - Math.sqrt(disc)) / (2 * a);
    }
}
