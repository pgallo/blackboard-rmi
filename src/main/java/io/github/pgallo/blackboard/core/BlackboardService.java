package io.github.pgallo.blackboard.core;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Collection;
import java.util.List;

/**
 * Remote contract of the shared blackboard. All writes are either create-if-absent
 * (natural keys make re-executed activations idempotent) or compare-and-set on the
 * hypothesis version (optimistic concurrency between knowledge sources in different JVMs).
 */
public interface BlackboardService extends Remote {

    Hypothesis get(String id) throws RemoteException;

    /** Batch read used to follow support links without one round trip per hypothesis. */
    List<Hypothesis> getAll(Collection<String> ids) throws RemoteException;

    List<Hypothesis> query(Level level) throws RemoteException;

    /** Server-side filter on a tag, to keep the payload small (e.g. tracks of one sensor). */
    List<Hypothesis> query(Level level, String tagKey, String tagValue) throws RemoteException;

    /** Stores {@code h} with version 1 if no hypothesis with that id exists. */
    WriteResult create(Hypothesis h, String author) throws RemoteException;

    /**
     * Replaces the stored hypothesis iff its version still equals {@code proposed.version()},
     * i.e. the version the writer read before deriving {@code proposed}.
     */
    WriteResult compareAndSet(Hypothesis proposed, String author) throws RemoteException;

    /** Journal entries with {@code seq > afterSeq}, oldest first, at most {@code max}. */
    List<ChangeEvent> eventsSince(long afterSeq, int max) throws RemoteException;

    BoardStats stats() throws RemoteException;
}
