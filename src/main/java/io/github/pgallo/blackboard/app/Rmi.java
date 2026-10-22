package io.github.pgallo.blackboard.app;

import java.io.IOException;
import java.net.ServerSocket;

final class Rmi {

    static final String BLACKBOARD = "blackboard";
    static final String CONTROL = "control";

    private Rmi() {
    }

    /** Pins RMI endpoints to loopback, so stubs never carry an unresolvable container hostname. */
    static void loopback() {
        if (System.getProperty("java.rmi.server.hostname") == null) {
            System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        }
        // Fail fast on dead peers instead of waiting for the OS TCP timeout.
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
        System.setProperty("sun.rmi.transport.connectionTimeout", "2000");
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
