package io.github.pgallo.blackboard.control;

import java.rmi.RemoteException;

/**
 * A knowledge source: a declared precondition plus an action over the blackboard.
 * Actions must be idempotent, because activations are delivered at least once
 * (an activation whose worker died is re-executed elsewhere).
 */
public interface KnowledgeSource {

    KsSpec spec();

    /** Runs the action and returns a short human-readable note for the trace. */
    String execute(Activation activation, KsContext ctx) throws RemoteException;
}
