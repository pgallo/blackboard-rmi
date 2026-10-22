package io.github.pgallo.blackboard.core;

import java.io.Serializable;
import java.util.Map;

/**
 * One entry of the blackboard journal. Events are totally ordered by {@code seq}
 * (1-based, gap free), which lets the controller keep one delivery cursor per
 * knowledge-source type instead of per-subscriber queues. The tag snapshot is carried
 * so that triggers and focus of attention can be evaluated without a read-back.
 */
public record ChangeEvent(long seq, Type type, String hypothesisId, Level level, long version,
                          double belief, Map<String, String> tags, String author) implements Serializable {

    public enum Type { CREATED, UPDATED }

    public String tag(String key) {
        return tags.get(key);
    }
}
