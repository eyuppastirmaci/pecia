package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * Proves that runs with nothing to index perform no index writes. A separate connection held open across the run
 * reads {@code PRAGMA data_version}, which changes whenever another connection commits; the database bytes and
 * any rollback-journal or write-ahead-log sidecar files must also be unchanged. A content change serves as the
 * negative control that shows the probe detects a write.
 */
class LexicalZeroWriteTest {

    private static final String DEFAULT_CONFIG =
            "[chunk]\nmax_tokens = 256\noverlap_tokens = 32\n[embed]\nconcurrency = 2\n";
    // Differs from the default configuration only in a setting that does not affect lexical processing.
    private static final String CONCURRENCY_CONFIG =
            "[chunk]\nmax_tokens = 256\noverlap_tokens = 32\n[embed]\nconcurrency = 4\n";
    private static final String[] SIDECAR_SUFFIXES = {"-journal", "-wal", "-shm"};

    @TempDir
    Path root;

    private final IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
    private Path database;

    @BeforeEach
    void indexCorpus() throws Exception {
        root = root.toRealPath();
        LexicalTestCorpus.write(root);
        Files.writeString(root.resolve(PeciaConfigFile.FILE_NAME), DEFAULT_CONFIG);
        IndexResult first = indexService.index(root);
        assertEquals(IndexResult.Status.COMPLETE, first.status());
        database = first.context().databasePath();
    }

    static Stream<NoWorkRun> noWorkRuns() {
        return Stream.of(
                new NoWorkRun("unchanged rerun", (service, root) -> requireNoWork(service.index(root))),
                new NoWorkRun("timestamp-only change", (service, root) -> {
                    Path guide = root.resolve(LexicalTestCorpus.GUIDE);
                    FileTime modified = Files.getLastModifiedTime(guide);
                    Files.setLastModifiedTime(
                            guide, FileTime.from(modified.toInstant().plus(Duration.ofHours(1))));
                    requireNoWork(service.index(root));
                }),
                new NoWorkRun("irrelevant configuration change", (service, root) -> {
                    Files.writeString(root.resolve(PeciaConfigFile.FILE_NAME), CONCURRENCY_CONFIG);
                    requireNoWork(service.index(root));
                }),
                new NoWorkRun(
                        "child target rerun", (service, root) -> requireNoWork(service.index(root.resolve("docs")))),
                new NoWorkRun("dry-run preview", (service, root) -> service.preview(root)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noWorkRuns")
    void runsWithNothingToIndexLeaveTheDatabaseUntouched(NoWorkRun run) throws Exception {
        try (Connection probe = openProbe()) {
            long dataVersion = dataVersion(probe);
            DatabaseFiles before = DatabaseFiles.read(database);

            run.action().apply(indexService, root);

            assertEquals(dataVersion, dataVersion(probe), "Another connection committed to the index");
            assertEquals(before, DatabaseFiles.read(database));
        }
    }

    @Test
    void contentChangeIsDetectedByTheSameProbe() throws Exception {
        try (Connection probe = openProbe()) {
            long dataVersion = dataVersion(probe);
            DatabaseFiles before = DatabaseFiles.read(database);
            Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Release notes: changed.\n");

            IndexResult result = indexService.index(root);

            assertEquals(1, result.indexedFiles());
            assertNotEquals(dataVersion, dataVersion(probe));
            assertNotEquals(before.databaseHash(), DatabaseFiles.read(database).databaseHash());
        }
    }

    /* A run that silently failed would also write nothing, so require a complete run with only unchanged files. */
    private static void requireNoWork(IndexResult result) {
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(0, result.indexedFiles());
        assertEquals(0, result.deletedFiles());
        assertEquals(result.candidateCount(), result.unchangedFiles());
    }

    private Connection openProbe() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }

    private static long dataVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA data_version")) {
            result.next();
            return result.getLong(1);
        }
    }

    @FunctionalInterface
    interface Action {

        void apply(IndexService service, Path root) throws Exception;
    }

    record NoWorkRun(String name, Action action) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The database file's SHA-256 and the size of each sidecar file that exists.
     *
     * @param sidecars sizes keyed by suffix; absent files are omitted
     */
    record DatabaseFiles(String databaseHash, Map<String, Long> sidecars) {

        static DatabaseFiles read(Path database) throws IOException {
            Map<String, Long> sidecars = new TreeMap<>();

            for (String suffix : SIDECAR_SUFFIXES) {
                Path sidecar = database.resolveSibling(database.getFileName() + suffix);

                if (Files.exists(sidecar)) {
                    sidecars.put(suffix, Files.size(sidecar));
                }
            }

            return new DatabaseFiles(
                    ContentHash.sha256(Files.readAllBytes(database)).value(), sidecars);
        }
    }
}
