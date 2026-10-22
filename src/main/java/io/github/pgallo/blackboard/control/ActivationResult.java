package io.github.pgallo.blackboard.control;

import java.io.Serializable;

/** Outcome of one KS execution, reported back through {@link ControlService#complete}. */
public record ActivationResult(boolean ok, int created, int updated, int unchanged,
                               int conflicts, String note) implements Serializable {

    public static ActivationResult error(String message) {
        return new ActivationResult(false, 0, 0, 0, 0, message);
    }
}
