package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.testing.OfflineEnvironment;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks in fresh JVMs when ONNX Runtime initializes and what it leaves behind, which a test JVM that already ran an
 * embedder cannot show.
 */
class EmbedderLoadingTest {

    private static final String RUNTIME = "ai.onnxruntime.OrtEnvironment";

    @TempDir
    Path sandbox;

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
    void embeddingWritesNothingOutsideTheRuntimesExtractionDirectory() throws Exception {
        // Telemetry builds of the runtime keep a device id and an event store under the user's home directory.
        OfflineEnvironment isolated = OfflineEnvironment.create(Files.createDirectory(sandbox.resolve("isolated")));
        Map<Path, String> before = isolated.snapshot();

        assertEquals("EMBEDDED 1", probe(sandbox.resolve("isolated.log"), isolated, "embed"));

        isolated.assertOnlyRuntimeExtraction(before);
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

    private static String probe(Path log, List<String> options, String mode) throws Exception {
        return probe(log, options, null, mode);
    }

    private static String probe(Path log, OfflineEnvironment isolated, String mode) throws Exception {
        return probe(log, isolated.jvmArguments(), isolated, mode);
    }

    private static String probe(Path log, List<String> options, OfflineEnvironment isolated, String mode)
            throws Exception {
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
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);

        if (isolated != null) {
            isolated.configure(builder.environment());
        }

        Process process = builder.start();
        process.getOutputStream().close();

        String output = read(process);

        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "The probe JVM did not finish");
        assertEquals(0, process.exitValue(), output);

        return output.strip();
    }

    private static String read(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
