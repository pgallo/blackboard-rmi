package io.github.pgallo.blackboard.control;

import java.rmi.Remote;
import java.rmi.RemoteException;

/** Remote face of the control component, called by knowledge-source workers. */
public interface ControlService extends Remote {

    WorkerLease register(KsSpec spec, KsWorker callback, String preferredId) throws RemoteException;

    /** Renews the lease. Returns false if the lease is unknown (expired), in which case the worker re-registers. */
    boolean heartbeat(String workerId) throws RemoteException;

    void complete(String workerId, long activationId, int attempt, ActivationResult result) throws RemoteException;
}
