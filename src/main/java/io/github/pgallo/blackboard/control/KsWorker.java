package io.github.pgallo.blackboard.control;

import java.rmi.Remote;
import java.rmi.RemoteException;

/** Callback exported by each knowledge-source worker. */
public interface KsWorker extends Remote {

    /** Accepts an activation and returns immediately; the result arrives later via {@link ControlService#complete}. */
    void dispatch(Activation activation) throws RemoteException;
}
