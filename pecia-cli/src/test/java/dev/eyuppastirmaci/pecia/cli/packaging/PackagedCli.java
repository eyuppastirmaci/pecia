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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the packaged CLI JAR against a temporary project and observes the project's entries and bytes. */
final class PackagedCli {

    private final Path sandbox;
    private final Path project;
    private final Path executableJar;
    private OfflineSandbox offline;

    private PackagedCli(Path sandbox, Path project, Path executableJar) {
        this.sandbox = sandbox;
        this.project = project;
        this.executableJar = executableJar;
    }

    /** Locates the Failsafe-supplied JAR and creates an empty project whose path contains spaces. */
    static PackagedCli create(Path sandbox) throws IOException {
        String jarProperty = System.getProperty("pecia.it.jar");
        assertNotNull(jarProperty, "Failsafe must supply the packaged CLI JAR path");
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
        OfflineSandbox.Run evidence = offline == null ? null : offline.newRun();
        if (evidence != null) {
            command.addAll(evidence.jvmArguments());
        }
        command.addAll(List.of("-jar", executableJar.toString()));
        command.addAll(List.of(arguments));

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
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Could not terminate the CLI process");
            }

            assertTrue(completed, () -> "CLI timed out: " + command);

            String error = Files.readString(stderr).replace("\r\n", "\n");
            if (evidence != null) {
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

    Result runReadOnly(Path directory, String... arguments) throws Exception {
        List<Path> entries = projectEntries();
        Map<Path, ContentHash> contents = projectContents();
        Result result = run(directory, arguments);
        assertSuccess(result);
        assertEquals(entries, projectEntries(), "Query/preview must not create or delete project entries");
        assertEquals(contents, projectContents(), "Query/preview must preserve source and index bytes");
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
}
