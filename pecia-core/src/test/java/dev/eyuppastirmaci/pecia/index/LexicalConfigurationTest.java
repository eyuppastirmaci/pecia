package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

class LexicalConfigurationTest {

    private static final Path DOCUMENT = Path.of("docs/calendar.txt");
    // Each line has four vocabulary tokens; no sentence or paragraph boundary takes priority.
    private static final String DOCUMENT_TEXT = """
        needle january one two
        needle february one two
        needle march one two
        needle april one two
        needle may one two
        needle june one two
        needle july one two
        needle august one two
        needle september one two
        needle october one two
        needle november one two
        needle december one two
        """;
    private static final List<LineRange> SMALL_RANGES =
            List.of(new LineRange(1, 3), new LineRange(4, 6), new LineRange(7, 9), new LineRange(10, 12));
    private static final List<LineRange> LARGE_RANGES = List.of(new LineRange(1, 6), new LineRange(7, 12));
    private static final List<LineRange> OVERLAPPING_RANGES = List.of(
            new LineRange(1, 3),
            new LineRange(3, 5),
            new LineRange(5, 7),
            new LineRange(7, 9),
            new LineRange(9, 11),
            new LineRange(11, 12));

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService indexService = new IndexService(loader);
    private final QueryService queryService = new QueryService(loader);
    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @BeforeEach
    void prepareProject() throws IOException {
        root = root.toRealPath();
        Files.createDirectory(root.resolve("docs"));
    }

    @ParameterizedTest
    @CsvSource({"26, 14", "14, 26"})
    void changingTokenBudgetRechunksUnchangedBytesAndRemovesObsoleteFtsRows(int before, int after) throws Exception {
        Files.writeString(root.resolve(DOCUMENT), DOCUMENT_TEXT);
        writeConfig(chunkConfig(before, 0));
        List<LineRange> originalRanges = before == 14 ? SMALL_RANGES : LARGE_RANGES;
        List<LineRange> updatedRanges = after == 14 ? SMALL_RANGES : LARGE_RANGES;
        assertCounts(indexService.index(root), 1, 1, 0, 0, originalRanges.size());
        IndexedFile original = snapshot().get(DOCUMENT);
        assertDocument(original, before, 0, originalRanges);
        assertUnchanged(1);

        writeConfig(chunkConfig(after, 0));
        IndexResult result = indexService.index(root);

        assertCounts(result, 1, 1, 0, 0, updatedRanges.size());
        assertTrue(result.context().loadedConfig().fromFile());
        assertEquals(after, result.context().loadedConfig().config().maxTokens());
        IndexedFile updated = snapshot().get(DOCUMENT);
        assertRechunked(original, updated);
        assertDocument(updated, after, 0, updatedRanges);
        assertEquals(DOCUMENT_TEXT, Files.readString(root.resolve(DOCUMENT)));
        assertUnchanged(1);
    }

    @ParameterizedTest
    @CsvSource({"0, 4", "4, 0"})
    void changingOverlapRebuildsBoundaryCoverageAndThenBecomesUnchanged(int before, int after) throws Exception {
        Files.writeString(root.resolve(DOCUMENT), DOCUMENT_TEXT);
        writeConfig(chunkConfig(14, before));
        List<LineRange> originalRanges = before == 0 ? SMALL_RANGES : OVERLAPPING_RANGES;
        List<LineRange> updatedRanges = after == 0 ? SMALL_RANGES : OVERLAPPING_RANGES;
        assertCounts(indexService.index(root), 1, 1, 0, 0, originalRanges.size());
        IndexedFile original = snapshot().get(DOCUMENT);
        assertDocument(original, 14, before, originalRanges);
        assertUnchanged(1);

        writeConfig(chunkConfig(14, after));
        IndexResult result = indexService.index(root);

        assertCounts(result, 1, 1, 0, 0, updatedRanges.size());
        assertEquals(14, result.context().loadedConfig().config().maxTokens());
        assertEquals(after, result.context().loadedConfig().config().overlapTokens());
        IndexedFile updated = snapshot().get(DOCUMENT);
        assertRechunked(original, updated);
        assertDocument(updated, 14, after, updatedRanges);
        assertEquals(DOCUMENT_TEXT, Files.readString(root.resolve(DOCUMENT)));
        assertUnchanged(1);
    }

    @Test
    void changingOnlyEmbeddingConcurrencyPreservesProfilesChunksAndSearchResults() throws Exception {
        Files.writeString(root.resolve(DOCUMENT), DOCUMENT_TEXT);
        writeConfig(chunkConfig(14, 4) + "\n[embed]\nconcurrency = 1\n");
        assertCounts(indexService.index(root), 1, 1, 0, 0, OVERLAPPING_RANGES.size());
        Map<Path, IndexedFile> original = snapshot();
        assertDocument(original.get(DOCUMENT), 14, 4, OVERLAPPING_RANGES);
        List<SearchHit> originalHits = search("needle");

        writeConfig(chunkConfig(14, 4) + "\n[embed]\nconcurrency = 4\n");
        IndexResult result = indexService.index(root);

        assertCounts(result, 1, 0, 1, 0, 0);
        assertEquals(4, result.context().loadedConfig().config().embedConcurrency());
        assertEquals(original, snapshot());
        assertEquals(originalHits, search("needle"));
        assertDocument(original.get(DOCUMENT), 14, 4, OVERLAPPING_RANGES);
        assertUnchanged(1);
    }

    @Test
    void discoveryChangesAdmitNewFilesAndRetainExcludedOrNoLongerIncludedRecords() throws Exception {
        for (String name : List.of("kept", "excluded", "narrowed", "new")) {
            Files.writeString(root.resolve(name + ".txt"), name + "needle\n");
        }
        writeConfig("""
            [index]
            include = ['kept.txt', 'excluded.txt', 'narrowed.txt']
            """);
        assertCounts(indexService.index(root), 3, 3, 0, 0, 3);
        Map<Path, IndexedFile> original = snapshot();
        assertEquals(Set.of(Path.of("kept.txt"), Path.of("excluded.txt"), Path.of("narrowed.txt")), original.keySet());
        assertTrue(search("newneedle").isEmpty());

        Files.writeString(root.resolve("excluded.txt"), "replacementneedle\n");
        writeConfig("""
            [index]
            include = ['**/*.txt']
            exclude = ['excluded.txt']
            """);
        assertCounts(indexService.index(root), 3, 1, 2, 0, 1);
        Map<Path, IndexedFile> expanded = snapshot();
        assertEquals(4, expanded.size());
        for (var entry : original.entrySet()) {
            assertEquals(entry.getValue(), expanded.get(entry.getKey()));
        }
        assertMarker("newneedle", "new.txt");
        assertMarker("excludedneedle", "excluded.txt");
        assertTrue(search("replacementneedle").isEmpty());
        assertFtsRows(expanded);
        assertUnchanged(3);

        // Missing files outside the new include scope must remain indexed too.
        Files.delete(root.resolve("narrowed.txt"));
        writeConfig("""
            [index]
            include = ['kept.txt', 'new.txt']
            exclude = ['excluded.txt']
            """);
        assertCounts(indexService.index(root), 2, 0, 2, 0, 0);
        assertEquals(expanded, snapshot());
        assertMarker("keptneedle", "kept.txt");
        assertMarker("newneedle", "new.txt");
        assertMarker("excludedneedle", "excluded.txt");
        assertMarker("narrowedneedle", "narrowed.txt");
        assertTrue(search("replacementneedle").isEmpty());
        assertUnchanged(2);

        writeConfig("""
            [index]
            include = ['**/*.txt']
            exclude = []
            """);
        assertCounts(indexService.index(root), 3, 1, 2, 1, 1);
        Map<Path, IndexedFile> readmitted = snapshot();
        assertEquals(Set.of(Path.of("kept.txt"), Path.of("new.txt"), Path.of("excluded.txt")), readmitted.keySet());
        assertEquals(expanded.get(Path.of("kept.txt")), readmitted.get(Path.of("kept.txt")));
        assertEquals(expanded.get(Path.of("new.txt")), readmitted.get(Path.of("new.txt")));
        assertMarker("replacementneedle", "excluded.txt");
        assertTrue(search("excludedneedle").isEmpty());
        assertTrue(search("narrowedneedle").isEmpty());
        assertFtsRows(readmitted);
        assertUnchanged(3);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "14, -1", "14, 14", "2, 0", "257, 0", "14, 12"})
    void invalidChunkConfigLeavesExistingIndexUntouchedAndAllowsValidRetry(int maxTokens, int overlapTokens)
            throws Exception {
        Files.writeString(root.resolve(DOCUMENT), DOCUMENT_TEXT);
        writeConfig(chunkConfig(14, 0));
        assertCounts(indexService.index(root), 1, 1, 0, 0, SMALL_RANGES.size());
        Map<Path, IndexedFile> original = snapshot();
        List<SearchHit> originalHits = search("needle");
        byte[] originalBytes = Files.readAllBytes(database());

        writeConfig(chunkConfig(maxTokens, overlapTokens));

        assertThrows(IllegalArgumentException.class, () -> indexService.index(root));
        assertArrayEquals(originalBytes, Files.readAllBytes(database()));
        assertEquals(original, snapshot());
        assertFtsRows(original);

        writeConfig(chunkConfig(14, 0));
        assertEquals(originalHits, search("needle"));
        assertUnchanged(1);
    }

    private void assertDocument(IndexedFile stored, int maxTokens, int overlapTokens, List<LineRange> ranges)
            throws Exception {
        assertEquals(DOCUMENT, stored.file().sourcePath());
        assertEquals(maxTokens, stored.identity().maxTokens());
        assertEquals(overlapTokens, stored.identity().overlapTokens());
        assertEquals(
                ranges,
                stored.chunks().stream()
                        .map(chunk -> chunk.chunk().sourceLocation())
                        .toList());
        assertEquals(ranges.size(), stableIds(stored).stream().distinct().count());
        List<String> lines = DOCUMENT_TEXT.lines().toList();
        int coveredEnd = 0;
        for (int index = 0; index < ranges.size(); index++) {
            LineRange range = ranges.get(index);
            String expected = lineSlice(lines, range.startLine() - 1, range.endLine());
            int start = lineSlice(lines, 0, range.startLine() - 1).length();
            int end = lineSlice(lines, 0, range.endLine()).length();
            Chunk chunk = stored.chunks().get(index).chunk();
            assertEquals(index, chunk.index());
            assertEquals(DOCUMENT, chunk.sourcePath());
            assertEquals(DocumentType.PLAIN_TEXT, chunk.documentType());
            assertEquals(expected, chunk.content());
            assertTrue(chunk.metadata().headingPath().isEmpty());
            assertEquals(
                    Map.of("startOffset", Integer.toString(start), "endOffset", Integer.toString(end)),
                    chunk.metadata().attributes());
            assertTrue(tokenizer.countModelInput(chunk.content()) <= maxTokens);
            assertTrue(start <= coveredEnd && end > coveredEnd);
            assertEquals(
                    overlapTokens == 0 || index == 0 ? 0 : 4,
                    tokenizer.count(DOCUMENT_TEXT.substring(start, coveredEnd)));
            coveredEnd = end;
        }
        assertEquals(DOCUMENT_TEXT.length(), coveredEnd);

        List<SearchHit> hits = search("needle").stream()
                .sorted(Comparator.comparingInt(SearchHit::chunkIndex))
                .toList();
        assertEquals(ranges.size(), hits.size());
        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            StoredChunk chunk = stored.chunks().get(index);
            assertEquals(index, hit.chunkIndex());
            assertEquals(DOCUMENT, hit.sourcePath());
            assertEquals(ranges.get(index), hit.sourceLocation());
            assertEquals(chunk.id(), hit.chunkId());
            assertEquals(chunk.stableId().orElseThrow(), hit.stableId().orElseThrow());
            assertEquals(chunk.chunk().content(), hit.snippet());
        }
        // Every month must occur in exactly the new ranges containing that source line.
        for (int line = 1; line <= lines.size(); line++) {
            String month = lines.get(line - 1).split(" ")[1];
            List<LineRange> expected = new ArrayList<>();
            for (LineRange range : ranges) {
                if (range.startLine() <= line && line <= range.endLine()) {
                    expected.add(range);
                }
            }
            assertEquals(
                    expected,
                    search(month).stream()
                            .sorted(Comparator.comparingInt(SearchHit::chunkIndex))
                            .map(SearchHit::sourceLocation)
                            .toList(),
                    month);
        }
        assertFtsRows(Map.of(DOCUMENT, stored));
    }

    private static void assertRechunked(IndexedFile original, IndexedFile updated) {
        assertEquals(original.file(), updated.file());
        assertNotEquals(original.identity(), updated.identity());
        assertNotEquals(original.identity().fingerprint(), updated.identity().fingerprint());
        assertEquals(original.identity().tokenizer(), updated.identity().tokenizer());
        assertEquals(original.identity().extractionVersion(), updated.identity().extractionVersion());
        assertEquals(original.identity().chunkingVersion(), updated.identity().chunkingVersion());
        assertNotEquals(
                original.chunks().stream()
                        .map(chunk -> chunk.chunk().sourceLocation())
                        .toList(),
                updated.chunks().stream()
                        .map(chunk -> chunk.chunk().sourceLocation())
                        .toList());
        assertTrue(Collections.disjoint(stableIds(original), stableIds(updated)));
    }

    private void assertUnchanged(int candidates) throws Exception {
        Map<Path, IndexedFile> before = snapshot();
        List<SearchHit> hits = search("needle");
        assertCounts(indexService.index(root), candidates, 0, candidates, 0, 0);
        assertEquals(before, snapshot());
        assertEquals(hits, search("needle"));
        assertFtsRows(before);
    }

    private void assertMarker(String query, String path) throws Exception {
        List<SearchHit> hits = search(query);
        assertEquals(1, hits.size(), query);
        assertEquals(Path.of(path), hits.getFirst().sourcePath());
    }

    private void assertFtsRows(Map<Path, IndexedFile> files) throws SQLException {
        Map<Long, List<String>> expected = new HashMap<>();
        for (IndexedFile file : files.values()) {
            for (StoredChunk chunk : file.chunks()) {
                expected.put(
                        chunk.id(),
                        List.of(
                                chunk.chunk().content(),
                                file.file().sourcePath().toString().replace('\\', '/')));
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        // Retrieval joins chunks, so an orphan FTS row can be invisible through QueryService alone.
        try (var connection = JDBC.createConnection(
                        "jdbc:sqlite:" + database().toUri().toASCIIString(), config.toProperties());
                var statement = connection.createStatement()) {
            Map<Long, List<String>> actual = new HashMap<>();
            try (var rows = statement.executeQuery("SELECT rowid, content, source_path FROM chunks_fts")) {
                while (rows.next()) {
                    actual.put(rows.getLong(1), List.of(rows.getString(2), rows.getString(3)));
                }
            }
            assertEquals(expected, actual);

            List<Long> matches = new ArrayList<>();
            try (var rows = statement.executeQuery(
                    "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'content : needle' ORDER BY rowid")) {
                while (rows.next()) {
                    matches.add(rows.getLong(1));
                }
            }
            List<Long> expectedMatches = files.containsKey(DOCUMENT)
                    ? files.get(DOCUMENT).chunks().stream()
                            .map(StoredChunk::id)
                            .sorted()
                            .toList()
                    : List.of();
            assertEquals(expectedMatches, matches);
        }
    }

    private Map<Path, IndexedFile> snapshot() throws IOException, SQLException {
        Map<Path, IndexedFile> files = new HashMap<>();
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database(), root)) {
            for (StoredFile file : storage.files().findAll()) {
                files.put(
                        file.sourcePath(),
                        new IndexedFile(
                                file,
                                storage.files().findChunkingIdentity(file.id()).orElseThrow(),
                                storage.chunks().findByFileId(file.id())));
            }
        }
        return Map.copyOf(files);
    }

    private List<SearchHit> search(String text) throws Exception {
        return queryService.search(root, new SearchRequest(text, SearchRequest.MAX_LIMIT));
    }

    private static List<ChunkId> stableIds(IndexedFile file) {
        return file.chunks().stream()
                .map(chunk -> chunk.stableId().orElseThrow())
                .toList();
    }

    private static String lineSlice(List<String> lines, int start, int end) {
        return String.join(
                "", lines.subList(start, end).stream().map(line -> line + "\n").toList());
    }

    private void writeConfig(String content) throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), content);
    }

    private static String chunkConfig(int maxTokens, int overlapTokens) {
        // The project TOML is itself a supported file; limit this fixture to its text document.
        return "[index]\ninclude = ['**/*.txt']\n\n[chunk]\nmax_tokens = " + maxTokens + "\noverlap_tokens = "
                + overlapTokens + "\n";
    }

    private Path database() {
        return root.resolve(".pecia/index.db");
    }

    private static void assertCounts(
            IndexResult result, int candidates, int indexed, int unchanged, int deleted, long chunks) {
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(candidates, result.candidateCount());
        assertEquals(indexed, result.indexedFiles());
        assertEquals(unchanged, result.unchangedFiles());
        assertEquals(deleted, result.deletedFiles());
        assertEquals(chunks, result.writtenChunks());
        assertTrue(result.issues().isEmpty());
    }

    private record IndexedFile(StoredFile file, ChunkingIdentity identity, List<StoredChunk> chunks) {
        private IndexedFile {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(identity, "identity");
            chunks = List.copyOf(chunks);
        }
    }
}
