package io.github.pgallo.blackboard.core;

import java.io.Serializable;

/** Result of a blackboard write. {@code current} is always the value now stored (or null if absent). */
public record WriteResult(Status status, Hypothesis current) implements Serializable {

    public enum Status {
        /** New hypothesis stored. */
        CREATED,
        /** Compare-and-set succeeded and the version was bumped. */
        UPDATED,
        /** Compare-and-set matched but the content was identical: no version bump, no event. */
        UNCHANGED,
        /** Create of an id that already exists (writers use natural keys, so this means "someone got there first"). */
        EXISTS,
        /** Compare-and-set lost: the hypothesis moved on since it was read. */
        CONFLICT,
        NOT_FOUND
    }
}
