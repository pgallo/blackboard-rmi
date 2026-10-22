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

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Command line entry point.
 * <pre>
 *   --local    [--seed N] [--fault] [--pace MS]           everything in one JVM (in-process registry)
 *   registry   [--port P]                                  RMI registry process
 *   server     [--port P] [--seed N] [--duration S] [--expect-workers N]   blackboard + controller + feed
 *   worker     --ks NAME [--port P] [--id ID] [--crash-after N]   one knowledge source
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        Map<String, String> o = options(args);
        int code = switch (args[0]) {
            case "--local", "local" -> local(o);
            case "registry" -> registry(o);
            case "server" -> server(o);
            case "worker" -> worker(o);
            case "scenario" -> scenario(o);
            default -> {
                usage();
                yield 1;
            }
        };
        System.exit(code);
    }

    private static int local(Map<String, String> o) throws Exception {
        long seed = Long.parseLong(o.getOrDefault("seed", "7"));
        LocalRun.Options opt = LocalRun.Options.defaults(seed)
                .withPace(Long.parseLong(o.getOrDefault("pace", "0")))
                .withFault(o.containsKey("fault") ? 3 : 0)
                .verbose(o.containsKey("verbose"));
        System.out.println("local mode: in-process RMI registry, 5 knowledge sources"
                + (opt.extraCorrelatorHangAfter() > 0 ? " + a second Correlator that hangs on its 3rd activation" : ""));
        LocalRun.Result r = LocalRun.run(opt);
        Report.print(System.out, r.board(), r.controller(), r.scenario(), 40);
        return Truth.expectedSituations(r.scenario()).equals(Truth.activeSituations(r.board(), r.scenario(), 0.5)) ? 0 : 2;
    }

    private static int registry(Map<String, String> o) throws Exception {
        Rmi.loopback();
        int port = Integer.parseInt(o.getOrDefault("port", "1099"));
        Registry registry = LocateRegistry.createRegistry(port);
        System.out.println("[registry] listening on 127.0.0.1:" + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (Exception ignored) {
                // shutting down
            }
        }));
        new CountDownLatch(1).await();
        return 0;
    }

    private static int server(Map<String, String> o) throws Exception {
        Rmi.loopback();
        int port = Integer.parseInt(o.getOrDefault("port", "1099"));
        long seed = Long.parseLong(o.getOrDefault("seed", "7"));
        double duration = Double.parseDouble(o.getOrDefault("duration", "10"));
        Scenario scenario = ScenarioGenerator.generate(seed);

        BlackboardStore store = new BlackboardStore();
        Controller controller = new Controller(store, ControllerConfig.defaults(KnowledgeSources.required()));
        Registry registry = lookupRegistry(port);
        registry.rebind(Rmi.BLACKBOARD, UnicastRemoteObject.exportObject(store, 0));
        registry.rebind(Rmi.CONTROL, UnicastRemoteObject.exportObject(controller, 0));
        controller.start();
        System.out.printf("[server] blackboard and controller bound; scenario seed %d: %d detections, %d sensors%n",
                seed, scenario.detections().size(), scenario.sensors().size());

        int expect = Integer.parseInt(o.getOrDefault("expect-workers", "0"));
        if (!controller.awaitWorkers(KnowledgeSources.NAMES, expect, 20_000)) {
            System.out.println("[server] not every knowledge source registered in time: " + controller.liveWorkers());
        }
        System.out.println("[server] workers: " + controller.liveWorkers());
        long scanSteps = scenario.detections().stream().mapToDouble(d -> d.t()).distinct().count();
        long pace = Math.max(1, (long) (duration * 1000 * 0.65 / scanSteps));
        long started = System.currentTimeMillis();
        controller.setDeadline(started + (long) (duration * 1000) + 10_000);
        System.out.printf("[server] feeding %d scan steps at one per %d ms%n", scanSteps, pace);
        Feeder.feed(scenario, store, pace, n -> { });
        controller.closeInput();
        System.out.printf("[server] feed complete after %.1f s, waiting for quiescence%n",
                (System.currentTimeMillis() - started) / 1000.0);
        controller.awaitTermination((long) (duration * 1000) + 15_000);
        System.out.printf("[server] terminated after %.1f s%n", (System.currentTimeMillis() - started) / 1000.0);
        controller.stop();

        Report.print(System.out, store, controller, scenario, 40);
        try {
            registry.unbind(Rmi.CONTROL);
            registry.unbind(Rmi.BLACKBOARD);
        } catch (NotBoundException | RemoteException ignored) {
            // registry may already be gone
        }
        UnicastRemoteObject.unexportObject(controller, true);
        UnicastRemoteObject.unexportObject(store, true);
        return Truth.expectedSituations(scenario).equals(Truth.activeSituations(store, scenario, 0.5)) ? 0 : 2;
    }

    private static int worker(Map<String, String> o) throws Exception {
        Rmi.loopback();
        String ks = o.get("ks");
        if (ks == null) throw new IllegalArgumentException("worker needs --ks <" + String.join("|", KnowledgeSources.NAMES) + ">");
        int port = Integer.parseInt(o.getOrDefault("port", "1099"));
        int crashAfter = Integer.parseInt(o.getOrDefault("crash-after", "0"));
        String id = o.get("id");
        String tag = "[" + (id != null ? id : ks) + "] ";

        Registry registry = lookupRegistry(port);
        BlackboardService board = (BlackboardService) awaitBinding(registry, Rmi.BLACKBOARD);
        ControlService control = (ControlService) awaitBinding(registry, Rmi.CONTROL);
        CountDownLatch done = new CountDownLatch(1);
        KsRuntime rt = new KsRuntime(KnowledgeSources.create(ks), board, control, id, crashAfter,
                s -> System.out.println(tag + s))
                .onControllerLost(done::countDown)
                .onHang(() -> Thread.ofPlatform().daemon().start(() -> {
                    // Let the dispatch call return first, so the controller believes the work is in progress.
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println(tag + "simulated crash: halting JVM with work in flight");
                    System.out.flush();
                    Runtime.getRuntime().halt(137);
                }))
                .start();
        done.await();
        rt.close();
        System.out.println(tag + "controller gone, exiting");
        return 0;
    }

    private static int scenario(Map<String, String> o) {
        Scenario s = ScenarioGenerator.generate(Long.parseLong(o.getOrDefault("seed", "7")));
        System.out.println("targets:");
        s.targets().forEach(t -> System.out.println("  " + t));
        long clutter = s.detections().stream().filter(d -> d.label().equals("clutter")).count();
        System.out.printf("detections: %d (clutter %d)%n", s.detections().size(), clutter);
        System.out.println("expected situations: " + Truth.expectedSituations(s));
        return 0;
    }

    private static Registry lookupRegistry(int port) throws InterruptedException {
        RemoteException last = null;
        for (int i = 0; i < 100; i++) {
            try {
                Registry r = LocateRegistry.getRegistry("127.0.0.1", port);
                r.list();
                return r;
            } catch (RemoteException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("no RMI registry on port " + port, last);
    }

    private static Object awaitBinding(Registry r, String name) throws Exception {
        for (int i = 0; i < 150; i++) {
            try {
                return r.lookup(name);
            } catch (NotBoundException e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("'" + name + "' never bound in the registry");
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> m = new HashMap<>();
        List<String> flags = List.of("fault", "verbose");
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + args[i]);
            String k = args[i].substring(2);
            if (flags.contains(k)) m.put(k, "true");
            else if (i + 1 < args.length) m.put(k, args[++i]);
            else throw new IllegalArgumentException("missing value for --" + k);
        }
        return m;
    }

    private static void usage() {
        System.err.println("""
                usage:
                  --local    [--seed N] [--fault] [--pace MS] [--verbose]
                  registry   [--port P]
                  server     [--port P] [--seed N] [--duration S] [--expect-workers N]
                  worker     --ks NAME [--port P] [--id ID] [--crash-after N]
                  scenario   [--seed N]
                knowledge sources:""" + " " + String.join(", ", KnowledgeSources.NAMES));
    }
}
