package io.github.pgallo.blackboard.core;

import java.rmi.RemoteException;

/**
 * Read-modify-write loop over compare-and-set. The mutation is re-applied to the fresh
 * value after every conflict, so annotations written concurrently by other knowledge
 * sources are preserved instead of being overwritten (no lost updates).
 */
public final class Cas {

    private Cas() {
    }

    @FunctionalInterface
    public interface Mutation {
        /** Returns the derived hypothesis, or null to leave the current one untouched. */
        Hypothesis apply(Hypothesis current) throws RemoteException;
    }

    public record Outcome(WriteResult.Status status, Hypothesis current, int conflicts) {
        public boolean changed() {
            return status == WriteResult.Status.UPDATED;
        }
    }

    public static Outcome update(BlackboardService board, String id, String author, Mutation m)
            throws RemoteException {
        return update(board, id, author, m, 32);
    }

    public static Outcome update(BlackboardService board, String id, String author, Mutation m, int maxAttempts)
            throws RemoteException {
        int conflicts = 0;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Hypothesis current = board.get(id);
            if (current == null) return new Outcome(WriteResult.Status.NOT_FOUND, null, conflicts);
            Hypothesis next = m.apply(current);
            if (next == null || next.sameContent(current)) {
                return new Outcome(WriteResult.Status.UNCHANGED, current, conflicts);
            }
            WriteResult r = board.compareAndSet(next, author);
            if (r.status() != WriteResult.Status.CONFLICT) return new Outcome(r.status(), r.current(), conflicts);
            conflicts++;
            Thread.onSpinWait();
        }
        return new Outcome(WriteResult.Status.CONFLICT, board.get(id), conflicts);
    }
}
