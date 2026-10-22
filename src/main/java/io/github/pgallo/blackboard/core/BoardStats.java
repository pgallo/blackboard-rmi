package io.github.pgallo.blackboard.core;

import java.io.Serializable;
import java.util.Map;

/** Counters exposed for reporting. */
public record BoardStats(long journalHead, Map<Level, Integer> countsByLevel,
                         long casConflicts, long suppressedNoOps) implements Serializable {
}
