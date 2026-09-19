package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LexicalLifecycleTest {

    private static final Path GUIDE = Path.of("docs/guide.md");
    private static final Path SOURCE = Path.of("src/Auth.java");
    private static final Path WEAK = Path.of("a-weak.txt");
    private static final Path STRONG = Path.of("z-strong.txt");
    private static final Path OTHER = Path.of("other.txt");
    private static final Path EMPTY = Path.of("empty.txt");
    private static final String ORIGINAL_GUIDE = "# Guide\n\nİstanbul café originalneedle 😀\n";
    private static final String UPDATED_GUIDE = "# Guide\n\nİstanbul café replacementneedle 😀\n";
    private static final String RECREATED_GUIDE = "# Guide\n\nİstanbul café recreatedneedle 😀\n";
    private static final String SOURCE_TEXT = "class Auth {\n    String JWT_SECRET;\n}\n";
    private static final String WEAK_TEXT = "needle filler filler filler\n";
    private static final String STRONG_TEXT = "needle needle needle filler\n";

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService indexService = new IndexService(loader);
    private final QueryService queryService = new QueryService(loader);

    @BeforeEach
    void createCorpus() throws IOException {
        root = root.toRealPath();
        Files.createDirectory(root.resolve("docs"));
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve(GUIDE), ORIGINAL_GUIDE);
        Files.writeString(root.resolve(SOURCE), SOURCE_TEXT);
        // Equal-length bodies and opposing path order make ranking observable after real ingestion.
        Files.writeString(root.resolve(WEAK), WEAK_TEXT);
        Files.writeString(root.resolve(STRONG), STRONG_TEXT);
        Files.writeString(root.resolve(OTHER), "unrelated material\n");
        Files.writeString(root.resolve(EMPTY), "");
    }

    @Test
    void indexesMixedCorpusAndPreservesStoredAndRankedResultsOnUnchangedRerun() throws Exception {
        IndexResult first = indexService.index(root);

        assertCounts(first, 6, 6, 0, 0, 5);
        Map<Path, FileSnapshot> initial = snapshot(first);
        assertEquals(Set.of(GUIDE, SOURCE, WEAK, STRONG, OTHER, EMPTY), initial.keySet());
        for (var entry : initial.entrySet()) {
            FileSnapshot file = entry.getValue();
            assertEquals(
                    entry.getKey().equals(EMPTY) ? 0 : 1,
                    file.chunks().size(),
                    entry.getKey().toString());
            for (StoredChunk chunk : file.chunks()) {
                assertEquals(file.file().id(), chunk.fileId());
                assertEquals(entry.getKey(), chunk.chunk().sourcePath());
                assertTrue(chunk.stableId().isPresent());
            }
        }
        assertEquals(DocumentType.PLAIN_TEXT, initial.get(EMPTY).file().documentType());
        assertEquals(
                5,
                initial.values().stream()
                        .flatMap(file -> file.chunks().stream())
                        .map(chunk -> chunk.stableId().orElseThrow())
                        .distinct()
                        .count());
        assertGuide("originalneedle", ORIGINAL_GUIDE, initial.get(GUIDE));
        assertSourceAndRanking(initial);
        Map<String, List<SearchHit>> originalQueries = querySnapshot();

        IndexResult repeated = indexService.index(root);

        assertCounts(repeated, 6, 0, 6, 0, 0);
        assertEquals(initial, snapshot(repeated));
        assertEquals(originalQueries, querySnapshot());
        assertGuide("originalneedle", ORIGINAL_GUIDE, initial.get(GUIDE));
        assertSourceAndRanking(initial);
    }

    @Test
    void editsDeletesAndRecreatesOneFileWithoutStaleHitsOrChangesToOtherFiles() throws Exception {
        IndexResult first = indexService.index(root);
        assertCounts(first, 6, 6, 0, 0, 5);
        Map<Path, FileSnapshot> initial = snapshot(first);
        assertGuide("originalneedle", ORIGINAL_GUIDE, initial.get(GUIDE));
        assertSourceAndRanking(initial);

        Files.writeString(root.resolve(GUIDE), UPDATED_GUIDE);
        IndexResult edited = indexService.index(root);

        assertCounts(edited, 6, 1, 5, 0, 1);
        Map<Path, FileSnapshot> afterEdit = snapshot(edited);
        assertEquals(initial.keySet(), afterEdit.keySet());
        assertOtherFilesUnchanged(initial, afterEdit);
        FileSnapshot editedGuide = afterEdit.get(GUIDE);
        assertNotEquals(
                initial.get(GUIDE).file().contentHash(), editedGuide.file().contentHash());
        // Stable IDs identify a position/profile; current bytes and hits establish freshness.
        assertEquals(
                initial.get(GUIDE).chunks().getFirst().stableId(),
                editedGuide.chunks().getFirst().stableId());
        assertGuide("replacementneedle", UPDATED_GUIDE, editedGuide);
        assertTrue(search("originalneedle").isEmpty());
        assertSourceAndRanking(afterEdit);

        Files.delete(root.resolve(GUIDE));
        IndexResult deleted = indexService.index(root);

        assertCounts(deleted, 5, 0, 5, 1, 0);
        Map<Path, FileSnapshot> afterDelete = snapshot(deleted);
        assertEquals(Set.of(SOURCE, WEAK, STRONG, OTHER, EMPTY), afterDelete.keySet());
        assertOtherFilesUnchanged(initial, afterDelete);
        try (SqliteStorage storage =
                SqliteStorage.openReadOnly(deleted.context().databasePath(), root)) {
            assertTrue(storage.files().findByPath(GUIDE).isEmpty());
            assertTrue(storage.chunks().findByFileId(editedGuide.file().id()).isEmpty());
        }
        for (String query : List.of("originalneedle", "replacementneedle", "İstanbul café", "Guide")) {
            assertTrue(search(query).isEmpty(), query);
        }
        assertSourceAndRanking(afterDelete);
        Map<String, List<SearchHit>> deletedQueries = querySnapshot();

        IndexResult repeatedDeletion = indexService.index(root);

        assertCounts(repeatedDeletion, 5, 0, 5, 0, 0);
        assertEquals(afterDelete, snapshot(repeatedDeletion));
        assertEquals(deletedQueries, querySnapshot());

        Files.writeString(root.resolve(GUIDE), RECREATED_GUIDE);
        IndexResult recreated = indexService.index(root);

        assertCounts(recreated, 6, 1, 5, 0, 1);
        Map<Path, FileSnapshot> afterRecreation = snapshot(recreated);
        assertEquals(initial.keySet(), afterRecreation.keySet());
        assertOtherFilesUnchanged(initial, afterRecreation);
        FileSnapshot recreatedGuide = afterRecreation.get(GUIDE);
        assertNotEquals(editedGuide.file().contentHash(), recreatedGuide.file().contentHash());
        assertEquals(
                initial.get(GUIDE).chunks().getFirst().stableId(),
                recreatedGuide.chunks().getFirst().stableId());
        assertGuide("recreatedneedle", RECREATED_GUIDE, recreatedGuide);
        assertTrue(search("originalneedle").isEmpty());
        assertTrue(search("replacementneedle").isEmpty());
        assertSourceAndRanking(afterRecreation);
        Map<String, List<SearchHit>> recreatedQueries = querySnapshot();

        IndexResult repeatedRecreation = indexService.index(root);

        assertCounts(repeatedRecreation, 6, 0, 6, 0, 0);
        assertEquals(afterRecreation, snapshot(repeatedRecreation));
        assertEquals(recreatedQueries, querySnapshot());
    }

    private void assertGuide(String marker, String text, FileSnapshot stored) throws Exception {
        List<SearchHit> hits = search(marker);
        assertEquals(1, hits.size());
        SearchHit guide = hits.getFirst();
        assertEquals(GUIDE, guide.sourcePath());
        assertEquals(DocumentType.MARKDOWN, guide.documentType());
        assertEquals(new LineRange(1, 3), guide.sourceLocation());
        assertEquals(List.of("Guide"), guide.metadata().headingPath());
        assertEquals(text, guide.snippet());
        assertEquals(1, stored.chunks().size());
        assertEquals(text, stored.chunks().getFirst().chunk().content());
        assertStoredIdentity(guide, stored);
        // Heading and Unicode terms must also retrieve exactly this chunk.
        for (String query : List.of("Guide", "İstanbul café")) {
            List<SearchHit> matches = search(query);
            assertEquals(1, matches.size(), query);
            assertEquals(GUIDE, matches.getFirst().sourcePath());
            assertStoredIdentity(matches.getFirst(), stored);
        }
    }

    private void assertSourceAndRanking(Map<Path, FileSnapshot> stored) throws Exception {
        List<SearchHit> sourceHits = search("JWT_SECRET");
        assertEquals(1, sourceHits.size());
        SearchHit source = sourceHits.getFirst();
        assertEquals(SOURCE, source.sourcePath());
        assertEquals(DocumentType.SOURCE_CODE, source.documentType());
        assertEquals(new LineRange(1, 3), source.sourceLocation());
        assertTrue(source.metadata().headingPath().isEmpty());
        assertEquals(SOURCE_TEXT, source.snippet());
        assertStoredIdentity(source, stored.get(SOURCE));

        List<SearchHit> ranked = search("needle");
        assertEquals(
                List.of(STRONG, WEAK),
                ranked.stream().map(SearchHit::sourcePath).toList());
        assertTrue(ranked.getFirst().score().value() < ranked.getLast().score().value());
        assertEquals(List.of(ranked.getFirst()), queryService.search(root, new SearchRequest("needle", 1)));
        assertEquals(STRONG_TEXT, ranked.getFirst().snippet());
        assertEquals(WEAK_TEXT, ranked.getLast().snippet());
        for (SearchHit hit : ranked) {
            assertEquals(DocumentType.PLAIN_TEXT, hit.documentType());
            assertEquals(new LineRange(1, 1), hit.sourceLocation());
            assertTrue(hit.metadata().headingPath().isEmpty());
            assertStoredIdentity(hit, stored.get(hit.sourcePath()));
        }
        List<SearchHit> other = search("unrelated");
        assertEquals(1, other.size());
        assertEquals(OTHER, other.getFirst().sourcePath());
        assertStoredIdentity(other.getFirst(), stored.get(OTHER));
    }

    private static void assertStoredIdentity(SearchHit hit, FileSnapshot stored) {
        StoredChunk chunk = stored.chunks().getFirst();
        assertEquals(chunk.id(), hit.chunkId());
        assertEquals(0, hit.chunkIndex());
        assertEquals(chunk.stableId().orElseThrow(), hit.stableId().orElseThrow());
        assertEquals(SearchScore.Kind.SQLITE_BM25, hit.score().kind());
        assertTrue(Double.isFinite(hit.score().value()));
    }

    private static void assertOtherFilesUnchanged(Map<Path, FileSnapshot> initial, Map<Path, FileSnapshot> current) {
        for (Path path : List.of(SOURCE, WEAK, STRONG, OTHER, EMPTY)) {
            assertEquals(initial.get(path), current.get(path), path.toString());
        }
    }

    private List<SearchHit> search(String text) throws Exception {
        return queryService.search(root, new SearchRequest(text));
    }

    private Map<String, List<SearchHit>> querySnapshot() throws Exception {
        Map<String, List<SearchHit>> results = new HashMap<>();
        for (String query : List.of(
                "originalneedle",
                "replacementneedle",
                "recreatedneedle",
                "İstanbul café",
                "Guide",
                "JWT_SECRET",
                "needle",
                "unrelated")) {
            results.put(query, search(query));
        }
        return Map.copyOf(results);
    }

    private Map<Path, FileSnapshot> snapshot(IndexResult result) throws IOException, SQLException {
        Map<Path, FileSnapshot> files = new HashMap<>();
        try (SqliteStorage storage = SqliteStorage.openReadOnly(result.context().databasePath(), root)) {
            for (StoredFile file : storage.files().findAll()) {
                files.put(
                        file.sourcePath(),
                        new FileSnapshot(file, storage.chunks().findByFileId(file.id())));
            }
        }
        return Map.copyOf(files);
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

    private record FileSnapshot(StoredFile file, List<StoredChunk> chunks) {}
}
