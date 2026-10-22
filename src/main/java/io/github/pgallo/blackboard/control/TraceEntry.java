package io.github.pgallo.blackboard.control;

import java.io.Serializable;
import java.util.Locale;

/** One line of the controller's activation trace. */
public record TraceEntry(long n, long atMs, Kind kind, String ks, String worker, long activationId,
                         int attempt, String context, double rating, String note) implements Serializable {

    public enum Kind { REGISTERED, DISPATCHED, DONE, FAILED, LATE, LEASE_EXPIRED, TIMED_OUT, REQUEUED, ABANDONED, TERMINATED }

    public String format() {
        String act = activationId > 0 ? "#" + activationId + (attempt > 1 ? "/a" + attempt : "") : "";
        String r = rating > 0 ? String.format(Locale.ROOT, "r=%.3f", rating) : "";
        return String.format(Locale.ROOT, "%6d ms  %-13s %-17s %-12s %-9s %-24s %-9s %s",
                atMs, kind, ks == null ? "" : ks, worker == null ? "" : worker, act,
                context == null ? "" : context, r, note == null ? "" : note).stripTrailing();
    }
}
