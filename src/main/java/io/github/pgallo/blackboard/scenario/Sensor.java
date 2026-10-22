package io.github.pgallo.blackboard.scenario;

/**
 * Simulated sensor. Every sensor covers the whole area; they differ in accuracy, scan phase
 * and clutter. Velocity noise is proportional to target speed ({@code velFactor} scales it).
 */
public record Sensor(String id, double posSd, double velFactor, double pd, double clutterRate, double offsetS) {

    public double velSd(double speed) {
        return velFactor * (0.05 * speed + 0.5);
    }
}
