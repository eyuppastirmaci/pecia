package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A storage failure at any stage of a run ends it as FAILED with a classified reason, rolls back only the work in
 * progress, leaves a consistent index, and the next run produces exactly the clean index.
 */
class StorageFailureMatrixTest {

    private static final ModelOutcome TINY = TinyModel.cached();
    private static final EmbeddingProfile CURRENT = EmbeddingProfile.minilm();
    // The same model under another pinned revision, so a semantic run discards the stored embeddings first.
    private static final EmbeddingProfile NEXT = new EmbeddingProfile(
            CURRENT.modelId(),
            "next-revision",
            CURRENT.modelSha256(),
            CURRENT.dimensions(),
            CURRENT.tokenizerAlgorithm(),
            CURRENT.vocabularySha256(),
            CURRENT.maxInputTokens(),
            CURRENT.pooling(),
            CURRENT.normalization(),
            CURRENT.input());

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private EmbeddingProfile profile = CURRENT;
    private int projects;

    /**
     * Each stage fails the first write of its kind. The changed files are, in path order, {@code docs/notes.txt},
     * {@code docs/Ödeme rehberi.md} (the only one with headings), and {@code handbook.txt}; {@code config.yaml} was
     * deleted.
     */
    @ParameterizedTest(name = "{0}, semantic {1}")
    @CsvSource({
        "BEFORE UPDATE ON files, false",
        "BEFORE DELETE ON chunks, false",
        "BEFORE INSERT ON chunks, false",
        "BEFORE INSERT ON chunk_attributes, false",
        "BEFORE INSERT ON chunk_headings, false",
        "AFTER INSERT ON chunks, false",
        "BEFORE INSERT ON chunk_identities, false",
        "BEFORE INSERT ON file_chunking_profiles, false",
        "BEFORE DELETE ON files, false",
        "BEFORE INSERT ON chunk_embeddings, true",
        "BEFORE INSERT ON chunks, true"
    })
    void aFailingWriteRollsBackOnlyItsFileAndTheNextRunCompletesTheIndex(String stage, boolean semantic)
            throws Exception {
        Path project = corpus();
        IndexOptions options = IndexOptions.semantic(semantic);
        service().index(project, options);
        IndexDump before = dump(project);
        change(project);
        trigger(project, stage);

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(project, options));

        assertFailed(failure, StorageFailure.OTHER);
        assertConsistent(project);
        IndexDump after = dump(project);
        assertEachFileIsWhole(before, after, cleanDump(project, options));
        assertTrue(
                failure.getCause().getMessage().contains("injected"),
                failure.getCause().getMessage());

        dropTrigger(project);

        assertRecovers(project, options);
    }

    @Test
    void aFailingBackfillPageKeepsEarlierPagesAndTheNextRunCompletesTheIndex() throws Exception {
        Path project = manyChunkProject();
        service().index(project);
        // The first page commits; the second fails as it is stored.
        trigger(project, "BEFORE INSERT ON chunk_embeddings WHEN (SELECT count(*) FROM chunk_embeddings) >= 32");

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(project, IndexOptions.semantic(true)));

        assertFailed(failure, StorageFailure.OTHER);
        assertEquals(32, failure.result().backfilledChunks());
        assertEquals(32, dump(project).embeddings().size());
        assertConsistent(project);

        dropTrigger(project);

        assertRecovers(project, IndexOptions.semantic(true));
    }

    @Test
    void aFailureWhileDiscardingAnotherProfileKeepsItsEmbeddingsAndTheNextRunCompletesTheIndex() throws Exception {
        Path project = corpus();
        service().index(project, IndexOptions.semantic(true));
        IndexDump before = dump(project);
        profile = NEXT;
        trigger(project, "BEFORE DELETE ON chunk_embeddings");

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(project, IndexOptions.semantic(true)));

        assertFailed(failure, StorageFailure.OTHER);
        assertEquals(0, failure.result().discardedEmbeddings());
        assertEquals(before, dump(project));
        assertConsistent(project);

        dropTrigger(project);

        assertRecovers(project, IndexOptions.semantic(true));
    }

    @Test
    void anIndexHeldByAnotherProcessFailsAsBusyAndTheNextRunCompletesTheIndex() throws Exception {
        Path project = corpus();
        service().index(project);
        IndexDump before = dump(project);
        change(project);
        IndexException failure;

        try (Connection holder = connect(project)) {
            holder.createStatement().execute("BEGIN EXCLUSIVE");
            failure = assertThrows(IndexException.class, () -> service().index(project));
            holder.createStatement().execute("ROLLBACK");
        }

        assertFailed(failure, StorageFailure.BUSY);
        assertEquals(before, dump(project));

        assertRecovers(project, IndexOptions.semantic(false));
    }

    @Test
    void aReadOnlyIndexFailsAsReadOnly() throws Exception {
        Path project = corpus();
        service().index(project);
        change(project);
        Path database = project.resolve(".pecia/index.db");
        assumeTrue(Files.getFileStore(database).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(database);

        try {
            Files.setPosixFilePermissions(database, Set.of(PosixFilePermission.OWNER_READ));
            assumeTrue(!Files.isWritable(database), "Current account bypasses permissions");

            IndexException failure =
                    assertThrows(IndexException.class, () -> service().index(project));

            assertFailed(failure, StorageFailure.READ_ONLY);
        } finally {
            Files.setPosixFilePermissions(database, original);
        }

        assertRecovers(project, IndexOptions.semantic(false));
    }

    @Test
    void aDamagedIndexFailsAsCorrupt() throws Exception {
        Path project = corpus();
        Path database = Files.createDirectories(project.resolve(".pecia")).resolve("index.db");
        Files.writeString(database, "not a database");

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(project));

        assertFailed(failure, StorageFailure.CORRUPT);
        assertEquals("not a database", Files.readString(database));
    }

    @Test
    void failuresOutsideStorageCarryNoStorageReason() throws Exception {
        Path project = corpus();
        IndexException failure = assertThrows(
                IndexException.class,
                () -> new IndexService(loader, StorageFailureMatrixTest::openStorage, listener -> TINY, (model, n) -> {
                            throw new EmbeddingException(EmbeddingException.Reason.INVALID_MODEL, "injected", null);
                        })
                        .index(project, IndexOptions.semantic(true)));

        assertEquals(IndexResult.Status.EMBEDDER_UNAVAILABLE, failure.result().status());
        assertTrue(failure.storageFailure().isEmpty());
    }

    private static void assertFailed(IndexException failure, StorageFailure reason) {
        assertEquals(IndexResult.Status.FAILED, failure.result().status());
        assertEquals(Optional.of(reason), failure.storageFailure());
    }

    /** Every stored file is either as it was before the run or as the clean index has it, never a mix. */
    private static void assertEachFileIsWhole(IndexDump before, IndexDump after, IndexDump clean) {
        boolean someFileKeptItsOldVersion = false;

        for (IndexDump.FileEntry file : after.files()) {
            String path = file.sourcePath();
            boolean asBefore = sameFile(before, after, path);
            boolean asClean = sameFile(clean, after, path);

            assertTrue(asBefore || asClean, path + " is neither its previous nor its new version");
            someFileKeptItsOldVersion |= asBefore && !asClean;
        }

        assertTrue(someFileKeptItsOldVersion, "The failing file keeps its previous version");
    }

    private static boolean sameFile(IndexDump expected, IndexDump actual, String path) {
        return expected.files().stream()
                        .filter(file -> file.sourcePath().equals(path))
                        .toList()
                        .equals(actual.files().stream()
                                .filter(file -> file.sourcePath().equals(path))
                                .toList())
                && expected.chunks().stream()
                        .filter(chunk -> chunk.sourcePath().equals(path))
                        .toList()
                        .equals(actual.chunks().stream()
                                .filter(chunk -> chunk.sourcePath().equals(path))
                                .toList())
                && expected.ftsRows().stream()
                        .filter(row -> path.equals(row.sourcePath()))
                        .toList()
                        .equals(actual.ftsRows().stream()
                                .filter(row -> path.equals(row.sourcePath()))
                                .toList());
    }

    /** SQLite's own checks: page structure, foreign keys, and the full-text index against its content. */
    private static void assertConsistent(Path project) throws SQLException {
        try (Connection connection = connect(project)) {
            assertEquals("ok", single(connection, "PRAGMA integrity_check"));

            try (ResultSet violations = connection.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(violations.next(), "No foreign-key violation");
            }

            // Raises an error when the full-text index disagrees with the chunks it indexes.
            connection.createStatement().execute("INSERT INTO chunks_fts(chunks_fts) VALUES('integrity-check')");
        }
    }

    private void assertRecovers(Path project, IndexOptions options) throws Exception {
        IndexResult next = service().index(project, options);

        assertEquals(IndexResult.Status.COMPLETE, next.status());
        assertEquals(cleanDump(project, options), dump(project));
    }

    /** Indexes a fresh copy of the project's current files and returns its dump. */
    private IndexDump cleanDump(Path project, IndexOptions options) throws Exception {
        Path clean =
                Files.createDirectories(sandbox.resolve("clean-" + projects++)).toRealPath();

        try (var paths = Files.walk(project)) {
            for (Path path : paths.toList()) {
                Path relative = project.relativize(path);

                if (relative.toString().isEmpty() || relative.startsWith(".pecia")) {
                    continue;
                }

                Path target = clean.resolve(relative.toString());

                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }

        service().index(clean, options);
        return dump(clean);
    }

    /** Changes three files, one of them with headings, and deletes one, so a run has replacements and a deletion. */
    private static void change(Path project) throws IOException {
        for (Path file : List.of(LexicalTestCorpus.NOTES, LexicalTestCorpus.GUIDE, LexicalTestCorpus.HANDBOOK)) {
            Files.writeString(project.resolve(file), Files.readString(project.resolve(file)) + "\nchanged\n");
        }

        Files.delete(project.resolve(LexicalTestCorpus.CONFIG));
    }

    private static void trigger(Path project, String when) throws SQLException {
        try (Connection connection = connect(project)) {
            connection
                    .createStatement()
                    .execute("CREATE TRIGGER injected_failure " + when + " BEGIN SELECT RAISE(ABORT, 'injected'); END");
        }
    }

    private static void dropTrigger(Path project) throws SQLException {
        try (Connection connection = connect(project)) {
            connection.createStatement().execute("DROP TRIGGER injected_failure");
        }
    }

    private static Connection connect(Path project) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + project.resolve(".pecia/index.db"));
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static IndexDump dump(Path project) throws Exception {
        return IndexDump.read(project.resolve(".pecia/index.db"), project, List.of());
    }

    private Path corpus() throws IOException {
        Path project = Files.createDirectories(sandbox.resolve("project-" + projects++))
                .toRealPath();
        LexicalTestCorpus.write(project);
        return project;
    }

    /** Writes a project whose single file splits into 70 six-token chunks, backfilled in pages of 32. */
    private Path manyChunkProject() throws IOException {
        Path project =
                Files.createDirectories(sandbox.resolve("many-" + projects++)).toRealPath();
        Files.writeString(
                project.resolve(".pecia.toml"),
                "[chunk]\nmax_tokens = 6\noverlap_tokens = 0\n\n[embed]\nconcurrency = 1\n");
        Files.writeString(project.resolve("words.txt"), "alpha beta gamma delta ".repeat(70) + "\n");
        return project;
    }

    private IndexService service() {
        return new IndexService(
                loader, StorageFailureMatrixTest::openStorage, listener -> TINY, TextEmbedder::open, () -> profile);
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }
}
