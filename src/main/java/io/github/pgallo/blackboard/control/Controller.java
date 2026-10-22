package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.BlackboardStore;
import io.github.pgallo.blackboard.core.ChangeEvent;
import io.github.pgallo.blackboard.core.Level;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Control component. It runs next to the blackboard (it reads the journal directly) and
 * does four things on every {@link #step()}:
 * <ol>
 *   <li>matches new journal events against every registered trigger pattern and coalesces
 *       them into activation records on the agenda,</li>
 *   <li>expires leases of silent workers and requeues their in-flight work,</li>
 *   <li>picks the best-rated eligible records and dispatches them to idle workers via RMI callback,</li>
 *   <li>evaluates the termination condition.</li>
 * </ol>
 * All state is guarded by {@code this}; RMI callbacks are made outside the monitor.
 */
public final class Controller implements ControlService {

    public enum Termination { QUIESCENT, DEADLINE, BUDGET }

    private static final int EVENT_BATCH = 4096;
    private static final double COST_EWMA = 0.3;

    private final BlackboardStore board;
    private final ControllerConfig cfg;
    private final LongSupplier clock;
    private final long startedAt;

    private final Map<String, KsSpec> specs = new LinkedHashMap<>();       // by KS name, survives worker death
    private final Map<String, Long> cursors = new HashMap<>();             // last journal seq ingested, per KS name
    private final Map<String, Double> costMs = new HashMap<>();            // measured execution cost, per KS name
    private final Map<String, Worker> workers = new LinkedHashMap<>();
    private final Map<Long, InFlight> inFlight = new LinkedHashMap<>();
    private final Set<String> busyContexts = new HashSet<>();
    private final Agenda agenda = new Agenda();
    private final LeaseTable leases;
    private final List<TraceEntry> trace = new ArrayList<>();                // guarded by this
    private final Map<String, int[]> perKs = new LinkedHashMap<>();        // dispatched, done, failed, conflicts

    private Level focus = Level.RAW_DETECTION;
    private boolean inputClosed;
    private long lastActivityAt;
    private long deadlineAt = Long.MAX_VALUE;
    private int dispatched;
    private int workerSeq;
    private Termination termination;
    private Thread loop;
    private volatile boolean running;

    private static final class Worker {
        final String id;
        final KsSpec spec;
        final KsWorker callback;
        Long busyWith;   // activation id, capacity is one
        int served;

        Worker(String id, KsSpec spec, KsWorker callback) {
            this.id = id;
            this.spec = spec;
            this.callback = callback;
        }
    }

    private record InFlight(Ksar ksar, String workerId, int attempt, long startedAt, double rating) {
    }

    private record Dispatch(Worker worker, Activation activation) {
    }

    public Controller(BlackboardStore board, ControllerConfig cfg) {
        this(board, cfg, System::currentTimeMillis);
    }

    public Controller(BlackboardStore board, ControllerConfig cfg, LongSupplier clock) {
        this.board = board;
        this.cfg = cfg;
        this.clock = clock;
        this.leases = new LeaseTable(cfg.leaseMs());
        this.startedAt = clock.getAsLong();
        this.lastActivityAt = startedAt;
        board.addListener(this::wake);
    }

    // ---------------------------------------------------------------- remote API

    @Override
    public synchronized WorkerLease register(KsSpec spec, KsWorker callback, String preferredId) {
        long now = clock.getAsLong();
        KsSpec known = specs.putIfAbsent(spec.name(), spec);
        if (known == null) {
            cursors.put(spec.name(), 0L);   // a new KS type replays the journal from the start
            costMs.put(spec.name(), spec.priorCostMs());
        }
        String id = preferredId != null && !preferredId.isBlank() && !workers.containsKey(preferredId)
                ? preferredId : spec.name() + "-" + (++workerSeq);
        workers.put(id, new Worker(id, specs.get(spec.name()), callback));
        leases.grant(id, now);
        record(now, TraceEntry.Kind.REGISTERED, spec.name(), id, 0, 0, null, 0,
                spec.triggers().size() + " trigger(s), lease " + cfg.leaseMs() + " ms");
        notifyAll();
        return new WorkerLease(id, cfg.leaseMs(), cfg.heartbeatMs());
    }

    @Override
    public synchronized boolean heartbeat(String workerId) {
        return leases.renew(workerId, clock.getAsLong());
    }

    @Override
    public synchronized void complete(String workerId, long activationId, int attempt, ActivationResult result) {
        long now = clock.getAsLong();
        Worker w = workers.get(workerId);
        InFlight f = inFlight.get(activationId);
        if (w != null && w.busyWith != null && w.busyWith == activationId) w.busyWith = null;
        if (f == null || !f.workerId().equals(workerId) || f.attempt() != attempt) {
            // The activation was already reassigned. Its writes stand (KS actions are idempotent).
            record(now, TraceEntry.Kind.LATE, null, workerId, activationId, attempt, null, 0, "result ignored");
            notifyAll();
            return;
        }
        inFlight.remove(activationId);
        busyContexts.remove(contextId(f.ksar()));
        lastActivityAt = now;
        String ks = f.ksar().ks();
        int[] c = perKs.computeIfAbsent(ks, k -> new int[4]);
        if (result.ok()) {
            long took = Math.max(0, now - f.startedAt());
            costMs.merge(ks, (double) took, (old, x) -> (1 - COST_EWMA) * old + COST_EWMA * Math.max(x, 0.5));
            c[1]++;
            c[3] += result.conflicts();
            record(now, TraceEntry.Kind.DONE, ks, workerId, activationId, attempt, f.ksar().contextKey(), 0,
                    String.format(java.util.Locale.ROOT, "+%d ~%d =%d cas-retry %d %dms %s", result.created(),
                            result.updated(), result.unchanged(), result.conflicts(), took,
                            result.note() == null ? "" : result.note()).stripTrailing());
        } else {
            c[2]++;
            record(now, TraceEntry.Kind.FAILED, ks, workerId, activationId, attempt, f.ksar().contextKey(), 0, result.note());
            retry(f.ksar(), now);
        }
        notifyAll();
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void closeInput() {
        inputClosed = true;
        notifyAll();
    }

    /** Waits until every named KS type has a live worker and at least {@code minWorkers} workers are live. */
    public synchronized boolean awaitWorkers(java.util.Collection<String> ksNames, int minWorkers, long timeoutMs)
            throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (workers.size() < minWorkers
                || !ksNames.stream().allMatch(n -> workers.values().stream().anyMatch(w -> w.spec.name().equals(n)))) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(Math.min(left, 50));
        }
        return true;
    }

    public synchronized void setDeadline(long deadlineMs) {
        this.deadlineAt = deadlineMs;
    }

    public synchronized void start() {
        running = true;
        loop = Thread.ofPlatform().name("bb-control").daemon().start(this::runLoop);
    }

    public void stop() {
        running = false;
        wake();
        if (loop != null) {
            try {
                loop.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Blocks until a termination condition holds or the timeout elapses (returns empty then). */
    public synchronized Optional<Termination> awaitTermination(long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (termination == null) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) break;
            wait(Math.min(left, 50));
        }
        return Optional.ofNullable(termination);
    }

    private void runLoop() {
        while (running) {
            boolean progressed = step();
            synchronized (this) {
                if (termination != null) {
                    running = false;
                    notifyAll();
                    break;
                }
                if (!progressed) {
                    try {
                        wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private synchronized void wake() {
        notifyAll();
    }

    /**
     * One control cycle. Public so tests can drive the controller deterministically with a
     * fake clock and in-process callbacks. Returns true if anything was dispatched or ingested.
     */
    public boolean step() {
        List<Dispatch> toSend;
        boolean ingested;
        synchronized (this) {
            if (termination != null) return false;
            long now = clock.getAsLong();
            ingested = ingest(now);
            expireLeases(now);
            reassignStuck(now);
            toSend = schedule(now);
            checkTermination(now);
        }
        for (Dispatch d : toSend) {
            try {
                d.worker().callback.dispatch(d.activation());
            } catch (RemoteException | RuntimeException e) {
                dispatchFailed(d, e);
            }
        }
        return ingested || !toSend.isEmpty();
    }

    // ---------------------------------------------------------------- control cycle internals

    private boolean ingest(long now) {
        boolean any = false;
        for (KsSpec spec : specs.values()) {
            long cursor = cursors.get(spec.name());
            while (true) {
                List<ChangeEvent> events = board.eventsSince(cursor, EVENT_BATCH);
                if (events.isEmpty()) break;
                for (ChangeEvent e : events) {
                    spec.match(e).ifPresent(p -> agenda.offer(spec.name(), p, e, now));
                    cursor = e.seq();
                }
                any = true;
            }
            cursors.put(spec.name(), cursor);
        }
        if (any) {
            lastActivityAt = now;
            // Focus of attention follows the most recent change: work triggered at the level
            // that just moved is boosted, which makes the search depth first along fresh results.
            List<ChangeEvent> last = board.eventsSince(board.head() - 1, 1);
            if (!last.isEmpty()) focus = last.get(0).level();
        }
        return any;
    }

    private void expireLeases(long now) {
        for (String id : leases.expire(now)) {
            Worker w = workers.remove(id);
            if (w == null) continue;
            record(now, TraceEntry.Kind.LEASE_EXPIRED, w.spec.name(), id, 0, 0, null, 0,
                    "no heartbeat for " + cfg.leaseMs() + " ms, worker removed");
            if (w.busyWith != null) {
                InFlight f = inFlight.remove(w.busyWith);
                if (f != null) {
                    busyContexts.remove(contextId(f.ksar()));
                    retry(f.ksar(), now);
                }
            }
        }
    }

    private void reassignStuck(long now) {
        List<Long> stuck = inFlight.entrySet().stream()
                .filter(e -> now - e.getValue().startedAt() > cfg.activationTimeoutMs())
                .map(Map.Entry::getKey).toList();
        for (Long id : stuck) {
            InFlight f = inFlight.remove(id);
            busyContexts.remove(contextId(f.ksar()));
            // The worker stays marked busy until its (late) completion arrives or its lease expires.
            record(now, TraceEntry.Kind.TIMED_OUT, f.ksar().ks(), f.workerId(), id, f.attempt(),
                    f.ksar().contextKey(), 0, "running > " + cfg.activationTimeoutMs() + " ms");
            retry(f.ksar(), now);
        }
    }

    private void retry(Ksar k, long now) {
        if (k.attempts() >= cfg.maxAttempts()) {
            record(now, TraceEntry.Kind.ABANDONED, k.ks(), null, k.id(), k.attempts(), k.contextKey(), 0,
                    "gave up after " + k.attempts() + " attempts");
            return;
        }
        agenda.requeue(k);
        record(now, TraceEntry.Kind.REQUEUED, k.ks(), null, k.id(), k.attempts(), k.contextKey(), 0,
                k.size() + " trigger(s) back on agenda");
    }

    private List<Dispatch> schedule(long now) {
        List<Dispatch> out = new ArrayList<>();
        while (dispatched < cfg.maxActivations()) {
            Optional<Agenda.Rated> best = agenda.pollBest(now, ks -> costMs.getOrDefault(ks, 1.0), focus,
                    k -> !busyContexts.contains(contextId(k)) && idleWorker(k.ks()).isPresent());
            if (best.isEmpty()) break;
            Ksar k = best.get().ksar();
            Worker w = idleWorker(k.ks()).orElseThrow();
            int attempt = k.incrementAttempts();
            Activation a = new Activation(k.id(), attempt, k.ks(), k.contextKey(), k.events(), best.get().rating());
            w.busyWith = k.id();
            w.served++;
            inFlight.put(k.id(), new InFlight(k, w.id, attempt, now, best.get().rating()));
            busyContexts.add(contextId(k));
            dispatched++;
            perKs.computeIfAbsent(k.ks(), x -> new int[4])[0]++;
            record(now, TraceEntry.Kind.DISPATCHED, k.ks(), w.id, k.id(), attempt, k.contextKey(),
                    best.get().rating(), k.size() + " trigger(s), focus " + focus);
            out.add(new Dispatch(w, a));
        }
        return out;
    }

    private Optional<Worker> idleWorker(String ks) {
        return workers.values().stream()
                .filter(w -> w.spec.name().equals(ks) && w.busyWith == null && leases.holds(w.id))
                .min(Comparator.comparingInt((Worker w) -> w.served).thenComparing(w -> w.id));
    }

    private synchronized void dispatchFailed(Dispatch d, Exception e) {
        long now = clock.getAsLong();
        Worker w = workers.remove(d.worker().id);
        leases.revoke(d.worker().id);
        InFlight f = inFlight.remove(d.activation().id());
        record(now, TraceEntry.Kind.FAILED, d.activation().ks(), d.worker().id, d.activation().id(),
                d.activation().attempt(), d.activation().contextKey(), 0,
                "callback unreachable (" + e.getClass().getSimpleName() + "), worker removed");
        if (f != null) {
            busyContexts.remove(contextId(f.ksar()));
            retry(f.ksar(), now);
        }
        if (w != null) notifyAll();
    }

    private void checkTermination(long now) {
        if (now >= deadlineAt) {
            terminate(now, Termination.DEADLINE);
        } else if (dispatched >= cfg.maxActivations() && inFlight.isEmpty()) {
            terminate(now, Termination.BUDGET);
        } else if (inputClosed && agenda.isEmpty() && inFlight.isEmpty()
                && specs.keySet().containsAll(cfg.requiredKs())
                && now - lastActivityAt >= cfg.quiescenceMs()) {
            terminate(now, Termination.QUIESCENT);
        }
    }

    private void terminate(long now, Termination t) {
        termination = t;
        record(now, TraceEntry.Kind.TERMINATED, null, null, 0, 0, null, 0,
                t + ", agenda " + agenda.size() + ", in flight " + inFlight.size() + ", journal " + board.head());
        notifyAll();
    }

    private static String contextId(Ksar k) {
        return k.ks() + "|" + k.contextKey();
    }

    private void record(long now, TraceEntry.Kind kind, String ks, String worker, long act, int attempt,
                        String ctx, double rating, String note) {
        trace.add(new TraceEntry(trace.size() + 1L, now - startedAt, kind, ks, worker, act, attempt, ctx, rating, note));
    }

    // ---------------------------------------------------------------- inspection

    public synchronized List<TraceEntry> trace() {
        return List.copyOf(trace);
    }

    public synchronized Termination termination() {
        return termination;
    }

    public synchronized Level focus() {
        return focus;
    }

    public synchronized int agendaSize() {
        return agenda.size();
    }

    public synchronized List<String> liveWorkers() {
        return List.copyOf(workers.keySet());
    }

    /** Per KS name: dispatched, done, failed, CAS retries, mean cost in ms. */
    public synchronized Map<String, String> ksSummary() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String ks : specs.keySet()) {
            int[] c = perKs.getOrDefault(ks, new int[4]);
            out.put(ks, String.format(java.util.Locale.ROOT,
                    "dispatched %4d  done %4d  failed %2d  cas-retries %3d  cost %.1f ms",
                    c[0], c[1], c[2], c[3], costMs.getOrDefault(ks, 0.0)));
        }
        return out;
    }
}
