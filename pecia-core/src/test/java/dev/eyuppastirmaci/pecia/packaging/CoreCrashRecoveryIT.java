package dev.eyuppastirmaci.pecia.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.testing.IndexDump;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A packaged indexing process killed in the middle of a run leaves an index that passes SQLite's checks, and running
 * it again produces exactly the index a clean run produces.
 */
class CoreCrashRecoveryIT {

    private static final int FILES = 800;
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @TempDir
    Path sandbox;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"recovery-lexical", "recovery-semantic"})
    void aKilledRunRecoversToTheCleanIndex(String scenario) throws Exception {
        Path parent = Files.createDirectory(sandbox.resolve("projects")).toRealPath();
        Path killed = Files.createDirectory(parent.resolve("killed"));
        Path clean = Files.createDirectory(parent.resolve("clean"));
        try (InputStream model = CoreCrashRecoveryIT.class.getResourceAsStream("/semantic/tiny/tiny.onnx")) {
            assertNotNull(model, "Missing tiny model resource");
            Files.copy(model, parent.resolve("tiny.onnx"));
        }
        writeCorpus(killed);
        writeCorpus(clean);
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                Files.createDirectory(sandbox.resolve("runs")));
        Path database = killed.resolve(".pecia/index.db");

        Process process = runner.start(scenario, killed);

        try {
            awaitCommittedFile(process, database);
        } finally {
            // A hard kill: no shutdown hook runs and no transaction is finished.
            process.destroyForcibly();
            assertTrue(process.waitFor(PATIENCE.toSeconds(), TimeUnit.SECONDS), "The process did not end");
        }

        int committed = Integer.parseInt(query(database, "SELECT count(*) FROM files"));
        assertTrue(committed > 0 && committed < FILES, committed + " files were committed before the kill");
        assertEquals("ok", query(database, "PRAGMA integrity_check"));
        assertFalse(hasRows(database, "PRAGMA foreign_key_check"), "No foreign-key violation");

        assertEquals(
                "PACKAGED_CORE_OK " + scenario,
                runner.run(scenario, killed, PATIENCE).stdout().strip());
        assertEquals(
                "PACKAGED_CORE_OK " + scenario,
                runner.run(scenario, clean, PATIENCE).stdout().strip());

        IndexDump recovered = IndexDump.read(database, killed, List.of());
        IndexDump expected = IndexDump.read(clean.resolve(".pecia/index.db"), clean, List.of());
        assertEquals(FILES, expected.files().size());
        assertEquals(scenario.endsWith("semantic"), !expected.embeddings().isEmpty());
        assertEquals(expected, recovered);
    }

    /** Writes Markdown files large enough that indexing them takes several seconds. */
    private static void writeCorpus(Path project) throws IOException {
        StringBuilder paragraph = new StringBuilder();

        for (int line = 0; line < 60; line++) {
            paragraph
                    .append("word")
                    .append(line % 97)
                    .append(" refund policy line ")
                    .append(line)
                    .append(' ');
        }

        String body = (paragraph + "\n\n").repeat(6);

        for (int file = 0; file < FILES; file++) {
            Path directory = Files.createDirectories(project.resolve("dir" + file % 20));
            Files.writeString(directory.resolve("note" + file + ".md"), "# Note " + file + "\n\n" + body);
        }
    }

    /** Waits until the run has committed at least one file, so the kill lands in the middle of the run. */
    private static void awaitCommittedFile(Process process, Path database) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();

        while (true) {
            assertTrue(process.isAlive(), "The run ended before it could be killed");
            assertTrue(System.nanoTime() < deadline, "The run committed no file in time");

            if (Files.exists(database)) {
                try {
                    if (Integer.parseInt(query(database, "SELECT count(*) FROM files")) > 0) {
                        return;
                    }
                } catch (SQLException busyOrUncreated) {
                    // The run may hold a write lock or still be creating the schema.
                }
            }

            Thread.sleep(20);
        }
    }

    private static String query(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static boolean hasRows(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            return rows.next();
        }
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, "Missing build property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.exists(path), "Missing build output: " + path);

        return path;
    }
}
