package dev.eyuppastirmaci.pecia;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
    private static final List<String> INPUT_KINDS = List.of(CONTENT, CONFIG);
    private static final String PREVIOUS_VERSION = "payment validation before the hostile change\n";
    private static final String PROJECT_FILE = "payment.txt";
    private static final String QUERY_PROJECT_TEXT = "Payment validation runs near the quoted text, or not.\n";
    private static final int SIZE_LIMIT = 64;

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

    @Test
    void manifestEntriesAreUniqueAndMatchInputFiles() throws Exception {
        Set<String> listed = new TreeSet<>();
        Set<String> committed = new TreeSet<>();

        for (String kind : List.of(CONTENT, CONFIG, QUERY)) {
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
     * @param stage {@code load} or {@code indexing} for a rejected case
     * @param exception the simple class name of the expected exception
     * @param message a fragment of the expected exception message
     */
    record ConfigCase(
            String id, IndexResult.Status status, String stage, String exception, String message, byte[] bytes) {

        static ConfigCase from(TomlTable row) throws IOException {
            String id = required(row, "id");
            String outcome = required(row, "outcome");
            byte[] bytes = readResource(required(row, "input"));

            return switch (outcome) {
                case "accepted" -> {
                    assertNull(row.get("stage"), id + ": an accepted case has no stage");
                    var status = IndexResult.Status.valueOf(required(row, "status"));
                    yield new ConfigCase(id, status, null, null, null, bytes);
                }
                case "rejected" -> {
                    assertNull(row.get("status"), id + ": a rejected case has no status");
                    String stage = required(row, "stage");
                    assertTrue(stage.equals("load") || stage.equals("indexing"), id + ": unknown stage " + stage);
                    yield new ConfigCase(id, null, stage, required(row, "exception"), required(row, "message"), bytes);
                }
                default -> throw new AssertionError(id + ": unknown outcome " + outcome);
            };
        }

        boolean rejectedOnLoad() {
            return "load".equals(stage);
        }

        @Override
        public String toString() {
            return id;
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
}
