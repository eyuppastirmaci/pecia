package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.Bootstrap;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.testing.OfflineEnvironment;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the packaged CLI JAR against a temporary project and observes the project's entries and bytes. */
final class PackagedCli {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Path sandbox;
    private final Path project;
    private final Path executableJar;
    private OfflineSandbox offline;

    private PackagedCli(Path sandbox, Path project, Path executableJar) {
        this.sandbox = sandbox;
        this.project = project;
        this.executableJar = executableJar;
    }

    /** Locates the build-supplied JAR and creates an empty project whose path contains spaces. */
    static PackagedCli create(Path sandbox) throws IOException {
        String jarProperty = System.getProperty("pecia.it.jar");
        assertNotNull(jarProperty, "The build must supply the packaged CLI JAR path");
        Path executableJar = Path.of(jarProperty);
        assertTrue(executableJar.isAbsolute(), "The CLI JAR path must be absolute");
        assertTrue(Files.isRegularFile(executableJar), "Missing packaged CLI JAR: " + executableJar);
        Path project =
                Files.createDirectory(sandbox.resolve("project with spaces")).toRealPath();

        return new PackagedCli(sandbox, project, executableJar);
    }

    Path project() {
        return project;
    }

    Path executableJar() {
        return executableJar;
    }

    /** Runs later processes under the network guard and verifies that the guard denies network access. */
    void enableOffline() throws Exception {
        offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();
    }

    /* Runs the distribution in an isolated directory and redirects both streams to avoid pipe deadlocks. */
    Result run(Path directory, String... arguments) throws Exception {
        return run(directory, List.of(), arguments);
    }

    Result run(Path directory, List<String> jvmArguments, String... arguments) throws Exception {
        return run(directory, jvmArguments, null, DEFAULT_TIMEOUT, arguments);
    }

    /** Runs like {@link #run(Path, String...)} with a longer limit, for commands that index large projects. */
    Result runWithin(Duration timeout, Path directory, String... arguments) throws Exception {
        return run(directory, List.of(), null, timeout, arguments);
    }

    /**
     * Runs a command offline that is expected to try one model download and be denied, verified by {@link
     * OfflineSandbox.Run#verifyDeniedDownload} instead of the lexical checks.
     */
    Result runDeniedDownload(Path directory, String host, Path lockFile, String... arguments) throws Exception {
        if (offline == null) {
            throw new IllegalStateException("A denied download needs the offline guard");
        }

        return run(
                directory,
                List.of(),
                evidence -> evidence.verifyDeniedDownload(host, lockFile),
                DEFAULT_TIMEOUT,
                arguments);
    }

    /**
     * Starts the distribution without the offline guard and returns at once, for tests that act on the process while
     * it runs; both streams go to files.
     */
    Started start(Path directory, String... arguments) throws IOException {
        List<String> command = command(List.of(), null, arguments);
        Path output = Files.createTempDirectory(sandbox, "process-");
        Path stdout = output.resolve("stdout.txt");
        Path stderr = output.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        Process process = builder.start();
        process.getOutputStream().close();

        return new Started(process, stdout, stderr);
    }

    /** Builds the command that runs the distribution with the shared JVM options. */
    private List<String> command(List<String> jvmArguments, OfflineSandbox.Run evidence, String... arguments)
            throws IOException {
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.isRegularFile(javaBin.resolve("java.exe"))
                ? javaBin.resolve("java.exe")
                : javaBin.resolve("java");
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Djava.io.tmpdir="
                        + Path.of(System.getProperty("java.io.tmpdir")).toRealPath(),
                "-Dpicocli.ansi=false",
                "-Dpicocli.usage.width=80"));

        command.addAll(jvmArguments);
        if (evidence != null) {
            command.addAll(evidence.jvmArguments());
        }
        command.addAll(List.of("-jar", executableJar.toString()));
        command.addAll(List.of(arguments));

        return command;
    }

    /** Runs the distribution; {@code check} replaces the lexical checks of offline runs when it is not null. */
    private Result run(
            Path directory, List<String> jvmArguments, EvidenceCheck check, Duration timeout, String... arguments)
            throws Exception {
        OfflineSandbox.Run evidence = offline == null ? null : offline.newRun();
        List<String> command = command(jvmArguments, evidence, arguments);
        Path output = Files.createTempDirectory(sandbox, "process-");
        Path stdout = output.resolve("stdout.txt");
        Path stderr = output.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        if (evidence != null) {
            evidence.configure(builder.environment());
        }
        Process process = builder.start();

        try {
            process.getOutputStream().close();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (!completed) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not terminate the CLI process");
            }

            assertTrue(completed, () -> "CLI timed out: " + command);

            String error = Files.readString(stderr).replace("\r\n", "\n");
            if (evidence != null && check != null) {
                check.verify(evidence);
                error = OfflineSandbox.applicationStderr(error);
            } else if (evidence != null) {
                evidence.verifyLexical(Bootstrap.class.getName());
                error = OfflineSandbox.applicationStderr(error);
                List<String> options = List.of(arguments);
                if (options.contains("query") || options.contains("--dry-run") || options.contains("--help")) {
                    assertFalse(
                            OfflineEnvironment.initialized(
                                    evidence.initializationLog(), MiniLmTokenizer.class.getName()),
                            "Query, preview and help must not initialize the tokenizer");
                }
            }
            return new Result(process.exitValue(), Files.readString(stdout).replace("\r\n", "\n"), error);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Runs a command that must succeed without changing the project: no entry may be created or deleted, and every
     * file, including the index and any journal or write-ahead-log sidecar, must keep its bytes.
     */
    Result runReadOnly(Path directory, String... arguments) throws Exception {
        List<Path> entries = projectEntries();
        Map<Path, ContentHash> contents = projectContents();
        Result result = run(directory, arguments);
        assertSuccess(result);
        assertEquals(entries, projectEntries(), "A read-only run must not create or delete project entries");
        assertEquals(contents, projectContents(), "A read-only run must preserve source and index bytes");
        return result;
    }

    List<Path> projectEntries() throws IOException {
        try (var paths = Files.walk(project)) {
            return paths.filter(path -> !path.equals(project))
                    .map(project::relativize)
                    .sorted()
                    .toList();
        }
    }

    private Map<Path, ContentHash> projectContents() throws IOException {
        Map<Path, ContentHash> contents = new HashMap<>();
        try (var paths = Files.walk(project)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                contents.put(project.relativize(path), ContentHash.sha256(Files.readAllBytes(path)));
            }
        }
        return Map.copyOf(contents);
    }

    /** A completed CLI process with CRLF-normalized output streams. */
    record Result(int exitCode, String out, String err) {}

    /** A running CLI process and the files that receive its output. */
    record Started(Process process, Path stdout, Path stderr) {}

    @FunctionalInterface
    private interface EvidenceCheck {

        void verify(OfflineSandbox.Run evidence) throws IOException;
    }
}
