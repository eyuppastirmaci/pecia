package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteChunkIdentityBatchReadTest {

    private static final ChunkingIdentity IDENTITY = new ChunkingIdentity(
            "extraction-v1",
            "chunking-v1",
            new TokenizerCompatibility("wordpiece-v1", "a".repeat(64), 30522, 512, 2),
            256,
            32);
    private static final ChunkingIdentity OTHER_IDENTITY = new ChunkingIdentity(
            IDENTITY.extractionVersion(), "chunking-v2", IDENTITY.tokenizer(), IDENTITY.maxTokens(), 0);

    @TempDir
    Path root;

    @Test
    void returnsEveryRequestedFileWithItsOwnProfileAndCompleteImmutableIdentities() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile first = save(storage, "docs/İstanbul-😀.md", 3, IDENTITY);
            StoredFile second = save(storage, "docs/other.md", 2, OTHER_IDENTITY);
            StoredFile empty = save(storage, "empty.md", 0, IDENTITY);
            Document legacyDocument = document("legacy.md", 1);
            StoredFile legacy = storage.replaceFile(legacyDocument, chunks(legacyDocument, 1));
            var reader = new SqliteChunkIdentityReader(storage.connection());
            List<Long> ids = List.of(second.id(), legacy.id(), first.id(), empty.id(), Long.MAX_VALUE, first.id());

            Map<Long, SqliteChunkIdentityReader.State> states = storage.inScope(() -> reader.readForFiles(ids));

            assertEquals(Set.copyOf(ids), states.keySet());
            assertKnown(storage, states.get(first.id()), first, IDENTITY, 3);
            assertKnown(storage, states.get(second.id()), second, OTHER_IDENTITY, 2);
            assertKnown(storage, states.get(empty.id()), empty, IDENTITY, 0);
            assertEquals(new SqliteChunkIdentityReader.State(Optional.empty(), Map.of()), states.get(legacy.id()));
            assertEquals(states.get(legacy.id()), states.get(Long.MAX_VALUE));
            assertEquals(states.get(first.id()), storage.inScope(() -> reader.read(first.id())));
            assertThrows(UnsupportedOperationException.class, states::clear);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> states.get(first.id()).chunkIds().clear());
        }
    }

    @Test
    void usesConstantQueryCountAndDeduplicatesRequestsAcrossManyFiles() throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            List<Long> ids = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                ids.add(save(storage, "file-" + index + ".md", 2, IDENTITY).id());
            }
            AtomicInteger queries = new AtomicInteger();
            Connection connection = countingConnection(storage.connection(), queries);
            var reader = new SqliteChunkIdentityReader(connection);
            ids.addAll(List.copyOf(ids));

            Map<Long, SqliteChunkIdentityReader.State> states = storage.inScope(() -> reader.readForFiles(ids));

            assertEquals(12, states.size());
            assertEquals(3, queries.get());
            queries.set(0);
            storage.inScope(() -> reader.read(ids.getFirst()));
            assertEquals(3, queries.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"both", "declared", "actual"})
    void rejectsWrongOwnersEvenWhenOneOwnerIsOutsideTheRequestedBatch(String requestedOwner) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile actual = save(storage, "actual.md", 1, IDENTITY);
            StoredFile declared = save(storage, "declared.md", 1, OTHER_IDENTITY);
            StoredFile unrelated = save(storage, "unrelated.md", 1, IDENTITY);
            execute(storage.connection(), "PRAGMA foreign_keys = OFF");
            execute(
                    storage.connection(),
                    "UPDATE chunk_identities SET file_id = ? WHERE file_id = ?",
                    declared.id(),
                    actual.id());
            List<Long> requested =
                    switch (requestedOwner) {
                        case "both" -> List.of(actual.id(), declared.id(), unrelated.id());
                        case "actual" -> List.of(actual.id(), unrelated.id());
                        default -> List.of(declared.id(), unrelated.id());
                    };
            var reader = new SqliteChunkIdentityReader(storage.connection());

            assertThrows(SQLException.class, () -> storage.inScope(() -> reader.readForFiles(requested)));
            assertEquals(Optional.of(IDENTITY), storage.files().findChunkingIdentity(unrelated.id()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM chunk_identities WHERE file_id = ?",
                "UPDATE chunk_identities SET stable_id ="
                        + " 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' WHERE file_id = ?",
                "UPDATE file_chunking_profiles SET extraction_version = 'changed' WHERE file_id = ?"
            })
    void rejectsTheEntireBatchWhenOneFileHasIncompleteOrIncorrectIdentities(String damage) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile valid = save(storage, "valid.md", 2, IDENTITY);
            StoredFile damaged = save(storage, "damaged.md", 1, OTHER_IDENTITY);
            execute(storage.connection(), damage, damaged.id());
            var reader = new SqliteChunkIdentityReader(storage.connection());

            assertThrows(
                    SQLException.class,
                    () -> storage.inScope(() -> reader.readForFiles(List.of(valid.id(), damaged.id()))));
            assertEquals(Optional.of(IDENTITY), storage.files().findChunkingIdentity(valid.id()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM file_chunking_profiles WHERE file_id = ?",
                "DELETE FROM files WHERE id = ?",
                "UPDATE chunk_identities SET chunk_id = 999 WHERE file_id = ?",
                "UPDATE chunk_identities SET file_id = 999 WHERE file_id = ?"
            })
    void rejectsOrphansAlongsideValidRequestedFiles(String damage) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile valid = save(storage, "valid.md", 1, IDENTITY);
            StoredFile damaged = save(storage, "damaged.md", 1, IDENTITY);
            execute(storage.connection(), "PRAGMA foreign_keys = OFF");
            execute(storage.connection(), damage, damaged.id());
            var reader = new SqliteChunkIdentityReader(storage.connection());

            assertThrows(
                    SQLException.class,
                    () -> storage.inScope(() -> reader.readForFiles(List.of(valid.id(), damaged.id()))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "0.5", "4294967296", "'invalid'"})
    void validatesIndicesForEveryProfiledFileWithoutValidatingUnknownLegacyIndices(String index) throws Exception {
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            StoredFile valid = save(storage, "valid.md", 2, IDENTITY);
            StoredFile damaged = save(storage, "damaged.md", 1, IDENTITY);
            long chunkId =
                    storage.chunks().findByFileId(damaged.id()).getFirst().id();
            execute(storage.connection(), "UPDATE chunks SET chunk_index = " + index + " WHERE id = ?", chunkId);
            var reader = new SqliteChunkIdentityReader(storage.connection());
            List<Long> ids = List.of(valid.id(), damaged.id());

            var unknown = storage.inScope(() -> reader.readForFiles(ids));
            assertTrue(unknown.get(damaged.id()).identity().isEmpty());
            assertTrue(unknown.get(damaged.id()).chunkIds().isEmpty());

            storage.files().saveChunkingIdentity(damaged.id(), IDENTITY);
            execute(
                    storage.connection(),
                    "INSERT INTO chunk_identities VALUES (?, ?, ?)",
                    chunkId,
                    damaged.id(),
                    new ChunkIdGenerator(IDENTITY)
                            .generate(damaged.sourcePath(), 0)
                            .value());

            assertThrows(SQLException.class, () -> storage.inScope(() -> reader.readForFiles(ids)));
        }
    }

    @Test
    void readsBatchesWithoutChangingReadOnlyDatabaseBytes() throws Exception {
        Path database = root.resolve("index.db");
        StoredFile first;
        StoredFile second;
        Map<Long, SqliteChunkIdentityReader.State> expected;
        try (var storage = SqliteStorage.open(database, root)) {
            first = save(storage, "first.md", 2, IDENTITY);
            second = save(storage, "second.md", 3, OTHER_IDENTITY);
            expected = storage.inScope(() ->
                    new SqliteChunkIdentityReader(storage.connection()).readForFiles(List.of(first.id(), second.id())));
        }
        byte[] before = Files.readAllBytes(database);

        try (var storage = SqliteStorage.openReadOnly(database, root)) {
            assertEquals(
                    expected,
                    storage.inScope(() -> new SqliteChunkIdentityReader(storage.connection())
                            .readForFiles(List.of(second.id(), first.id()))));
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void validatesCompleteInputBeforeUsingClosedConnectionAndSkipsEmptyBatch() throws Exception {
        SqliteChunkIdentityReader reader;
        try (var storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            reader = new SqliteChunkIdentityReader(storage.connection());
        }

        assertThrows(NullPointerException.class, () -> reader.readForFiles(null));
        assertThrows(NullPointerException.class, () -> reader.readForFiles(Arrays.asList(1L, null)));
        for (long invalid : new long[] {Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> reader.readForFiles(List.of(1L, invalid)));
            assertThrows(IllegalArgumentException.class, () -> reader.read(invalid));
        }
        assertEquals(Map.of(), reader.readForFiles(List.of()));
    }

    private static void assertKnown(
            SqliteStorage storage,
            SqliteChunkIdentityReader.State state,
            StoredFile file,
            ChunkingIdentity identity,
            int chunkCount)
            throws SQLException {
        assertEquals(Optional.of(identity), state.identity());
        assertEquals(chunkCount, state.chunkIds().size());
        var generator = new ChunkIdGenerator(identity);
        for (var chunk : storage.chunks().findByFileId(file.id())) {
            assertEquals(
                    generator.generate(file.sourcePath(), chunk.chunk().index()),
                    state.chunkIds().get(chunk.id()));
        }
    }

    private static StoredFile save(SqliteStorage storage, String path, int chunkCount, ChunkingIdentity identity)
            throws SQLException {
        Document document = document(path, chunkCount);
        return storage.replaceFile(document, chunks(document, chunkCount), identity);
    }

    private static Document document(String path, int chunkCount) {
        String content = "chunk body\n".repeat(chunkCount);
        return new Document(
                Path.of(path),
                DocumentType.MARKDOWN,
                content,
                ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Chunk> chunks(Document document, int chunkCount) {
        List<Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < chunkCount; index++) {
            chunks.add(new Chunk(
                    document.sourcePath(),
                    document.type(),
                    index,
                    "chunk body",
                    new LineRange(index + 1, index + 1),
                    ChunkMetadata.empty()));
        }
        return List.copyOf(chunks);
    }

    private static Connection countingConnection(Connection connection, AtomicInteger queries) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        queries.incrementAndGet();
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
