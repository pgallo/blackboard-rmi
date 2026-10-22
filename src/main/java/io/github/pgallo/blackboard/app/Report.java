package io.github.pgallo.blackboard.app;

import io.github.pgallo.blackboard.control.Controller;
import io.github.pgallo.blackboard.control.TraceEntry;
import io.github.pgallo.blackboard.core.BlackboardStore;
import io.github.pgallo.blackboard.core.BoardStats;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.scenario.Scenario;

import java.io.PrintStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Human-readable end-of-run report: board state, scoring against ground truth, activation trace. */
public final class Report {

    /** Trace kinds that are always printed, whatever the trace length. */
    private static final Set<TraceEntry.Kind> NOTABLE = EnumSet.of(TraceEntry.Kind.REGISTERED, TraceEntry.Kind.FAILED,
            TraceEntry.Kind.LATE, TraceEntry.Kind.LEASE_EXPIRED, TraceEntry.Kind.TIMED_OUT, TraceEntry.Kind.REQUEUED,
            TraceEntry.Kind.ABANDONED, TraceEntry.Kind.TERMINATED);

    private Report() {
    }

    public static void print(PrintStream out, BlackboardStore board, Controller control, Scenario scenario, int traceHead) {
        BoardStats st = board.stats();
        Map<String, String> labels = Truth.objectLabels(board, scenario);
        out.println();
        out.println("=== Final blackboard state (seed " + scenario.seed() + ") ===");
        out.printf(Locale.ROOT, "journal %d events, CAS conflicts %d, suppressed no-op writes %d%n",
                st.journalHead(), st.casConflicts(), st.suppressedNoOps());
        for (Level l : Level.values()) out.printf(Locale.ROOT, "  %-14s %4d%n", l, st.countsByLevel().get(l));
        long confirmed = board.query(Level.TRACK).stream().filter(t -> "CONFIRMED".equals(t.tag("status"))).count();
        out.printf(Locale.ROOT, "  (tracks confirmed: %d)%n", confirmed);

        out.println();
        out.println("Objects:");
        out.printf(Locale.ROOT, "  %-16s %-9s %5s %6s %-9s %-28s %s%n", "id", "class", "conf", "belief", "sensors", "critique", "truth");
        for (Hypothesis o : board.query(Level.OBJECT)) {
            out.printf(Locale.ROOT, "  %-16s %-9s %5.2f %6.3f %-9s %-28s %s%n", o.id(), nz(o.tag("class")),
                    o.num("classConf", 0), o.belief(), nz(o.tag("sensors")), nz(o.tag("critique")), labels.get(o.id()));
        }

        out.println();
        out.println("Situations:");
        for (Hypothesis s : board.query(Level.SITUATION)) {
            out.printf(Locale.ROOT, "  %-9s belief %.3f v%-3d %-48s support %s%n", s.tag("status"), s.belief(), s.version(),
                    s.id(), s.support().stream().map(id -> id + "(" + labels.get(id) + ")").toList());
        }

        List<String> expected = Truth.expectedSituations(scenario);
        List<String> actual = Truth.activeSituations(board, scenario, 0.5);
        out.println();
        out.println("Expected (rules on ground truth):  " + expected);
        out.println("Assessed (active, belief >= 0.5):  " + actual);
        out.println(expected.equals(actual) ? "RESULT: assessment matches ground truth" : "RESULT: MISMATCH");

        List<TraceEntry> trace = control.trace();
        // Follow every activation that had to be retried, so the reassignment is visible end to end.
        Set<Long> retried = new java.util.HashSet<>();
        trace.stream().filter(e -> e.kind() == TraceEntry.Kind.REQUEUED).forEach(e -> retried.add(e.activationId()));
        out.println();
        out.println("=== KS activation trace (" + trace.size() + " entries; first " + traceHead
                + ", then faults, retried activations and termination) ===");
        for (TraceEntry e : trace) {
            if (e.n() <= traceHead || NOTABLE.contains(e.kind()) || retried.contains(e.activationId())) {
                out.println(e.format());
            }
        }
        out.println();
        out.println("Per knowledge source:");
        control.ksSummary().forEach((ks, line) -> out.printf(Locale.ROOT, "  %-17s %s%n", ks, line));
        out.println("Termination: " + control.termination());
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }
}
