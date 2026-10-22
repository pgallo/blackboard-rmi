package io.github.pgallo.blackboard.control;

import java.util.Set;

/**
 * @param leaseMs              worker lease; a worker silent for longer is declared dead
 * @param heartbeatMs          suggested renewal period handed to workers
 * @param activationTimeoutMs  in-flight activations older than this are reassigned even if the worker is alive
 * @param quiescenceMs         how long the board must stay quiet before the run is declared converged
 * @param maxAttempts          an activation failing this many times is abandoned
 * @param maxActivations       hard budget on dispatched activations
 * @param requiredKs           knowledge-source types that must have registered before quiescence counts
 */
public record ControllerConfig(long leaseMs, long heartbeatMs, long activationTimeoutMs, long quiescenceMs,
                               int maxAttempts, int maxActivations, Set<String> requiredKs) {

    public ControllerConfig {
        requiredKs = Set.copyOf(requiredKs);
    }

    public static ControllerConfig defaults(Set<String> requiredKs) {
        return new ControllerConfig(1200, 300, 8000, 400, 3, 100_000, requiredKs);
    }

    public ControllerConfig withLease(long lease, long heartbeat) {
        return new ControllerConfig(lease, heartbeat, activationTimeoutMs, quiescenceMs, maxAttempts, maxActivations, requiredKs);
    }

    public ControllerConfig withActivationTimeout(long ms) {
        return new ControllerConfig(leaseMs, heartbeatMs, ms, quiescenceMs, maxAttempts, maxActivations, requiredKs);
    }
}
