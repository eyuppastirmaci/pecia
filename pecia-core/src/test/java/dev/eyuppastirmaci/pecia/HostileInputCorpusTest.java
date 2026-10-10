package dev.eyuppastirmaci.pecia;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.index.IndexException;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LegacyIndexFixture;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.JDBC;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Runs the hostile-input corpus described in {@code hostile/README.md}: every case in {@code hostile/cases.toml} must
 * produce its recorded typed outcome end to end, and a rejected input must leave earlier records untouched.
 */
class HostileInputCorpusTest {

    private static final String RESOURCE_ROOT = "/hostile/";
    private static final String CONTENT = "content";
    private static final String CONFIG = "config";
    private static final String QUERY = "query";
    private static final String STORAGE = "storage";
    private static final List<String> INPUT_KINDS = List.of(CONTENT, CONFIG);
    private static final String PREVIOUS_VERSION = "payment validation before the hostile change\n";
    private static final String PROJECT_FILE = "payment.txt";
    private static final String QUERY_PROJECT_TEXT = "Payment validation runs near the quoted text, or not.\n";
    private static final int SIZE_LIMIT = 64;
    // Windows reserves device names with any extension, and these characters; Git refuses to check such paths out.
    private static final Pattern WINDOWS_INVALID_NAME =
            Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?|.*[<>:\"|?*\\\\].*|.*[ .]");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService indexService = new IndexService(loader);
    private final QueryService queryService = new QueryService(loader);

    static Stream<ContentCase> contentCases() throws IOException {
        return cases(CONTENT, ContentCase::from);
    }

    static Stream<ConfigCase> configCases() throws IOException {
        return cases(CONFIG, ConfigCase::from);
    }

    static Stream<QueryCase> queryCases() throws IOException {
        return cases(QUERY, QueryCase::from);
    }

    static Stream<StorageCase> storageCases() throws IOException {
        return cases(STORAGE, StorageCase::from);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentCases")
    void contentInputIsIndexedOrRejectedAsRecorded(ContentCase contentCase) throws Exception {
        Path root = project();
        Path file = root.resolve(contentCase.fileName());

        if (contentCase.rejected()) {
            // A rejected input must not replace the records of the file's last accepted version.
            Files.writeString(file, PREVIOUS_VERSION);
            IndexResult accepted = indexService.index(root);
            assertEquals(IndexResult.Status.COMPLETE, accepted.status());
            IndexDump before = dump(accepted, root);

            Files.write(file, contentCase.bytes());
            IndexResult result = indexService.index(root);

            assertEquals(IndexResult.Status.PARTIAL, result.status());
            assertEquals(0, result.indexedFiles());
            assertEquals(1, result.issues().size(), () -> result.issues().toString());
            IndexResult.FileIssue issue = result.issues().get(0);
            assertEquals(Path.of(contentCase.fileName()), issue.sourcePath());
            assertEquals(contentCase.reason(), issue.reason(), issue.message());
            assertEquals(before, dump(result, root), "A rejected input must retain the earlier records");
        } else {
            Files.write(file, contentCase.bytes());
            IndexResult result = indexService.index(root);

            assertEquals(
                    IndexResult.Status.COMPLETE,
                    result.status(),
                    () -> result.issues().toString());
            assertEquals(1, result.indexedFiles());
            assertEquals(contentCase.chunks(), result.writtenChunks());
            List<IndexDump.ChunkEntry> chunks = dump(result, root).chunks();
            assertEquals(contentCase.chunks(), chunks.size());
            for (IndexDump.ChunkEntry chunk : chunks) {
                assertFalse(chunk.content().contains("\uFEFF"), "Stored content must not keep a byte order mark");
            }
        }
    }

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @CsvSource({"64, COMPLETE", "65, PARTIAL"})
    void sizeLimitAcceptsTheExactLimitAndRejectsOneByteMore(int size, IndexResult.Status status) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Files.writeString(root.resolve(PeciaConfigFile.FILE_NAME), "[index]\nmax_file_bytes = " + SIZE_LIMIT + "\n");
        Files.writeString(root.resolve("sized.txt"), "x".repeat(size));

        IndexResult result = indexService.index(root);

        assertEquals(status, result.status(), () -> result.issues().toString());
        if (status == IndexResult.Status.COMPLETE) {
            assertEquals(1, result.indexedFiles());
        } else {
            assertEquals(0, result.indexedFiles());
            assertEquals(1, result.issues().size());
            assertEquals(
                    ExtractionException.Reason.TOO_LARGE, result.issues().get(0).reason());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configCases")
    void configurationIsAcceptedOrRejectedAsRecorded(ConfigCase configCase) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Files.write(root.resolve(PeciaConfigFile.FILE_NAME), configCase.bytes());
        Files.writeString(root.resolve(PROJECT_FILE), PREVIOUS_VERSION);

        if (configCase.status() != null) {
            IndexResult result = indexService.index(root);
            assertEquals(
                    configCase.status(), result.status(), () -> result.issues().toString());
            assertEquals(configCase.warnings(root.resolve(PeciaConfigFile.FILE_NAME)), result.warnings());
            return;
        }

        if (configCase.rejectedOnLoad()) {
            assertRejection(configCase, assertThrows(Exception.class, () -> loader.load(root)));
        } else {
            assertDoesNotThrow(() -> loader.load(root));
        }

        assertRejection(configCase, assertThrows(Exception.class, () -> indexService.index(root)));
        assertEquals(
                Set.of(PeciaConfigFile.FILE_NAME, PROJECT_FILE),
                entries(root),
                "Invalid configuration must fail before creating storage");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryCases")
    void queryIsSearchedAsLiteralTextOrRejectedAsRecorded(QueryCase queryCase) throws Exception {
        Path root = project();

        if (queryCase.message() != null) {
            // Without an index, a validation failure proves the query was rejected before the index was opened.
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> queryService.search(root, new SearchRequest(queryCase.query(), queryCase.limit())));
            assertTrue(failure.getMessage().contains(queryCase.message()), failure.getMessage());
            return;
        }

        Files.writeString(root.resolve(PROJECT_FILE), QUERY_PROJECT_TEXT);
        IndexResult indexed = indexService.index(root);
        assertEquals(IndexResult.Status.COMPLETE, indexed.status());
        IndexDump before = IndexDump.read(indexed.context().databasePath(), root, List.of());
        SearchRequest request = new SearchRequest(queryCase.query(), queryCase.limit());

        // Query syntax must be matched as literal words and never fail as SQLite or FTS syntax.
        List<SearchHit> hits = assertDoesNotThrow(() -> queryService.search(root, request));

        assertEquals(queryCase.hits(), hits.size());
        assertEquals(before, IndexDump.read(indexed.context().databasePath(), root, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storageCases")
    void hostileIndexStorageIsClassifiedAndLeftUnchanged(StorageCase storageCase) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Path database = storageCase.install(root);
        StorageSnapshot before = StorageSnapshot.of(database);

        QueryException query =
                assertThrows(QueryException.class, () -> queryService.search(root, new SearchRequest("payment")));
        assertEquals(storageCase.query(), query.reason(), query.getMessage());
        assertEquals(before, StorageSnapshot.of(database), "A query must never change the index");

        switch (storageCase.indexing()) {
            case "COMPLETE" ->
                assertEquals(
                        IndexResult.Status.COMPLETE, indexService.index(root).status());
            case "IOException" -> assertThrows(IOException.class, () -> indexService.index(root));
            default -> {
                IndexException indexing = assertThrows(IndexException.class, () -> indexService.index(root));
                assertEquals(IndexResult.Status.FAILED, indexing.result().status());
                IndexAccessException cause = assertInstanceOf(IndexAccessException.class, indexing.getCause());
                assertEquals(IndexAccessException.Reason.valueOf(storageCase.indexing()), cause.reason());
            }
        }

        if (!storageCase.indexing().equals("COMPLETE")) {
            assertEquals(before, StorageSnapshot.of(database), "A rejected index must keep its exact state");
        }
    }

    /**
     * Another connection holds an exclusive lock while indexing and querying run in parallel; both must fail with a
     * classified busy error after the SQLite busy timeout and leave the index unchanged.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void lockedIndexFailsIndexingAndQueryingAfterTheBusyTimeout() throws Exception {
        Path root = project();
        Files.writeString(root.resolve(PROJECT_FILE), PREVIOUS_VERSION);
        IndexResult indexed = indexService.index(root);
        Path database = indexed.context().databasePath();
        IndexDump before = IndexDump.read(database, root, List.of());
        // A change gives the locked run something to write.
        Files.writeString(root.resolve(PROJECT_FILE), "payment validation changed while locked\n");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (Connection holder =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                Statement statement = holder.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");
            Future<IndexException> indexing =
                    executor.submit(() -> assertThrows(IndexException.class, () -> indexService.index(root)));
            Future<QueryException> query = executor.submit(() ->
                    assertThrows(QueryException.class, () -> queryService.search(root, new SearchRequest("payment"))));

            IndexException indexFailure = indexing.get(30, TimeUnit.SECONDS);
            QueryException queryFailure = query.get(30, TimeUnit.SECONDS);
            statement.execute("ROLLBACK");

            assertEquals(IndexResult.Status.FAILED, indexFailure.result().status());
            assertBusy(indexFailure.getCause());
            assertEquals(QueryException.Reason.READ_FAILED, queryFailure.reason());
            assertBusy(queryFailure.getCause());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(before, IndexDump.read(database, root, List.of()), "A locked run must not write");
        assertEquals(IndexResult.Status.COMPLETE, indexService.index(root).status());
    }

    @Test
    void manifestEntriesAreUniqueAndMatchInputFiles() throws Exception {
        Set<String> listed = new TreeSet<>();
        Set<String> committed = new TreeSet<>();

        for (String kind : List.of(CONTENT, CONFIG, QUERY, STORAGE)) {
            Set<String> ids = new HashSet<>();
            for (TomlTable row : rows(kind)) {
                assertTrue(ids.add(required(row, "id")), () -> "Duplicate " + kind + " case: " + row.toJson());
            }
        }

        for (String kind : INPUT_KINDS) {
            for (TomlTable row : rows(kind)) {
                listed.add(required(row, "input"));
            }

            for (String name : entries(resourceDirectory().resolve(kind))) {
                assertFalse(WINDOWS_INVALID_NAME.matcher(name).matches(), "Not a valid Windows file name: " + name);
                committed.add(kind + "/" + name);
            }
        }

        assertEquals(committed, listed, "Every input needs exactly one manifest entry");
    }

    @Test
    void inputsKeepTheirRecordedBytes() throws Exception {
        for (String kind : INPUT_KINDS) {
            for (TomlTable row : rows(kind)) {
                String input = required(row, "input");
                assertEquals(required(row, "sha256"), sha256(readResource(input)), input);
            }
        }
    }

    private Path project() throws IOException {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        // A repository marker pins the project root without a configuration file, so defaults apply.
        Files.createDirectory(root.resolve(".git"));

        return root;
    }

    private static void assertRejection(ConfigCase configCase, Exception failure) {
        assertEquals(configCase.exception(), failure.getClass().getSimpleName(), () -> String.valueOf(failure));
        assertTrue(failure.getMessage().contains(configCase.message()), failure.getMessage());
    }

    private static void assertBusy(Throwable failure) {
        // SQLITE_BUSY is primary result code 5; extended codes keep it in the low byte.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() & 0xff) == 5) {
                return;
            }
        }

        throw new AssertionError("Expected a SQLITE_BUSY cause", failure);
    }

    private static IndexDump dump(IndexResult result, Path root) throws Exception {
        return IndexDump.read(result.context().databasePath(), root, List.of("payment"));
    }

    private static Set<String> entries(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    private static <T> Stream<T> cases(String kind, ManifestReader<T> reader) throws IOException {
        List<T> cases = new ArrayList<>();
        for (TomlTable row : rows(kind)) {
            cases.add(reader.read(row));
        }

        return cases.stream();
    }

    private static List<TomlTable> rows(String kind) throws IOException {
        TomlParseResult manifest = Toml.parse(new String(readResource("cases.toml"), StandardCharsets.UTF_8));
        assertFalse(manifest.hasErrors(), () -> "Invalid hostile manifest: " + manifest.errors());
        TomlArray array = manifest.getArray(kind);
        assertNotNull(array, "Missing " + kind + " cases");

        List<TomlTable> rows = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            rows.add(array.getTable(index));
        }

        return rows;
    }

    private static String required(TomlTable row, String key) {
        String value = row.getString(key);
        assertNotNull(value, () -> "Hostile case is missing " + key + ": " + row.toJson());

        return value;
    }

    private static byte[] readResource(String name) throws IOException {
        try (var input = HostileInputCorpusTest.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            assertNotNull(input, "Missing hostile resource: " + name);

            return input.readAllBytes();
        }
    }

    private static Path resourceDirectory() throws URISyntaxException {
        var url = HostileInputCorpusTest.class.getResource(RESOURCE_ROOT);
        assertNotNull(url, "Missing hostile resource directory");

        return Path.of(url.toURI());
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * One content case from the manifest.
     *
     * @param reason the expected rejection reason, or null for an indexed case
     * @param chunks the expected chunk count of an indexed case
     */
    record ContentCase(String id, String input, ExtractionException.Reason reason, long chunks, byte[] bytes) {

        static ContentCase from(TomlTable row) throws IOException {
            String id = required(row, "id");
            String input = required(row, "input");
            String outcome = required(row, "outcome");
            byte[] bytes = readResource(input);

            return switch (outcome) {
                case "indexed" -> {
                    assertNull(row.get("reason"), id + ": an indexed case has no reason");
                    Long chunks = row.getLong("chunks");
                    assertNotNull(chunks, id + ": an indexed case needs a chunk count");
                    yield new ContentCase(id, input, null, chunks, bytes);
                }
                case "rejected" -> {
                    assertNull(row.get("chunks"), id + ": a rejected case has no chunk count");
                    var reason = ExtractionException.Reason.valueOf(required(row, "reason"));
                    yield new ContentCase(id, input, reason, 0, bytes);
                }
                default -> throw new AssertionError(id + ": unknown outcome " + outcome);
            };
        }

        boolean rejected() {
            return reason != null;
        }

        String fileName() {
            return Path.of(input).getFileName().toString();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * One configuration case from the manifest.
     *
     * @param status the expected indexing status of an accepted case, or null for a rejected case
     * @param expectedWarnings the warnings an accepted case must produce, in order; empty when it must produce none
     * @param stage {@code load} or {@code indexing} for a rejected case
     * @param exception the simple class name of the expected exception
     * @param message a fragment of the expected exception message
     */
    record ConfigCase(
            String id,
            IndexResult.Status status,
            List<ExpectedWarning> expectedWarnings,
            String stage,
            String exception,
            String message,
            byte[] bytes) {

        static ConfigCase from(TomlTable row) throws IOException {
            String id = required(row, "id");
            String outcome = required(row, "outcome");
            byte[] bytes = readResource(required(row, "input"));

            return switch (outcome) {
                case "accepted" -> {
                    assertNull(row.get("stage"), id + ": an accepted case has no stage");
                    var status = IndexResult.Status.valueOf(required(row, "status"));
                    yield new ConfigCase(id, status, ExpectedWarning.list(row), null, null, null, bytes);
                }
                case "rejected" -> {
                    assertNull(row.get("status"), id + ": a rejected case has no status");
                    assertNull(row.get("warnings"), id + ": a rejected configuration produces no warnings");
                    String stage = required(row, "stage");
                    assertTrue(stage.equals("load") || stage.equals("indexing"), id + ": unknown stage " + stage);
                    yield new ConfigCase(
                            id, null, List.of(), stage, required(row, "exception"), required(row, "message"), bytes);
                }
                default -> throw new AssertionError(id + ": unknown outcome " + outcome);
            };
        }

        boolean rejectedOnLoad() {
            return "load".equals(stage);
        }

        /** Returns the warnings an accepted case must produce for the configuration file at {@code source}. */
        List<Warning> warnings(Path source) {
            return expectedWarnings.stream()
                    .map(expected -> new Warning(
                            Warning.Code.valueOf(expected.code()),
                            source,
                            new Warning.Position(expected.line(), expected.column()),
                            expected.message()))
                    .toList();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** A warning listed for a configuration case; its source is the case's configuration file. */
    record ExpectedWarning(String code, int line, int column, String message) {

        static List<ExpectedWarning> list(TomlTable row) {
            TomlArray array = row.getArray("warnings");
            List<ExpectedWarning> warnings = new ArrayList<>();

            for (int index = 0; array != null && index < array.size(); index++) {
                TomlTable warning = array.getTable(index);
                Long line = warning.getLong("line");
                Long column = warning.getLong("column");
                assertNotNull(line, () -> "Expected warning needs a line: " + warning.toJson());
                assertNotNull(column, () -> "Expected warning needs a column: " + warning.toJson());
                warnings.add(new ExpectedWarning(
                        required(warning, "code"),
                        Math.toIntExact(line),
                        Math.toIntExact(column),
                        required(warning, "message")));
            }

            return List.copyOf(warnings);
        }
    }

    /**
     * One query case from the manifest.
     *
     * @param hits the expected hit count of an accepted case, or null for a rejected case
     * @param message a fragment of the expected validation message, or null for an accepted case
     */
    record QueryCase(String id, String query, int limit, Integer hits, String message) {

        static QueryCase from(TomlTable row) {
            String id = required(row, "id");
            String outcome = required(row, "outcome");
            String query = row.contains("query") ? required(row, "query") : generated(row);
            Long limit = row.getLong("limit");
            int requestLimit = limit == null ? SearchRequest.DEFAULT_LIMIT : Math.toIntExact(limit);

            return switch (outcome) {
                case "accepted" -> {
                    Long hits = row.getLong("hits");
                    assertNotNull(hits, id + ": an accepted case needs a hit count");
                    yield new QueryCase(id, query, requestLimit, Math.toIntExact(hits), null);
                }
                case "rejected" -> {
                    assertNull(row.get("hits"), id + ": a rejected case has no hit count");
                    yield new QueryCase(id, query, requestLimit, null, required(row, "message"));
                }
                default -> throw new AssertionError(id + ": unknown outcome " + outcome);
            };
        }

        // Long queries are generated so the manifest stays readable.
        private static String generated(TomlTable row) {
            Long times = row.getLong("times");
            assertNotNull(times, () -> "A generated query needs times: " + row.toJson());
            String separator = row.getString("separator", () -> "");

            return String.join(separator, Collections.nCopies(Math.toIntExact(times), required(row, "repeat")));
        }

        @Override
        public String toString() {
            return id;
        }
    }

    @FunctionalInterface
    private interface ManifestReader<T> {

        T read(TomlTable row) throws IOException;
    }

    /**
     * One storage case from the manifest.
     *
     * @param damage the damage applied to the current-version fixture, or null for a generated source
     * @param indexing {@code COMPLETE}, {@code IOException}, or the expected {@link IndexAccessException.Reason}
     */
    record StorageCase(
            String id, String source, LegacyIndexFixture.Damage damage, QueryException.Reason query, String indexing) {

        static StorageCase from(TomlTable row) {
            String id = required(row, "id");
            String source = required(row, "source");
            LegacyIndexFixture.Damage damage =
                    source.equals("fixture") ? LegacyIndexFixture.Damage.valueOf(required(row, "damage")) : null;

            return new StorageCase(
                    id,
                    source,
                    damage,
                    QueryException.Reason.valueOf(required(row, "query")),
                    required(row, "indexing"));
        }

        Path install(Path root) throws IOException, SQLException {
            if (damage != null) {
                return LegacyIndexFixture.installDamaged(damage, root);
            }

            Files.createDirectory(root.resolve(".git"));
            Files.writeString(root.resolve(PROJECT_FILE), PREVIOUS_VERSION);
            Path database = root.resolve(PeciaConfig.defaults().storePath());
            Files.createDirectories(database.getParent());

            switch (source) {
                case "text-file" -> Files.writeString(database, "this is not a database\n");
                case "header-only" ->
                    Files.write(database, Arrays.copyOf("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII), 100));
                case "empty-file" -> Files.write(database, new byte[0]);
                // VACUUM writes a database header without creating any schema object.
                case "empty-database" -> execute(database, "VACUUM");
                case "foreign-database" -> execute(database, "CREATE TABLE notes(text TEXT)");
                case "directory" -> Files.createDirectory(database);
                default -> throw new AssertionError(id + ": unknown source " + source);
            }

            return database;
        }

        private static void execute(Path database, String sql) throws SQLException {
            try (Connection connection = JDBC.createConnection(
                            "jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** The checksum of an index file, or a directory's entries, together with the entries beside it. */
    private record StorageSnapshot(String checksum, Set<String> directoryEntries, Set<String> siblings) {

        static StorageSnapshot of(Path database) throws IOException, NoSuchAlgorithmException {
            Set<String> siblings = entries(database.getParent());

            if (Files.isDirectory(database)) {
                return new StorageSnapshot(null, entries(database), siblings);
            }

            return new StorageSnapshot(HostileInputCorpusTest.sha256(Files.readAllBytes(database)), null, siblings);
        }
    }
}
