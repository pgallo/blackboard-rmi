package io.github.pgallo.blackboard.control;

import io.github.pgallo.blackboard.core.BlackboardService;
import io.github.pgallo.blackboard.core.Cas;
import io.github.pgallo.blackboard.core.Hypothesis;
import io.github.pgallo.blackboard.core.Level;
import io.github.pgallo.blackboard.core.WriteResult;

import java.rmi.RemoteException;
import java.util.Collection;
import java.util.List;

/** Blackboard access for one activation: stamps the author and counts write outcomes for the trace. */
public final class KsContext {

    private final BlackboardService board;
    private final String author;
    private int created;
    private int updated;
    private int unchanged;
    private int conflicts;

    public KsContext(BlackboardService board, String author) {
        this.board = board;
        this.author = author;
    }

    public Hypothesis get(String id) throws RemoteException {
        return board.get(id);
    }

    public List<Hypothesis> getAll(Collection<String> ids) throws RemoteException {
        return board.getAll(ids);
    }

    public List<Hypothesis> query(Level level) throws RemoteException {
        return board.query(level);
    }

    public List<Hypothesis> query(Level level, String tagKey, String tagValue) throws RemoteException {
        return board.query(level, tagKey, tagValue);
    }

    public WriteResult create(Hypothesis h) throws RemoteException {
        WriteResult r = board.create(h, author);
        if (r.status() == WriteResult.Status.CREATED) created++;
        else unchanged++;
        return r;
    }

    public Cas.Outcome update(String id, Cas.Mutation m) throws RemoteException {
        Cas.Outcome o = Cas.update(board, id, author, m);
        conflicts += o.conflicts();
        if (o.changed()) updated++;
        else unchanged++;
        return o;
    }

    public ActivationResult result(String note) {
        return new ActivationResult(true, created, updated, unchanged, conflicts, note);
    }
}
