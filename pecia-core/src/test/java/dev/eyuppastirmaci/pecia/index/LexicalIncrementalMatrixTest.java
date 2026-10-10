package dev.eyuppastirmaci.pecia.index;

import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.AUTH;
import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.CONFIG;
import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.EMPTY;
import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.GUIDE;
import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.HANDBOOK;
import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.NOTES;
import static dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus.SCRIPT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.IndexWriteObserver;
import dev.eyuppastirmaci.pecia.testing.IndexWriteObserver.ChunkKey;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * Applies one change to an indexed corpus, reindexes it, and asserts exactly which files and chunks the run wrote,
 * retained, or removed. A follow-up run must then be unchanged and write nothing. Unless a case documents that
 * stale records are deliberately retained, the incremental result must also equal a fresh index of the changed
 * corpus, so incremental indexing is indistinguishable from a full rebuild.
 *
 * <p>Replacement is per file: a written file has all of its current chunks written, and chunk keys that no longer
 * exist are removed. Expected chunk sets therefore follow from the explicitly expected file sets.
 *
 * <p>Paths are compared in their stored spelling, not as {@link Path} values, because Windows path equality ignores
 * case. Case-only and normalization-only renames are ordinary renames on filesystems that distinguish the
 * spellings, so they run everywhere with the same expectations. Cases that need folding or distinction are skipped
 * by a runtime capability probe, never by operating system name.
 *
 * <p>Discovery never admits the configuration file, so configuration cases write only the files whose processing
 * the changed setting affects.
 */
class LexicalIncrementalMatrixTest {

    private static final int CORPUS_SIZE = LexicalTestCorpus.files().size();
    private static final Set<Path> CORPUS = LexicalTestCorpus.files().keySet();
    private static final Path CONFIG_FILE = Path.of(PeciaConfigFile.FILE_NAME);
    private static final Path RENAMED_NOTES = Path.of("docs/release-notes.txt");
    private static final Path CASE_RENAMED_NOTES = Path.of("docs/Notes.txt");
    private static final Path DECOMPOSED_GUIDE =
            Path.of("docs", Normalizer.normalize(GUIDE.getFileName().toString(), Normalizer.Form.NFD));
    private static final Set<Path> DOCS = Set.of(GUIDE, NOTES);
    private static final Set<Path> CASE_RENAMED_DOCS =
            Set.of(Path.of("Docs").resolve(GUIDE.getFileName()), Path.of("Docs").resolve(NOTES.getFileName()));

    // Every setting spelled out at its default value, so each configuration case changes exactly one of them.
    private static final String DEFAULT_CONFIG = config("", 256, 32, 2);
    // Below the long handbook and above every other corpus file.
    private static final int SMALL_FILE_LIMIT = 1024;

    @TempDir
    Path sandbox;

    private final IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

    static Stream<MatrixCase> cases() {
        return Stream.of(
                // Content, timestamp, rename, and deletion changes.
                MatrixCase.of("unchanged corpus", root -> {}, Set.of(), Set.of(), CORPUS_SIZE),
                MatrixCase.of(
                        "content edit",
                        root -> Files.writeString(root.resolve(NOTES), "Release notes: rewritten entirely.\n"),
                        Set.of(NOTES),
                        Set.of(),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "same size and timestamp byte change",
                        LexicalIncrementalMatrixTest::changeByteKeepingSizeAndTimestamp,
                        Set.of(AUTH),
                        Set.of(),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "timestamp-only change",
                        root -> shiftTimestamp(root.resolve(GUIDE)),
                        Set.of(),
                        Set.of(),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "empty file gains content",
                        root -> Files.writeString(root.resolve(EMPTY), "no longer empty\n"),
                        Set.of(EMPTY),
                        Set.of(),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "multi-chunk document shrinks to one chunk",
                        root -> Files.writeString(root.resolve(HANDBOOK), "Handbook\n\nOnly one section remains.\n"),
                        Set.of(HANDBOOK),
                        Set.of(),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "ordinary rename",
                        root -> Files.move(root.resolve(NOTES), root.resolve(RENAMED_NOTES)),
                        Set.of(RENAMED_NOTES),
                        Set.of(NOTES),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "file deletion",
                        root -> Files.delete(root.resolve(CONFIG)),
                        Set.of(),
                        Set.of(CONFIG),
                        CORPUS_SIZE - 1),
                MatrixCase.of(
                        "nested directory deletion",
                        root -> deleteRecursively(root.resolve("src")),
                        Set.of(),
                        Set.of(AUTH, SCRIPT),
                        CORPUS_SIZE - 2),

                // Case and Unicode normalization renames.
                MatrixCase.of(
                        "case-only file rename",
                        root -> renameSpelling(root.resolve(NOTES), CASE_RENAMED_NOTES.getFileName()),
                        Set.of(CASE_RENAMED_NOTES),
                        Set.of(NOTES),
                        CORPUS_SIZE),
                MatrixCase.of(
                        "case-only directory rename",
                        root -> renameSpelling(root.resolve("docs"), Path.of("Docs")),
                        CASE_RENAMED_DOCS,
                        DOCS,
                        CORPUS_SIZE),
                MatrixCase.of(
                        "NFC to NFD file rename",
                        root -> renameSpelling(root.resolve(GUIDE), DECOMPOSED_GUIDE.getFileName()),
                        Set.of(DECOMPOSED_GUIDE),
                        Set.of(GUIDE),
                        CORPUS_SIZE),
                MatrixCase.of("child target typed with another case", root -> {}, Set.of(), Set.of(), DOCS.size())
                        .requiring(Filesystem.CASE_INSENSITIVE)
                        .reindexing(Path.of("DOCS")),
                MatrixCase.of(
                                "case-only directory rename reindexed through the old spelling",
                                root -> renameSpelling(root.resolve("docs"), Path.of("Docs")),
                                CASE_RENAMED_DOCS,
                                DOCS,
                                DOCS.size())
                        .requiring(Filesystem.CASE_INSENSITIVE)
                        .reindexing(Path.of("docs")),
                MatrixCase.of(
                                "case-distinct sibling",
                                root -> Files.writeString(
                                        root.resolve(CASE_RENAMED_NOTES), "Distinct sibling notes.\n"),
                                Set.of(CASE_RENAMED_NOTES),
                                Set.of(),
                                CORPUS_SIZE + 1)
                        .requiring(Filesystem.CASE_SENSITIVE),

                // Configuration changes.
                MatrixCase.of(
                                "max_tokens change rechunks every file",
                                root -> writeConfig(root, config("", 128, 32, 2)),
                                CORPUS,
                                Set.of(),
                                CORPUS_SIZE)
                        .withBaselineConfig(DEFAULT_CONFIG),
                MatrixCase.of(
                                "overlap_tokens change rechunks every file",
                                root -> writeConfig(root, config("", 256, 8, 2)),
                                CORPUS,
                                Set.of(),
                                CORPUS_SIZE)
                        .withBaselineConfig(DEFAULT_CONFIG),
                MatrixCase.of(
                                "embed.concurrency change writes nothing",
                                root -> writeConfig(root, config("", 256, 32, 4)),
                                Set.of(),
                                Set.of(),
                                CORPUS_SIZE)
                        .withBaselineConfig(DEFAULT_CONFIG),
                MatrixCase.of(
                                "max_file_bytes below a file's size rejects it and retains its records",
                                root -> writeConfig(
                                        root, config("max_file_bytes = " + SMALL_FILE_LIMIT + "\n", 256, 32, 2)),
                                Set.of(),
                                Set.of(),
                                CORPUS_SIZE)
                        .withBaselineConfig(DEFAULT_CONFIG)
                        .rejecting(1),
                MatrixCase.of(
                                "added exclusion retains the excluded records",
                                root -> writeConfig(root, config("exclude = ['**/*.yaml']\n", 256, 32, 2)),
                                Set.of(),
                                Set.of(),
                                CORPUS_SIZE - 1)
                        .withBaselineConfig(DEFAULT_CONFIG)
                        .retainingStaleRecords(),
                MatrixCase.of(
                                "removed exclusion admits the file",
                                root -> writeConfig(root, DEFAULT_CONFIG),
                                Set.of(CONFIG),
                                Set.of(),
                                CORPUS_SIZE)
                        .withBaselineConfig(config("exclude = ['**/*.yaml']\n", 256, 32, 2)),

                // Stored processing state written by an earlier compatible version.
                MatrixCase.of("earlier extraction version", root -> {}, Set.of(NOTES), Set.of(), CORPUS_SIZE)
                        .withEarlierIdentity(
                                NOTES,
                                identity -> new ChunkingIdentity(
                                        "pecia-utf8-extraction-v0",
                                        identity.chunkingVersion(),
                                        identity.tokenizer(),
                                        identity.maxTokens(),
                                        identity.overlapTokens())),
                MatrixCase.of("earlier chunking version", root -> {}, Set.of(GUIDE), Set.of(), CORPUS_SIZE)
                        .withEarlierIdentity(
                                GUIDE,
                                identity -> new ChunkingIdentity(
                                        identity.extractionVersion(),
                                        "pecia-built-in-chunking-v0",
                                        identity.tokenizer(),
                                        identity.maxTokens(),
                                        identity.overlapTokens())),
                MatrixCase.of("earlier tokenizer vocabulary", root -> {}, Set.of(HANDBOOK), Set.of(), CORPUS_SIZE)
                        .withEarlierIdentity(
                                HANDBOOK,
                                identity -> new ChunkingIdentity(
                                        identity.extractionVersion(),
                                        identity.chunkingVersion(),
                                        new TokenizerCompatibility(
                                                identity.tokenizer().algorithm(),
                                                "0".repeat(64),
                                                identity.tokenizer().vocabularySize(),
                                                identity.tokenizer().maxInputTokens(),
                                                identity.tokenizer().specialTokenCount()),
                                        identity.maxTokens(),
                                        identity.overlapTokens())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void reindexingWritesRetainsAndRemovesExactlyTheAffectedFilesAndChunks(MatrixCase matrixCase) throws Exception {
        assumeFilesystem(matrixCase.requires());
        Path root = corpus("incremental", matrixCase);
        Path database = index(root, IndexResult.Status.COMPLETE).context().databasePath();

        if (matrixCase.earlierIdentity() != null) {
            storeEarlierIdentity(database, root, matrixCase.earlierIdentity());
        }

        IndexDump before = dump(root, database);
        IndexWriteObserver observer = IndexWriteObserver.start(database);
        matrixCase.change().apply(root);
        assumeSpellingsPreserved(root, matrixCase.written());

        Path target = root.resolve(matrixCase.target());
        IndexResult result = index(target, matrixCase.status());
        IndexWriteObserver.Writes writes = observer.finish();
        IndexDump after = dump(root, database);

        SortedSet<String> written = encode(matrixCase.written());
        SortedSet<String> removed = encode(matrixCase.removed());
        Set<String> beforeFiles = files(before);
        Set<String> afterFiles = files(after);
        SortedSet<String> retained = new TreeSet<>(afterFiles);
        retained.removeAll(written);
        assertTrue(afterFiles.containsAll(written), "Written files must exist afterwards");
        assertTrue(beforeFiles.containsAll(removed), "Removed files must exist beforehand");

        assertEquals(written, writes.writtenFiles());
        assertEquals(retained, writes.retainedFiles());
        assertEquals(removed, writes.removedFiles());
        assertEquals(chunkKeys(after, written), writes.writtenChunks());
        assertEquals(chunkKeys(after, retained), writes.retainedChunks());
        SortedSet<ChunkKey> removedChunks = chunkKeys(before, beforeFiles);
        removedChunks.removeAll(chunkKeys(after, afterFiles));
        assertEquals(removedChunks, writes.removedChunks());
        assertEquals(written.isEmpty() && removed.isEmpty(), writes.writeCount() == 0);

        assertEquals(matrixCase.candidates(), result.candidateCount());
        assertEquals(written.size(), result.indexedFiles());
        assertEquals(matrixCase.unchanged(), result.unchangedFiles());
        assertEquals(matrixCase.rejected(), result.rejectedFiles());
        assertEquals(removed.size(), result.deletedFiles());
        assertEquals(writes.writtenChunks().size(), result.writtenChunks());

        IndexWriteObserver rerunObserver = IndexWriteObserver.start(database);
        IndexResult rerun = index(target, matrixCase.status());
        assertEquals(0, rerunObserver.finish().writeCount(), "A follow-up run must write nothing");
        assertEquals(matrixCase.unchanged() + written.size(), rerun.unchangedFiles());

        if (matrixCase.matchesFreshIndex()) {
            // A fresh index always covers the whole project; child-target runs leave everything outside unchanged.
            Path fresh = corpus("fresh", matrixCase);
            matrixCase.change().apply(fresh);
            assertEquals(dump(fresh, index(fresh, matrixCase.status()).context().databasePath()), after);
        }
    }

    private void assumeFilesystem(Filesystem required) throws IOException {
        if (required == Filesystem.ANY) {
            return;
        }

        Path probe = Files.writeString(sandbox.resolve("CaseProbe.tmp"), "");
        boolean foldsCase;

        try {
            foldsCase = Files.exists(sandbox.resolve("caseprobe.tmp"));
        } finally {
            Files.delete(probe);
        }

        assumeTrue(
                foldsCase == (required == Filesystem.CASE_INSENSITIVE),
                "Filesystem " + (foldsCase ? "folds" : "distinguishes") + " case");
    }

    /* Skips where the filesystem rewrites a requested name, such as one that stores every name decomposed. */
    private static void assumeSpellingsPreserved(Path root, Set<Path> written) throws IOException {
        Set<String> onDisk = new TreeSet<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.forEach(path -> onDisk.add(path.equals(root) ? "" : SqlitePath.encode(root.relativize(path))));
        }

        for (String path : encode(written)) {
            assumeTrue(onDisk.contains(path), "Filesystem does not preserve the spelling " + path);
        }
    }

    private Path corpus(String name, MatrixCase matrixCase) throws IOException {
        Path root = Files.createDirectory(sandbox.resolve(name)).toRealPath();
        // A Git marker pins the project root for child targets; discovery always skips it, so candidates are unchanged.
        Files.createDirectory(root.resolve(".git"));
        LexicalTestCorpus.write(root);

        if (matrixCase.baselineConfig() != null) {
            writeConfig(root, matrixCase.baselineConfig());
        }

        return root;
    }

    private IndexResult index(Path target, IndexResult.Status expected) throws Exception {
        IndexResult result = indexService.index(target);
        assertEquals(expected, result.status(), () -> "Unexpected issues: " + result.issues());
        return result;
    }

    private static IndexDump dump(Path root, Path database) throws Exception {
        return IndexDump.read(database, root, LexicalTestCorpus.QUERIES);
    }

    /**
     * Replaces one file's processing state with what an earlier compatible version would have stored: the earlier
     * identity with its matching fingerprint, and stable chunk IDs generated from it. Deleting the profile cascades
     * to the old identities, so the replacement is consistent rather than corrupt. Reading it back through storage
     * proves the run sees a valid earlier identity, not invalidated state that would be reprocessed anyway.
     */
    private static void storeEarlierIdentity(Path database, Path root, EarlierIdentity earlier) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        String sourcePath = SqlitePath.encode(earlier.sourcePath());

        try (Connection connection =
                JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties())) {
            connection.setAutoCommit(false);
            long fileId;
            ChunkingIdentity current;

            try (var query = connection.prepareStatement("""
                    SELECT f.id, p.extraction_version, p.chunking_version, p.tokenizer_algorithm,
                           p.vocabulary_sha256, p.vocabulary_size, p.max_input_tokens, p.special_token_count,
                           p.max_tokens, p.overlap_tokens
                    FROM files f JOIN file_chunking_profiles p ON p.file_id = f.id
                    WHERE f.source_path = ?
                    """)) {
                query.setString(1, sourcePath);

                try (var row = query.executeQuery()) {
                    assertTrue(row.next(), "Missing chunking profile for " + sourcePath);
                    fileId = row.getLong(1);
                    current = new ChunkingIdentity(
                            row.getString(2),
                            row.getString(3),
                            new TokenizerCompatibility(
                                    row.getString(4), row.getString(5), row.getInt(6), row.getInt(7), row.getInt(8)),
                            row.getInt(9),
                            row.getInt(10));
                }
            }

            ChunkingIdentity legacy = earlier.identity().apply(current);
            assertNotEquals(current.fingerprint(), legacy.fingerprint());
            ChunkIdGenerator ids = new ChunkIdGenerator(legacy);
            List<long[]> chunks = new ArrayList<>();

            try (var query = connection.prepareStatement("SELECT id, chunk_index FROM chunks WHERE file_id = ?")) {
                query.setLong(1, fileId);

                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        chunks.add(new long[] {rows.getLong(1), rows.getLong(2)});
                    }
                }
            }

            try (var delete = connection.prepareStatement("DELETE FROM file_chunking_profiles WHERE file_id = ?");
                    var identity = connection.prepareStatement(
                            "INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (?, ?, ?)");
                    var profile = connection.prepareStatement("""
                            INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version,
                                tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens,
                                special_token_count, max_tokens, overlap_tokens, fingerprint)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                delete.setLong(1, fileId);
                delete.executeUpdate();

                for (long[] chunk : chunks) {
                    identity.setLong(1, chunk[0]);
                    identity.setLong(2, fileId);
                    identity.setString(
                            3,
                            ids.generate(earlier.sourcePath(), (int) chunk[1]).value());
                    identity.executeUpdate();
                }

                profile.setLong(1, fileId);
                profile.setString(2, legacy.extractionVersion());
                profile.setString(3, legacy.chunkingVersion());
                profile.setString(4, legacy.tokenizer().algorithm());
                profile.setString(5, legacy.tokenizer().vocabularySha256());
                profile.setInt(6, legacy.tokenizer().vocabularySize());
                profile.setInt(7, legacy.tokenizer().maxInputTokens());
                profile.setInt(8, legacy.tokenizer().specialTokenCount());
                profile.setInt(9, legacy.maxTokens());
                profile.setInt(10, legacy.overlapTokens());
                profile.setString(11, legacy.fingerprint());
                profile.executeUpdate();
            }

            connection.commit();

            try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
                assertEquals(Optional.of(legacy), storage.files().findChunkingIdentity(fileId));
            }
        }
    }

    private static String config(String indexSettings, int maxTokens, int overlapTokens, int concurrency) {
        return "[index]\n" + indexSettings + "[chunk]\nmax_tokens = " + maxTokens + "\noverlap_tokens = "
                + overlapTokens + "\n[embed]\nconcurrency = " + concurrency + "\n";
    }

    private static void writeConfig(Path root, String content) throws IOException {
        Files.writeString(root.resolve(CONFIG_FILE), content);
    }

    private static SortedSet<String> encode(Set<Path> paths) {
        SortedSet<String> encoded = new TreeSet<>();
        paths.forEach(path -> encoded.add(SqlitePath.encode(path)));
        return encoded;
    }

    private static Set<String> files(IndexDump dump) {
        Set<String> files = new TreeSet<>();
        dump.files().forEach(file -> files.add(file.sourcePath()));
        return files;
    }

    private static SortedSet<ChunkKey> chunkKeys(IndexDump dump, Set<String> files) {
        SortedSet<ChunkKey> keys = new TreeSet<>();

        for (IndexDump.ChunkEntry chunk : dump.chunks()) {
            if (files.contains(chunk.sourcePath())) {
                keys.add(new ChunkKey(chunk.sourcePath(), chunk.chunkIndex()));
            }
        }

        return keys;
    }

    /* Replaces one character so size and modification time match, leaving only the content hash to detect it. */
    private static void changeByteKeepingSizeAndTimestamp(Path root) throws IOException {
        Path file = root.resolve(AUTH);
        FileTime modified = Files.getLastModifiedTime(file);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        String changed = original.replace("\"x\"", "\"y\"");
        assertNotEquals(original, changed);
        assertEquals(original.length(), changed.length());

        Files.writeString(file, changed, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, modified);
        assertEquals(modified, Files.getLastModifiedTime(file));
    }

    private static void shiftTimestamp(Path file) throws IOException {
        FileTime modified = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.from(modified.toInstant().plus(Duration.ofHours(1))));
    }

    /** Renames through a temporary name because a case-only move is a no-op on case-insensitive filesystems. */
    private static void renameSpelling(Path path, Path name) throws IOException {
        Path temporary = Files.move(path, path.resolveSibling(name + ".renaming"));
        Files.move(temporary, path.resolveSibling(name));
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** The case behavior a matrix case requires from the test filesystem. */
    enum Filesystem {
        ANY,
        CASE_INSENSITIVE,
        CASE_SENSITIVE
    }

    @FunctionalInterface
    interface Change {

        void apply(Path root) throws IOException;
    }

    /** Stored processing state for one file, derived from its current identity, applied before the observed run. */
    record EarlierIdentity(Path sourcePath, UnaryOperator<ChunkingIdentity> identity) {}

    /**
     * One matrix row. Build rows with {@link #of} and the {@code with}-style methods.
     *
     * @param target the reindexed directory relative to the project root; empty for the root itself
     * @param baselineConfig the configuration file written before the first run, or null for none
     * @param earlierIdentity stored state replaced after the first run, or null for none
     * @param written files expected to be written, including new paths created by the change
     * @param removed files expected to be removed from the index
     * @param candidates admitted candidates below the target after the change
     * @param rejected candidates expected to be rejected; their earlier records are retained
     * @param matchesFreshIndex whether the result must equal a fresh index; false where stale records are retained
     */
    record MatrixCase(
            String name,
            Filesystem requires,
            Path target,
            String baselineConfig,
            EarlierIdentity earlierIdentity,
            Change change,
            Set<Path> written,
            Set<Path> removed,
            int candidates,
            int rejected,
            boolean matchesFreshIndex) {

        static MatrixCase of(String name, Change change, Set<Path> written, Set<Path> removed, int candidates) {
            return new MatrixCase(
                    name, Filesystem.ANY, Path.of(""), null, null, change, written, removed, candidates, 0, true);
        }

        MatrixCase requiring(Filesystem filesystem) {
            return new MatrixCase(
                    name,
                    filesystem,
                    target,
                    baselineConfig,
                    earlierIdentity,
                    change,
                    written,
                    removed,
                    candidates,
                    rejected,
                    matchesFreshIndex);
        }

        MatrixCase reindexing(Path childTarget) {
            return new MatrixCase(
                    name,
                    requires,
                    childTarget,
                    baselineConfig,
                    earlierIdentity,
                    change,
                    written,
                    removed,
                    candidates,
                    rejected,
                    matchesFreshIndex);
        }

        MatrixCase withBaselineConfig(String config) {
            return new MatrixCase(
                    name,
                    requires,
                    target,
                    config,
                    earlierIdentity,
                    change,
                    written,
                    removed,
                    candidates,
                    rejected,
                    matchesFreshIndex);
        }

        MatrixCase withEarlierIdentity(Path sourcePath, UnaryOperator<ChunkingIdentity> identity) {
            return new MatrixCase(
                    name,
                    requires,
                    target,
                    baselineConfig,
                    new EarlierIdentity(sourcePath, identity),
                    change,
                    written,
                    removed,
                    candidates,
                    rejected,
                    matchesFreshIndex);
        }

        /** Rejected files keep their earlier records, which a fresh index would not contain. */
        MatrixCase rejecting(int count) {
            return new MatrixCase(
                    name,
                    requires,
                    target,
                    baselineConfig,
                    earlierIdentity,
                    change,
                    written,
                    removed,
                    candidates,
                    count,
                    false);
        }

        /** Newly excluded files keep their records by design, which a fresh index would not contain. */
        MatrixCase retainingStaleRecords() {
            return new MatrixCase(
                    name,
                    requires,
                    target,
                    baselineConfig,
                    earlierIdentity,
                    change,
                    written,
                    removed,
                    candidates,
                    rejected,
                    false);
        }

        IndexResult.Status status() {
            return rejected == 0 ? IndexResult.Status.COMPLETE : IndexResult.Status.PARTIAL;
        }

        int unchanged() {
            return candidates - written.size() - rejected;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
