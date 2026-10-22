package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.ChangeEvent;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * What a knowledge source declares when it registers: its name (also the author tag of its
 * writes), its trigger patterns, and a prior cost estimate in milliseconds that the
 * controller replaces with measured execution times.
 */
public record KsSpec(String name, List<TriggerPattern> triggers, double priorCostMs) implements Serializable {

    public KsSpec {
        triggers = List.copyOf(triggers);
    }

    /** First matching pattern, so a KS with several patterns maps each event to one partition. */
    public Optional<TriggerPattern> match(ChangeEvent e) {
        for (TriggerPattern p : triggers) if (p.matches(e, name)) return Optional.of(p);
        return Optional.empty();
    }
}
