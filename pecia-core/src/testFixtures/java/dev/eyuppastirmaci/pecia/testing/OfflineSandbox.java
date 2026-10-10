package dev.eyuppastirmaci.pecia.testing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Test-only isolation and evidence for packaged lexical processes running on JDK 21. */
public final class OfflineSandbox {

    private static final String STARTUP_WARNINGS = "WARNING: A command line option has enabled the Security Manager\n"
            + "WARNING: The Security Manager is deprecated and will be removed in a future release\n";
    private final Path directory;
    private final Path guardJar;
    private final OfflineEnvironment environment;

    private OfflineSandbox(Path directory, Path guardJar, OfflineEnvironment environment) {
        this.directory = directory;
        this.guardJar = guardJar;
        this.environment = environment;
    }

    /** Creates fresh test state in an existing directory, without using the host's home or cache. */
    public static OfflineSandbox create(Path directory) throws IOException {
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("Offline sandbox is not a directory: " + root);
        }
        Path guard = root.resolve("network-guard.jar");
        if (Files.exists(guard)) {
            throw new IOException("Offline guard already exists: " + guard);
        }
        OfflineEnvironment environment = OfflineEnvironment.create(root);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(guard))) {
            // Neither the host-side helpers nor JUnit are exposed to the application JVM.
            for (Class<?> type :
                    List.of(NetworkGuard.class, NetworkGuard.NetworkDeniedException.class, NetworkProbe.class)) {
                String resource = type.getName().replace('.', '/') + ".class";
                try (var input = type.getResourceAsStream("/" + resource)) {
                    if (input == null) {
                        throw new IOException("Missing offline guard class: " + resource);
                    }
                    jar.putNextEntry(new JarEntry(resource));
                    input.transferTo(jar);
                    jar.closeEntry();
                }
            }
        }
        return new OfflineSandbox(root, guard, environment);
    }

    public Path guardJar() {
        return guardJar;
    }

    /** Proves the guard rejects real network API calls in separate control JVMs. */
    public void verifyNetworkDenied() throws IOException, InterruptedException {
        for (String mode : List.of("connect", "dns", "listen")) {
            Run run = newRun();
            List<String> command = new ArrayList<>();
            Path javaBin = Path.of(System.getProperty("java.home"), "bin");
            command.add(javaBin.resolve(Files.isRegularFile(javaBin.resolve("java.exe")) ? "java.exe" : "java")
                    .toString());
            command.addAll(run.jvmArguments());
            command.addAll(List.of("-cp", guardJar.toString(), NetworkProbe.class.getName(), mode));
            Path stdout = run.directory.resolve("stdout.txt");
            Path stderr = run.directory.resolve("stderr.txt");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            run.configure(builder.environment());
            Process process = builder.start();
            try {
                process.getOutputStream().close();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Offline network control timed out: " + mode);
                }
                String output = Files.readString(stdout);
                String error = Files.readString(stderr);
                List<String> audit =
                        Files.isRegularFile(run.auditLog()) ? Files.readAllLines(run.auditLog()) : List.of();
                validateNetworkControl(mode, process.exitValue(), output, error, audit);
                OfflineEnvironment.assertLexicalInitialization(run.initializationLog(), NetworkProbe.class.getName());
                environment.assertUnchanged(run.before);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Could not terminate offline network control");
                    }
                }
            }
        }
    }

    static void validateNetworkControl(String mode, int exitCode, String stdout, String stderr, List<String> audit) {
        if (exitCode != 0 || !stdout.strip().equals("NETWORK_DENIED " + mode)) {
            throw new AssertionError("Network control did not prove guard denial: " + mode + "\n" + stdout + stderr);
        }
        if (!applicationStderr(stderr).isEmpty()) {
            throw new AssertionError("Unexpected network control stderr: " + stderr);
        }
        if (audit.size() < 2
                || !audit.getFirst().equals("READY")
                || audit.stream().skip(1).anyMatch(line -> !line.startsWith("DENY "))) {
            throw new AssertionError("Network control has no recorded guard denial: " + audit);
        }
    }

    /** Allocates separate evidence files and snapshots the isolated cache before one child process. */
    public Run newRun() throws IOException {
        return new Run(Files.createTempDirectory(directory, "run-"), environment.snapshot());
    }

    /**
     * Returns a JVM argument that records class initialization in {@code log}. JVM logging separates options with
     * ':', so the path is quoted to protect Windows drive letters. Windows process creation wraps arguments that
     * contain spaces in quotes without escaping embedded quotes, so they are escaped there.
     */
    public static String initializationLogArgument(Path log) {
        String quote = File.separatorChar == '\\' ? "\\\"" : "\"";

        return "-Xlog:class+init=info:file=" + quote + log.toString().replace('\\', '/') + quote;
    }

    /** Removes only the exact JDK 21 startup diagnostics; all application stderr remains visible. */
    public static String applicationStderr(String stderr) {
        String normalized = stderr.replace("\r\n", "\n");
        int startup = normalized.indexOf(STARTUP_WARNINGS);
        if (startup < 0) {
            throw new AssertionError("Missing expected JDK 21 SecurityManager startup diagnostics: " + normalized);
        }
        String application =
                normalized.substring(0, startup) + normalized.substring(startup + STARTUP_WARNINGS.length());
        if (application.contains("WARNING: A command line option has enabled the Security Manager")
                || application.contains(
                        "WARNING: The Security Manager is deprecated and will be removed in a future release")) {
            throw new AssertionError("Duplicate SecurityManager startup diagnostics: " + normalized);
        }
        return application;
    }

    /** One process's immutable starting snapshot and dedicated audit files. */
    public final class Run {

        private final Path directory;
        private final Map<Path, String> before;

        private Run(Path directory, Map<Path, String> before) {
            this.directory = Objects.requireNonNull(directory, "directory");
            this.before = Map.copyOf(before);
        }

        public Path auditLog() {
            return directory.resolve("network.log");
        }

        public Path initializationLog() {
            return directory.resolve("initialization.log");
        }

        /** Returns JVM arguments that isolate storage and record network and class-initialization evidence. */
        public List<String> jvmArguments() {
            List<String> arguments = new ArrayList<>(environment.jvmArguments());
            // Keep JVM monitoring files out of the directory audited for unexpected model writes.
            arguments.add("-XX:-UsePerfData");
            arguments.add("-Dfile.encoding=UTF-8");
            arguments.add("-Dstdout.encoding=UTF-8");
            arguments.add("-Dstderr.encoding=UTF-8");
            arguments.add("-Xbootclasspath/a:" + guardJar);
            arguments.add("-Djava.security.manager=" + NetworkGuard.class.getName());
            arguments.add("-Dpecia.offline.audit=" + auditLog());
            arguments.add(initializationLogArgument(initializationLog()));
            return List.copyOf(arguments);
        }

        /** Redirects the child environment to this sandbox's isolated storage. */
        public void configure(Map<String, String> variables) {
            environment.configure(variables);
        }

        /** Requires guard activation and no network attempt, including attempts swallowed by the application. */
        public void assertNoNetworkAttempts() throws IOException {
            if (!Files.isRegularFile(auditLog())) {
                throw new AssertionError("Missing lexical network audit: " + auditLog());
            }
            String audit = Files.readString(auditLog());
            if (!audit.equals("READY\n")) {
                throw new AssertionError("Lexical process attempted network access or has an invalid audit: " + audit);
            }
        }

        /** Validates network, cache and initialized-class evidence after the child has exited. */
        public void verifyLexical(String requiredClass) throws IOException {
            assertNoNetworkAttempts();
            environment.assertUnchanged(before);
            OfflineEnvironment.assertLexicalInitialization(initializationLog(), requiredClass);
            if (!OfflineEnvironment.initialized(initializationLog(), NetworkGuard.class.getName())) {
                throw new AssertionError("Network guard was not initialized in the lexical JVM");
            }
        }
    }
}
