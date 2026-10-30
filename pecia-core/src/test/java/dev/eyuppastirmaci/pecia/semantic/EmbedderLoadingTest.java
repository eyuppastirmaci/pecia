package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.testing.OfflineEnvironment;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import dev.eyuppastirmaci.pecia.testing.TemporaryDirectories;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks in fresh JVMs when ONNX Runtime initializes and what it leaves behind, which a test JVM that already ran an
 * embedder cannot show.
 */
class EmbedderLoadingTest {

    private static final String RUNTIME = "ai.onnxruntime.OrtEnvironment";
    // Loading the runtime takes a few seconds; slow CI runners take longer.
    private static final Duration PROBE_LIMIT = Duration.ofSeconds(60);

    @TempDir
    Path sandbox;

    /** Probes leave ONNX Runtime's native libraries in the sandbox, which Windows can hold for a moment after they exit. */
    @AfterEach
    void emptyTheSandboxOnceTheProbesReleaseIt() throws Exception {
        TemporaryDirectories.deleteContentsOnceReleased(sandbox);
    }

    @Test
    void nothingBeforeOpeningAnEmbedderInitializesTheRuntime() throws Exception {
        Path log = sandbox.resolve("initialize.log");

        assertEquals("RUNTIME_UNAVAILABLE", probe(log, List.of(), "initialize"));

        assertTrue(OfflineEnvironment.initialized(log, Embedder.class.getName()));
        assertFalse(OfflineEnvironment.initialized(log, RUNTIME));
        assertFalse(Files.readString(log).contains("Initializing 'ai/onnxruntime/"), "No runtime class initialized");
    }

    @Test
    void openingAnEmbedderInitializesTheRuntime() throws Exception {
        Path log = sandbox.resolve("embed.log");

        assertEquals("EMBEDDED 1", probe(log, List.of(), "embed"));

        assertTrue(OfflineEnvironment.initialized(log, RUNTIME));
    }

    @Test
    void embeddingWritesNothingButTheRuntimesLibrariesInTheCache() throws Exception {
        // Telemetry builds of the runtime keep a device id and an event store under the user's home directory.
        OfflineEnvironment isolated = OfflineEnvironment.create(Files.createDirectory(sandbox.resolve("isolated")));
        Map<Path, String> before = isolated.snapshot();

        assertEquals("EMBEDDED 1", probe(sandbox.resolve("isolated.log"), isolated, "embed"));

        isolated.assertOnlyRuntimeLibraries(before);
    }

    @Test
    void laterProcessesLoadTheCachedLibrariesAndExtractNothing() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(Files.createDirectory(sandbox.resolve("isolated")));
        assertEquals("EMBEDDED 1", probe(sandbox.resolve("first.log"), isolated, "embed"));
        Map<Path, String> placed = isolated.snapshot();

        assertEquals("EMBEDDED 1", probe(sandbox.resolve("second.log"), isolated, "embed"));

        // The runtime's empty extraction directory is removed when the process exits.
        isolated.assertUnchanged(placed);

        try (Stream<Path> temporary = Files.list(isolated.temporary())) {
            assertEquals(
                    List.of(),
                    temporary
                            .filter(path -> path.getFileName().toString().startsWith("onnxruntime-java"))
                            .toList());
        }
    }

    @Test
    void aRuntimeThatCannotLoadItsNativeLibraryFailsClearlyEveryTime() throws Exception {
        // ONNX Runtime loads its libraries only from this directory when the property is set.
        Path empty = Files.createDirectory(sandbox.resolve("no-native-libraries"));
        List<String> options = List.of("-Donnxruntime.native.path=" + empty);

        assertEquals(
                "RUNTIME_UNAVAILABLE RUNTIME_UNAVAILABLE",
                probe(sandbox.resolve("broken.log"), options, "broken-runtime"));
    }

    @Test
    // The probe's limit, plus the time to start it and to stop it.
    @Timeout(30)
    void aProbeThatNeverFinishesFailsWithinItsLimitAndShowsItsOutput() {
        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> probe(sandbox.resolve("hang.log"), List.of(), null, "hang", Duration.ofSeconds(5)));

        assertTrue(failure.getMessage().contains("did not finish within 5 s"), failure.getMessage());
        Matcher hanging = Pattern.compile("HANGING (\\d+)").matcher(failure.getMessage());
        assertTrue(hanging.find(), failure.getMessage());
        long pid = Long.parseLong(hanging.group(1));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "The probe was stopped");
    }

    private static String probe(Path log, List<String> options, String mode) throws Exception {
        return probe(log, options, null, mode, PROBE_LIMIT);
    }

    private static String probe(Path log, OfflineEnvironment isolated, String mode) throws Exception {
        return probe(log, isolated.jvmArguments(), isolated, mode, PROBE_LIMIT);
    }

    /**
     * Runs the probe in a fresh JVM and returns its output. The output goes to a file, so a probe that hangs cannot
     * block the test: it is stopped after {@code limit}, and the test fails with what it printed.
     */
    private static String probe(
            Path log, List<String> options, OfflineEnvironment isolated, String mode, Duration limit) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(OfflineSandbox.initializationLogArgument(log));
        command.addAll(options);
        command.addAll(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                EmbedderLoadingProbe.class.getName(),
                mode,
                EmbedderTest.model("tiny").toString()));
        Path output = log.resolveSibling(log.getFileName() + ".out");
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());

        if (isolated != null) {
            isolated.configure(builder.environment());
        }

        Process process = builder.start();
        process.getOutputStream().close();

        if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("The probe JVM did not finish within " + limit.toSeconds() + " s; it printed: "
                    + Files.readString(output, StandardCharsets.UTF_8));
        }

        String printed = Files.readString(output, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), printed);

        return printed.strip();
    }
}
