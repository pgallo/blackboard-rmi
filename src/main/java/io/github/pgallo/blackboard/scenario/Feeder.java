package io.github.pgallo.blackboard.scenario;

import io.github.pgallo.blackboard.core.BlackboardService;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * Sensor ingest: posts detections to the blackboard one scan at a time, in time order,
 * sleeping {@code paceMs} between scans to emulate a live feed.
 */
public final class Feeder {

    public static final String AUTHOR = "ingest";

    private Feeder() {
    }

    /** Returns the number of detections posted. {@code onScan} receives the running count after each scan. */
    public static int feed(Scenario scenario, BlackboardService board, long paceMs, IntConsumer onScan)
            throws RemoteException, InterruptedException {
        Map<Double, List<Detection>> byTime = new TreeMap<>();
        for (Detection d : scenario.detections()) {
            byTime.computeIfAbsent(d.t(), k -> new java.util.ArrayList<>()).add(d);
        }
        int posted = 0;
        for (List<Detection> scan : byTime.values()) {
            for (Detection d : scan) {
                board.create(d.toHypothesis(), AUTHOR);
                posted++;
            }
            onScan.accept(posted);
            if (paceMs > 0) Thread.sleep(paceMs);
        }
        return posted;
    }
}
