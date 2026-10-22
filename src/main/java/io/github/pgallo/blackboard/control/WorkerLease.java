package io.github.pgallo.blackboard.control;

import java.io.Serializable;

/** Granted at registration: the worker must renew within {@code leaseMs}, ideally every {@code heartbeatMs}. */
public record WorkerLease(String workerId, long leaseMs, long heartbeatMs) implements Serializable {
}
