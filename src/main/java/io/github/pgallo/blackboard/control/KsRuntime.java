package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.BlackboardService;

import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hosts one knowledge source as an RMI callback object: registers with the controller,
 * renews its lease, executes dispatched activations on a single worker thread and reports
 * results. The same class runs inside the test JVM (local mode) and in a worker process.
 *
 * <p>Fault injection: with {@code hangAfter = n} the worker accepts its n-th activation and
 * then goes silent (no result, no heartbeat), which is how a hung process or a network
 * partition looks from the controller's side.
 */
public final class KsRuntime implements KsWorker, AutoCloseable {

    /** Consecutive failed heartbeats after which the controller is considered gone. */
    static final int MAX_MISSED_HEARTBEATS = 5;

    private final KnowledgeSource ks;
    private final BlackboardService board;
    private final ControlService control;
    private final String preferredId;
    private final int hangAfter;
    private final Consumer<String> log;
    private final ExecutorService exec;
    private final ScheduledExecutorService ticker;
    private final AtomicInteger received = new AtomicInteger();
    private volatile WorkerLease lease;
    private volatile boolean hung;
    private volatile boolean closed;
    private volatile Runnable onHang = () -> { };
    private volatile Runnable onControllerLost = () -> { };
    private int missed;

    public KsRuntime(KnowledgeSource ks, BlackboardService board, ControlService control,
                     String preferredId, int hangAfter, Consumer<String> log) {
        this.ks = ks;
        this.board = board;
        this.control = control;
        this.preferredId = preferredId;
        this.hangAfter = hangAfter;
        this.log = log;
        String name = preferredId != null ? preferredId : ks.spec().name();
        this.exec = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ks-" + name).daemon().factory());
        this.ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("hb-" + name).daemon().factory());
    }

    public KsRuntime onHang(Runnable r) {
        this.onHang = r;
        return this;
    }

    public KsRuntime onControllerLost(Runnable r) {
        this.onControllerLost = r;
        return this;
    }

    public KsRuntime start() throws RemoteException {
        KsWorker stub = (KsWorker) UnicastRemoteObject.exportObject(this, 0);
        lease = control.register(ks.spec(), stub, preferredId);
        log.accept("registered " + lease.workerId() + " (lease " + lease.leaseMs() + " ms, heartbeat " + lease.heartbeatMs() + " ms)");
        ticker.scheduleAtFixedRate(this::heartbeat, lease.heartbeatMs(), lease.heartbeatMs(), TimeUnit.MILLISECONDS);
        return this;
    }

    public String workerId() {
        return lease == null ? null : lease.workerId();
    }

    @Override
    public void dispatch(Activation a) {
        if (hung || closed) return;
        if (hangAfter > 0 && received.incrementAndGet() >= hangAfter) {
            hung = true;
            log.accept("FAULT INJECTION: accepted activation #" + a.id() + " and going silent");
            onHang.run();
            return;
        }
        exec.execute(() -> run(a));
    }

    private void run(Activation a) {
        if (hung || closed) return;
        KsContext ctx = new KsContext(board, ks.spec().name());
        ActivationResult result;
        try {
            result = ctx.result(ks.execute(a, ctx));
        } catch (Exception | AssertionError e) {
            result = ActivationResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (hung || closed) return;
        try {
            control.complete(lease.workerId(), a.id(), a.attempt(), result);
        } catch (RemoteException e) {
            log.accept("could not report activation #" + a.id() + ": " + e.getMessage());
        }
    }

    private void heartbeat() {
        if (hung || closed) return;
        try {
            if (!control.heartbeat(lease.workerId())) {
                // Our lease expired (long pause, partition). Any in-flight work was reassigned,
                // so rejoin as a fresh worker rather than resurrect the old identity.
                lease = control.register(ks.spec(), (KsWorker) UnicastRemoteObject.toStub(this), null);
                log.accept("lease lost, re-registered as " + lease.workerId());
            }
            missed = 0;
        } catch (RemoteException e) {
            if (++missed >= MAX_MISSED_HEARTBEATS) {
                log.accept("controller unreachable after " + missed + " heartbeats");
                closed = true;
                onControllerLost.run();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        ticker.shutdownNow();
        exec.shutdownNow();
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (NoSuchObjectException ignored) {
            // never exported or already gone
        }
    }
}
