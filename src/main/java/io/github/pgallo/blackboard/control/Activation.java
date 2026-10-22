package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;

import java.io.Serializable;
import java.util.List;

/** A knowledge-source activation record as shipped to a worker. The triggering events are the notification. */
public record Activation(long id, int attempt, String ks, String contextKey,
                         List<ChangeEvent> events, double rating) implements Serializable {

    public Activation {
        events = List.copyOf(events);
    }

    public List<String> hypothesisIds() {
        return events.stream().map(ChangeEvent::hypothesisId).toList();
    }
}
