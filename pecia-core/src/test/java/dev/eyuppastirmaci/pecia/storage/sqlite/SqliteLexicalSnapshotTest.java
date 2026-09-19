package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SqliteLexicalSnapshotTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    @Timeout(20)
    void keepsCandidateMetadataAndIdentityInOneSnapshotDuringConcurrentReplacement(
            boolean knownBefore, boolean knownAfter) throws Exception {
        Path database = root.resolve("index.db");
        try (var reader = SqliteStorage.open(database, root)) {
            try (var statement = reader.connection().createStatement();
                    var rows = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(rows.next());
                assertEquals("wal", rows.getString(1));
            }
            replace(reader, "old", new LineRange(1, 2), knownBefore);

            try (var writer = SqliteStorage.open(database, root)) {
                var candidatesRead = new CountDownLatch(1);
                var writerCommitted = new CountDownLatch(1);
                var executor = Executors.newSingleThreadExecutor();
                var update = executor.submit(() -> {
                    try {
                        await(candidatesRead);
                        replace(writer, "new", new LineRange(10, 20), knownAfter);
                    } finally {
                        writerCommitted.countDown();
                    }
                    return null;
                });

                try {
                    var metadata = new SqliteChunkMetadataReader(reader.connection());
                    var coordinated = new SqliteLexicalRetriever(reader, ids -> {
                        candidatesRead.countDown();
                        await(writerCommitted);
                        return metadata.readForChunks(ids);
                    });

                    var oldHit = new SqliteLexicalSearch(coordinated)
                            .search(new SearchRequest("needle"))
                            .getFirst();
                    update.get(5, TimeUnit.SECONDS);

                    assertEquals("needle old", oldHit.snippet());
                    assertEquals(new LineRange(1, 2), oldHit.sourceLocation());
                    assertEquals(List.of("old heading"), oldHit.metadata().headingPath());
                    assertEquals(Map.of("generation", "old"), oldHit.metadata().attributes());
                    assertEquals(expectedId(knownBefore, "old"), oldHit.stableId());

                    var newHit = reader.lexicalSearch()
                            .search(new SearchRequest("needle"))
                            .getFirst();
                    assertEquals("needle new", newHit.snippet());
                    assertEquals(new LineRange(10, 20), newHit.sourceLocation());
                    assertEquals(List.of("new heading"), newHit.metadata().headingPath());
                    assertEquals(Map.of("generation", "new"), newHit.metadata().attributes());
                    assertEquals(expectedId(knownAfter, "new"), newHit.stableId());
                    assertTrue(reader.connection().getAutoCommit());
                } finally {
                    candidatesRead.countDown();
                    writerCommitted.countDown();
                    update.cancel(true);
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static void replace(SqliteStorage storage, String generation, LineRange lines, boolean known)
            throws SQLException {
        Path path = Path.of("notes.txt");
        String content = "needle " + generation;
        var document = new Document(
                path, DocumentType.PLAIN_TEXT, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
        var chunks = List.of(new Chunk(
                path,
                DocumentType.PLAIN_TEXT,
                0,
                content,
                lines,
                new ChunkMetadata(List.of(generation + " heading"), Map.of("generation", generation))));

        if (known) {
            storage.replaceFile(document, chunks, identity(generation));
        } else {
            storage.replaceFile(document, chunks);
        }
    }

    private static Optional<ChunkId> expectedId(boolean known, String generation) {
        return known
                ? Optional.of(new ChunkIdGenerator(identity(generation)).generate(Path.of("notes.txt"), 0))
                : Optional.empty();
    }

    private static ChunkingIdentity identity(String generation) {
        return new ChunkingIdentity(
                "extract-v1",
                "chunk-" + generation,
                new TokenizerCompatibility("wordpiece", "a".repeat(64), 100, 512, 2),
                128,
                16);
    }

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("Timed out waiting for snapshot test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("Snapshot test interrupted", interrupted);
        }
    }
}
