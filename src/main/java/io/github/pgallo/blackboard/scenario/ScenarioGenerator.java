package io.github.pgallo.blackboard.scenario;

import io.github.pgallo.blackboard.domain.ObjectClass;
import io.github.pgallo.blackboard.domain.Zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Seeded generator for a multi-sensor maritime and air picture. The geometry is a set of
 * scripted vignettes (a formation, a zone incursion, a rendezvous, a bystander and a
 * single-sensor artifact) placed with seeded jitter; the seed also drives measurement
 * noise, missed detections and clutter. Margins are chosen so that the expected situations
 * are stable under the jitter.
 */
public final class ScenarioGenerator {

    public static final double SCAN_PERIOD_S = 10.0;
    public static final int DEFAULT_SCANS = 18;
    public static final double AREA_MIN = -5000;
    public static final double AREA_MAX = 45000;
    /** The restricted zone is a mission parameter, known to the situation assessor. */
    public static final Zone DEFAULT_ZONE = new Zone("ZONE-A", 20000, 20000, 5000);

    private ScenarioGenerator() {
    }

    public static List<Sensor> defaultSensors() {
        return List.of(
                new Sensor("S1", 50, 1.0, 0.92, 4.0, 0.0),
                new Sensor("S2", 80, 1.3, 0.90, 4.0, 3.0),
                new Sensor("S3", 120, 1.6, 0.90, 4.0, 6.0));
    }

    public static Scenario generate(long seed) {
        return generate(seed, DEFAULT_SCANS);
    }

    public static Scenario generate(long seed, int scans) {
        Random rnd = new Random(seed);
        List<Sensor> sensors = defaultSensors();
        Zone zone = DEFAULT_ZONE;
        double endTime = (scans - 1) * SCAN_PERIOD_S + sensors.get(sensors.size() - 1).offsetS();
        List<Target> targets = new ArrayList<>();

        // Formation: two aircraft 1200 m abeam, identical velocity, eastbound well north of the zone.
        {
            double[] d = jitter(rnd, 4000, 30000);
            double[] v = rotate(rnd, 190, 0);
            targets.add(new Target("A1", ObjectClass.AIRCRAFT, d[0], d[1], v[0], v[1], null));
            targets.add(new Target("A2", ObjectClass.AIRCRAFT, d[0] - v[1] / 190 * 1200, d[1] + v[0] / 190 * 1200, v[0], v[1], null));
        }
        // Incursion: an aircraft heading for the zone centre, about 2 km inside the zone at the end of the run.
        {
            double speed = 150 * (1 + (rnd.nextDouble() - 0.5) * 0.1);
            double bearing = Math.toRadians(-45 + (rnd.nextDouble() - 0.5) * 20);
            double dEnd = 3000, d0 = dEnd + speed * endTime;
            double ux = Math.cos(bearing), uy = Math.sin(bearing);
            targets.add(new Target("A3", ObjectClass.AIRCRAFT, zone.cx() + ux * d0, zone.cy() + uy * d0,
                    -ux * speed, -uy * speed, null));
        }
        // Rendezvous: a vessel and a fast boat whose tracks meet 90 s after the end of the run, 50 m apart.
        {
            double meet = endTime + 90;
            double[] p = jitter(rnd, 9000, 9000);
            double[] v1 = rotate(rnd, 6.58, Math.toDegrees(Math.atan2(-1, 6.5)));
            double[] v2 = rotate(rnd, 18.44, Math.toDegrees(Math.atan2(14, -12)));
            targets.add(new Target("V1", ObjectClass.VESSEL, p[0] - v1[0] * meet, p[1] - v1[1] * meet, v1[0], v1[1], null));
            targets.add(new Target("B1", ObjectClass.FASTBOAT, p[0] + 50 - v2[0] * meet, p[1] - v2[1] * meet, v2[0], v2[1], null));
        }
        // Bystander: a vessel steaming away from everything.
        {
            double[] p = jitter(rnd, 30000, 6000);
            double[] v = rotate(rnd, 5, Math.toDegrees(Math.atan2(-3, 4)));
            targets.add(new Target("V2", ObjectClass.VESSEL, p[0], p[1], v[0], v[1], null));
        }
        // Artifact: a coherent false target seen only by S2, fast-boat-like, heading into the zone.
        {
            double[] p = jitter(rnd, 25000, 27000);
            double dx = zone.cx() - p[0], dy = zone.cy() - p[1], n = Math.hypot(dx, dy);
            targets.add(new Target("G1", ObjectClass.FASTBOAT, p[0], p[1], dx / n * 16, dy / n * 16, "S2"));
        }

        List<Detection> detections = new ArrayList<>();
        for (int scan = 0; scan < scans; scan++) {
            for (Sensor s : sensors) {
                double t = scan * SCAN_PERIOD_S + s.offsetS();
                List<Detection> batch = new ArrayList<>();
                for (Target tg : targets) {
                    if (!tg.real() && !tg.onlySensor().equals(s.id())) continue;
                    if (rnd.nextDouble() > s.pd()) continue;
                    double x = tg.x0() + tg.vx() * t, y = tg.y0() + tg.vy() * t;
                    double vsd = s.velSd(Math.hypot(tg.vx(), tg.vy()));
                    batch.add(new Detection(null, s.id(), scan, t,
                            x + rnd.nextGaussian() * s.posSd(), y + rnd.nextGaussian() * s.posSd(),
                            tg.vx() + rnd.nextGaussian() * vsd, tg.vy() + rnd.nextGaussian() * vsd,
                            tg.cls().signatureMean + rnd.nextGaussian() * ObjectClass.SIGNATURE_SD, tg.label()));
                }
                int clutter = poisson(rnd, s.clutterRate());
                for (int c = 0; c < clutter; c++) {
                    double heading = rnd.nextDouble() * 2 * Math.PI, speed = rnd.nextDouble() * 250;
                    batch.add(new Detection(null, s.id(), scan, t,
                            uniform(rnd, AREA_MIN, AREA_MAX), uniform(rnd, AREA_MIN, AREA_MAX),
                            Math.cos(heading) * speed, Math.sin(heading) * speed, rnd.nextDouble() * 35, "clutter"));
                }
                // Shuffle so that the detection index carries no information about the source.
                Collections.shuffle(batch, rnd);
                for (int i = 0; i < batch.size(); i++) {
                    Detection d = batch.get(i);
                    String id = String.format(Locale.ROOT, "D/%s/%02d/%02d", s.id(), scan, i);
                    detections.add(new Detection(id, d.sensor(), d.scan(), d.t(), d.x(), d.y(), d.vx(), d.vy(),
                            d.signature(), d.label()));
                }
            }
        }
        detections.sort(Comparator.comparingDouble(Detection::t).thenComparing(Detection::id));
        return new Scenario(seed, sensors, List.copyOf(targets), zone, endTime, List.copyOf(detections));
    }

    private static double[] jitter(Random rnd, double x, double y) {
        return new double[]{x + uniform(rnd, -500, 500), y + uniform(rnd, -500, 500)};
    }

    /** Velocity of the given speed and heading (degrees, maths convention), jittered by +-3 degrees and +-5 %. */
    private static double[] rotate(Random rnd, double speed, double headingDeg) {
        double h = Math.toRadians(headingDeg + uniform(rnd, -3, 3));
        double s = speed * (1 + uniform(rnd, -0.05, 0.05));
        return new double[]{Math.cos(h) * s, Math.sin(h) * s};
    }

    private static double uniform(Random rnd, double lo, double hi) {
        return lo + rnd.nextDouble() * (hi - lo);
    }

    private static int poisson(Random rnd, double mean) {
        double l = Math.exp(-mean), p = 1.0;
        int k = 0;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > l);
        return k - 1;
    }
}
