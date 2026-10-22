package io.github.pgallo.blackboard.app;

import io.github.pgallo.blackboard.control.ControlService;
import io.github.pgallo.blackboard.control.Controller;
import io.github.pgallo.blackboard.control.ControllerConfig;
import io.github.pgallo.blackboard.control.KsRuntime;
import io.github.pgallo.blackboard.core.BlackboardService;
import io.github.pgallo.blackboard.core.BlackboardStore;
import io.github.pgallo.blackboard.ks.KnowledgeSources;
import io.github.pgallo.blackboard.scenario.Feeder;
import io.github.pgallo.blackboard.scenario.Scenario;
import io.github.pgallo.blackboard.scenario.ScenarioGenerator;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything in one JVM, but still through RMI: an in-process registry, the blackboard and
 * controller exported as remote objects, and every knowledge source looking them up and
 * exporting its own callback. Only process boundaries are missing compared with the
 * distributed mode, which is what makes this mode useful for tests.
 */
public final class LocalRun {

    public record Options(long seed, long paceMs, int extraCorrelatorHangAfter, long timeoutMs, boolean verbose) {
        public static Options defaults(long seed) {
            return new Options(seed, 0, 0, 60_000, false);
        }

        public Options withFault(int hangAfter) {
            return new Options(seed, paceMs, hangAfter, timeoutMs, verbose);
        }

        public Options withPace(long ms) {
            return new Options(seed, ms, extraCorrelatorHangAfter, timeoutMs, verbose);
        }

        public Options verbose(boolean v) {
            return new Options(seed, paceMs, extraCorrelatorHangAfter, timeoutMs, v);
        }
    }

    public record Result(Scenario scenario, BlackboardStore board, Controller controller, Controller.Termination termination) {
    }

    private LocalRun() {
    }

    public static Result run(Options opt) throws Exception {
        Rmi.loopback();
        Scenario scenario = ScenarioGenerator.generate(opt.seed());
        BlackboardStore store = new BlackboardStore();
        ControllerConfig cfg = ControllerConfig.defaults(KnowledgeSources.required()).withLease(600, 150);
        Controller controller = new Controller(store, cfg);
        Consumer<String> log = opt.verbose() ? s -> System.out.println("[worker] " + s) : s -> { };

        int port = Rmi.freePort();
        Registry registry = LocateRegistry.createRegistry(port);
        List<KsRuntime> runtimes = new ArrayList<>();
        try {
            registry.rebind(Rmi.BLACKBOARD, UnicastRemoteObject.exportObject(store, 0));
            registry.rebind(Rmi.CONTROL, UnicastRemoteObject.exportObject(controller, 0));
            controller.start();

            Registry client = LocateRegistry.getRegistry("127.0.0.1", port);
            BlackboardService board = (BlackboardService) client.lookup(Rmi.BLACKBOARD);
            ControlService control = (ControlService) client.lookup(Rmi.CONTROL);
            for (String name : KnowledgeSources.NAMES) {
                runtimes.add(new KsRuntime(KnowledgeSources.create(name), board, control, name + "-1", 0, log).start());
            }
            if (opt.extraCorrelatorHangAfter() > 0) {
                runtimes.add(new KsRuntime(KnowledgeSources.create("Correlator"), board, control, "Correlator-2",
                        opt.extraCorrelatorHangAfter(), log).start());
            }
            Feeder.feed(scenario, board, opt.paceMs(), n -> { });
            controller.closeInput();
            Controller.Termination t = controller.awaitTermination(opt.timeoutMs()).orElse(null);
            return new Result(scenario, store, controller, t);
        } finally {
            controller.stop();
            runtimes.forEach(KsRuntime::close);
            UnicastRemoteObject.unexportObject(controller, true);
            UnicastRemoteObject.unexportObject(store, true);
            UnicastRemoteObject.unexportObject(registry, true);
        }
    }
}
