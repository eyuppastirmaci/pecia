package dev.eyuppastirmaci.pecia.testing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Exercises the real JVM networking APIs to prove the child process's guard is enforcing its policy. */
// Reads the SecurityManager, deprecated for removal, to confirm that NetworkGuard is installed.
@SuppressWarnings("removal")
public final class NetworkProbe {

    private NetworkProbe() {}

    /** Runs a connect, DNS, or listen probe and succeeds only when {@link NetworkGuard} denies it. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one probe mode: connect, dns, or listen");
        }
        if (!(System.getSecurityManager() instanceof NetworkGuard)) {
            throw new IllegalStateException("The acceptance-test network guard is not installed");
        }

        String mode = arguments[0];
        try {
            switch (mode) {
                case "connect" -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", 9), 1_000);
                    }
                }
                case "dns" -> InetAddress.getByName("pecia-offline-probe.invalid");
                case "listen" -> {
                    try (ServerSocket socket = new ServerSocket(0)) {
                        throw new IllegalStateException(
                                "The network guard permitted listening on " + socket.getLocalPort());
                    }
                }
                default -> throw new IllegalArgumentException("Unknown network probe mode: " + mode);
            }
        } catch (NetworkGuard.NetworkDeniedException denied) {
            if (!(System.getSecurityManager() instanceof NetworkGuard)) {
                throw new IllegalStateException("The acceptance-test network guard was replaced", denied);
            }
            System.out.println("NETWORK_DENIED " + mode);
            return;
        }
        throw new IllegalStateException("The network guard permitted " + mode);
    }
}
