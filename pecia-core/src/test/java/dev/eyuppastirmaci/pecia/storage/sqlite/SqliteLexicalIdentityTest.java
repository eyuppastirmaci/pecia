package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteLexicalIdentityTest {
    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("wordpiece", "a".repeat(64), 100, 512, 2);
    private static final ChunkingIdentity IDENTITY = new ChunkingIdentity("extract-v1", "chunk-v1", TOKENIZER, 256, 32);
    private static final ChunkingIdentity UPDATED = new ChunkingIdentity("extract-v1", "chunk-v2", TOKENIZER, 128, 8);

    @TempDir
    Path root;

    @Test
    void exposesEveryStoredIdentityAcrossFilesAndReadOnlyReopening() throws Exception {
        Path database = root.resolve("index.db");
        List<SearchHit> expected;
        try (var storage = SqliteStorage.open(database, root)) {
            StoredFile first = save(storage, "docs/İstanbul.md", IDENTITY, "needle first", "needle second");
            StoredFile second = save(storage, "other.md", UPDATED, "needle third");
            List<StoredChunk> stored = new ArrayList<>(storage.chunks().findByFileId(first.id()));
            stored.addAll(storage.chunks().findByFileId(second.id()));

            expected = search(storage, "needle");

            assertEquals(3, expected.size());
            assertEquals(
                    3, expected.stream().map(SearchHit::stableId).distinct().count());
            for (SearchHit hit : expected) {
                StoredChunk chunk = stored.stream()
                        .filter(value -> value.id() == hit.chunkId())
                        .findFirst()
                        .orElseThrow();
                ChunkingIdentity identity = chunk.fileId() == first.id() ? IDENTITY : UPDATED;
                assertEquals(chunk.stableId(), hit.stableId());
                assertEquals(Optional.of(new ChunkIdGenerator(identity).generate(chunk.chunk())), hit.stableId());
                assertEquals(chunk.chunk().metadata(), hit.metadata());
                assertEquals(chunk.chunk().sourceLocation(), hit.sourceLocation());
                assertEquals(chunk.chunk().content(), hit.snippet());
            }
        }

        byte[] before = Files.readAllBytes(database);
        try (var storage = SqliteStorage.openReadOnly(database, root)) {
            assertEquals(expected, search(storage, "needle"));
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void plainAndLegacyProfiledChunksRemainSearchableWithUnknownIdentity(boolean legacy) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document("legacy.md", List.of("needle original"));
            List<Chunk> chunks = chunks(document, List.of(document.content()));
            if (legacy) {
                storage.replaceFile(document, chunks, new IndexingProfile("legacy:tokenizer", 256, 32));
            } else {
                storage.replaceFile(document, chunks);
            }
            save(storage, "known.md", IDENTITY, "needle known");

            List<SearchHit> hits = search(storage, "needle");

            assertEquals(2, hits.size());
            SearchHit legacyHit = hitAtPath(hits, "legacy.md");
            assertTrue(legacyHit.stableId().isEmpty());
            assertEquals("needle original", legacyHit.snippet());
            assertTrue(hitAtPath(hits, "known.md").stableId().isPresent());
        }
    }

    @Test
    void invalidatedMetadataRemainsSearchableWithoutStaleIdentity() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            save(storage, "notes.md", IDENTITY, "needle original", "needle sibling");
            SearchHit original = search(storage, "original").getFirst();

            execute(
                    storage.connection(),
                    "UPDATE chunk_attributes SET value = 'changed' WHERE chunk_id = ? AND name = 'section'",
                    original.chunkId());

            List<SearchHit> hits = search(storage, "needle");
            assertEquals(2, hits.size());
            assertTrue(hits.stream().allMatch(hit -> hit.stableId().isEmpty()));
            SearchHit changed = search(storage, "original").getFirst();
            assertEquals(original.chunkId(), changed.chunkId());
            assertEquals(original.snippet(), changed.snippet());
            assertEquals("changed", changed.metadata().attributes().get("section"));
        }
    }

    @Test
    void identityPresenceDoesNotChangeRankingScoresSnippetsMetadataOrLimit() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            save(storage, "a.md", IDENTITY, "needle filler filler filler");
            save(storage, "z.md", UPDATED, "needle needle needle filler");
            List<SearchHit> known = search(storage, "needle");
            assertEquals(Path.of("z.md"), known.getFirst().sourcePath());
            assertTrue(known.get(0).score().value() < known.get(1).score().value());
            assertEquals(List.of(known.getFirst()), storage.lexicalSearch().search(new SearchRequest("needle", 1)));

            execute(storage.connection(), "DELETE FROM file_chunking_profiles");

            List<SearchHit> unknown = search(storage, "needle");
            assertEquals(
                    known.stream()
                            .map(SqliteLexicalIdentityTest::withoutStableId)
                            .toList(),
                    unknown);
            assertEquals(List.of(unknown.getFirst()), storage.lexicalSearch().search(new SearchRequest("needle", 1)));
        }
    }

    @Test
    void replacementKeepsPositionIdentityWithFreshContentAndChangesItForANewProfile() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            save(storage, "notes.md", IDENTITY, "needle original");
            SearchHit original = search(storage, "needle").getFirst();

            save(storage, "notes.md", IDENTITY, "needle replacement");

            SearchHit replacement = search(storage, "needle").getFirst();
            assertEquals(original.stableId(), replacement.stableId());
            assertEquals("needle replacement", replacement.snippet());
            assertTrue(search(storage, "original").isEmpty());

            save(storage, "notes.md", UPDATED, "needle replacement");

            SearchHit reprofiled = search(storage, "needle").getFirst();
            assertNotEquals(replacement.stableId(), reprofiled.stableId());
            assertEquals(replacement.snippet(), reprofiled.snippet());
            assertEquals(
                    Optional.of(new ChunkIdGenerator(UPDATED).generate(Path.of("notes.md"), 0)), reprofiled.stableId());
        }
    }

    @Test
    void failedReplacementRestoresSearchContentMetadataAndIdentityTogether() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            save(storage, "notes.md", IDENTITY, "needle original", "needle sibling");
            List<SearchHit> before = search(storage, "needle");
            execute(storage.connection(), """
                CREATE TRIGGER injected_identity_failure BEFORE INSERT ON file_chunking_profiles
                BEGIN SELECT RAISE(ABORT, 'injected profile failure'); END
                """);

            assertThrows(SQLException.class, () -> save(storage, "notes.md", UPDATED, "needle replacement"));

            assertEquals(before, search(storage, "needle"));
            assertTrue(search(storage, "replacement").isEmpty());
            assertTrue(storage.connection().getAutoCommit());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fingerprint", "missing", "incorrect", "orphan"})
    void rejectsCorruptIdentityStateEvenWhenOnlyTheSiblingMatches(String corruption) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, "notes.md", IDENTITY, "needle selected", "unmatched sibling");
            long sibling = storage.chunks().findByFileId(file.id()).get(1).id();
            switch (corruption) {
                case "fingerprint" ->
                    execute(
                            storage.connection(),
                            "UPDATE file_chunking_profiles SET fingerprint = ? WHERE file_id = ?",
                            "b".repeat(64),
                            file.id());
                case "missing" ->
                    execute(storage.connection(), "DELETE FROM chunk_identities WHERE chunk_id = ?", sibling);
                case "incorrect" ->
                    execute(
                            storage.connection(),
                            "UPDATE chunk_identities SET stable_id = ? WHERE chunk_id = ?",
                            "c".repeat(64),
                            sibling);
                case "orphan" -> {
                    execute(storage.connection(), "PRAGMA foreign_keys = OFF");
                    execute(storage.connection(), "DELETE FROM file_chunking_profiles WHERE file_id = ?", file.id());
                }
                default -> throw new AssertionError(corruption);
            }

            assertThrows(SQLException.class, () -> search(storage, "needle"));
            SearchException failure = assertThrows(
                    SearchException.class, () -> storage.lexicalSearch().search(new SearchRequest("needle")));
            assertTrue(failure.getCause() instanceof SQLException);
            assertTrue(storage.connection().getAutoCommit());
            assertFalse(storage.connection().isClosed());
        }
    }

    @Test
    void rejectsIdentityOwnershipMismatchFromEitherAffectedFile() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile first = save(storage, "first.md", IDENTITY, "firstneedle");
            StoredFile second = save(storage, "second.md", IDENTITY, "secondneedle");
            execute(storage.connection(), "PRAGMA foreign_keys = OFF");
            execute(
                    storage.connection(),
                    "UPDATE chunk_identities SET file_id = ? WHERE file_id = ?",
                    second.id(),
                    first.id());

            assertThrows(SQLException.class, () -> search(storage, "firstneedle"));
            assertThrows(SQLException.class, () -> search(storage, "secondneedle"));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidStoredFileIdsRemainStorageFailuresAndPreserveTheCallersTransaction(long invalidId) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            Document document = document("notes.md", List.of("needle original"));
            StoredFile file = storage.replaceFile(document, chunks(document, List.of(document.content())));
            List<SearchHit> before = search(storage, "needle");
            execute(storage.connection(), "PRAGMA foreign_keys = OFF");
            execute(storage.connection(), "PRAGMA ignore_check_constraints = ON");
            storage.connection().setAutoCommit(false);
            execute(storage.connection(), "UPDATE files SET id = ? WHERE id = ?", invalidId, file.id());
            execute(storage.connection(), "UPDATE chunks SET file_id = ? WHERE file_id = ?", invalidId, file.id());

            assertThrows(SQLException.class, () -> new SqliteLexicalRetriever(storage).retrieve("\"needle\"", 10));
            assertThrows(SQLException.class, () -> search(storage, "needle"));
            SearchException failure = assertThrows(
                    SearchException.class, () -> storage.lexicalSearch().search(new SearchRequest("needle")));

            assertTrue(failure.getCause() instanceof SQLException);
            assertFalse(storage.connection().getAutoCommit());
            try (var statement = storage.connection().createStatement();
                    var rows = statement.executeQuery("SELECT id FROM files")) {
                assertTrue(rows.next());
                assertEquals(invalidId, rows.getLong(1));
            }
            storage.connection().rollback();
            storage.connection().setAutoCommit(true);
            assertEquals(before, search(storage, "needle"));
        }
    }

    @Test
    void doesNotValidateIdentityStateOfFilesOutsideTheSelectedResults() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            save(storage, "a.md", IDENTITY, "needle same");
            StoredFile other = save(storage, "z.md", IDENTITY, "needle same");
            SearchHit selected = storage.lexicalSearch()
                    .search(new SearchRequest("needle", 1))
                    .getFirst();
            assertEquals(Path.of("a.md"), selected.sourcePath());
            execute(storage.connection(), "DELETE FROM chunk_identities WHERE file_id = ?", other.id());

            assertEquals(List.of(selected), storage.lexicalSearch().search(new SearchRequest("needle", 1)));
            assertTrue(search(storage, "absent").isEmpty());
            assertThrows(SQLException.class, () -> search(storage, "needle"));
        }
    }

    @Test
    void identityReadFailurePreservesTheCallersTransactionAndCanBeRetried() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile file = save(storage, "notes.md", IDENTITY, "needle original");
            SearchHit original = search(storage, "needle").getFirst();
            storage.connection().setAutoCommit(false);
            save(storage, "earlier.md", IDENTITY, "earlier sentinel");
            List<SearchHit> expectedInTransaction = search(storage, "needle");
            execute(storage.connection(), "DELETE FROM chunk_identities WHERE file_id = ?", file.id());

            assertThrows(SQLException.class, () -> search(storage, "needle"));

            assertFalse(storage.connection().getAutoCommit());
            assertEquals(1, search(storage, "earlier").size());
            execute(
                    storage.connection(),
                    "INSERT INTO chunk_identities (chunk_id, file_id, stable_id) VALUES (?, ?, ?)",
                    original.chunkId(),
                    file.id(),
                    original.stableId().orElseThrow().value());
            assertEquals(expectedInTransaction, search(storage, "needle"));
            storage.connection().rollback();
            storage.connection().setAutoCommit(true);
            assertTrue(search(storage, "earlier").isEmpty());
            assertEquals(List.of(original), search(storage, "needle"));
        }
    }

    private static SearchHit hitAtPath(List<SearchHit> hits, String path) {
        return hits.stream()
                .filter(hit -> hit.sourcePath().equals(Path.of(path)))
                .findFirst()
                .orElseThrow();
    }

    private static SearchHit withoutStableId(SearchHit hit) {
        return new SearchHit(
                hit.chunkId(),
                hit.chunkIndex(),
                hit.sourcePath(),
                hit.documentType(),
                hit.sourceLocation(),
                hit.metadata(),
                hit.score(),
                hit.snippet());
    }

    private static List<SearchHit> search(SqliteStorage storage, String query) throws SQLException {
        return new SqliteLexicalRetriever(storage).search("\"" + query + "\"", SearchRequest.DEFAULT_LIMIT);
    }

    private static StoredFile save(SqliteStorage storage, String path, ChunkingIdentity identity, String... contents)
            throws SQLException {
        List<String> text = List.of(contents);
        Document document = document(path, text);
        return storage.replaceFile(document, chunks(document, text), identity);
    }

    private static Document document(String path, List<String> contents) {
        String content = String.join("\n", contents);
        return new Document(
                Path.of(path),
                DocumentType.MARKDOWN,
                content,
                ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document, List<String> contents) {
        List<Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            chunks.add(new Chunk(
                    document.sourcePath(),
                    document.type(),
                    index,
                    contents.get(index),
                    new LineRange(index + 1, index + 1),
                    new ChunkMetadata(List.of("Guide", "Section"), Map.of("section", Integer.toString(index)))));
        }
        return List.copyOf(chunks);
    }
}
