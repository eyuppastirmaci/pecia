package dev.eyuppastirmaci.pecia.testing;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketPermission;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Permission;
import java.util.Objects;

/** Denies and records network operations in a dedicated JDK 21 acceptance-test process. */
// SecurityManager is deprecated for removal; JDK 21 still installs it, which the build's JDK range pins.
@SuppressWarnings("removal")
public final class NetworkGuard extends SecurityManager {

    private final Path audit;

    /** Creates a new audit file at the absolute path supplied by {@code pecia.offline.audit}. */
    public NetworkGuard() {
        audit = Path.of(Objects.requireNonNull(System.getProperty("pecia.offline.audit"), "pecia.offline.audit"));
        if (!audit.isAbsolute()) {
            throw new IllegalArgumentException("pecia.offline.audit must be absolute");
        }
        write("READY", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @Override
    public void checkConnect(String host, int port) {
        deny("CONNECT " + host + ":" + port);
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        checkConnect(host, port);
    }

    @Override
    public void checkListen(int port) {
        deny("LISTEN " + port);
    }

    @Override
    public void checkAccept(String host, int port) {
        deny("ACCEPT " + host + ":" + port);
    }

    @Override
    public void checkMulticast(InetAddress address) {
        deny("MULTICAST " + address.getHostAddress());
    }

    @Override
    // The deprecated overload must still be denied because callers can reach it on JDK 21.
    @SuppressWarnings("deprecation")
    public void checkMulticast(InetAddress address, byte ttl) {
        checkMulticast(address);
    }

    @Override
    public void checkPermission(Permission permission) {
        if (permission instanceof SocketPermission socket) {
            deny("SOCKET " + socket.getName() + " " + socket.getActions());
        }
        if (permission instanceof RuntimePermission && permission.getName().equals("setSecurityManager")) {
            throw new SecurityException("The acceptance-test network guard cannot be replaced");
        }
    }

    @Override
    public void checkPermission(Permission permission, Object context) {
        checkPermission(permission);
    }

    private void deny(String operation) {
        String event = operation.replace('\r', '_').replace('\n', '_');
        write("DENY " + event, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        throw new NetworkDeniedException(event);
    }

    private synchronized void write(String event, StandardOpenOption... options) {
        try {
            Files.writeString(audit, event + "\n", StandardCharsets.UTF_8, options);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot record the acceptance-test network audit: " + audit, e);
        }
    }

    /** Distinguishes enforcement by this guard from ordinary connection failures or other restrictions. */
    public static final class NetworkDeniedException extends SecurityException {

        private NetworkDeniedException(String operation) {
            super("PECIA_OFFLINE_NETWORK_DENIED: " + operation);
        }
    }
}
