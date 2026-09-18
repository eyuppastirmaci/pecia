package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqliteLexicalSnapshotTest {
    @TempDir
    Path root;

    @Test
    @Timeout(20)
    void keepsCandidateAndMetadataInOneSnapshotDuringConcurrentReplacement() throws Exception {
        Path database = root.resolve("index.db");
        try (var reader = SqliteStorage.open(database, root)) {
            try (var statement = reader.connection().createStatement();
                 var rows = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                assertTrue(rows.next());
                assertEquals("wal", rows.getString(1));
            }
            replace(reader, "old", new LineRange(1, 2));

            try (var writer = SqliteStorage.open(database, root)) {
                var candidatesRead = new CountDownLatch(1);
                var writerCommitted = new CountDownLatch(1);
                var executor = Executors.newSingleThreadExecutor();
                var update = executor.submit(() -> {
                    try {
                        await(candidatesRead);
                        replace(writer, "new", new LineRange(10, 20));
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

                    var oldHit = new SqliteLexicalSearch(coordinated).search(new SearchRequest("needle")).getFirst();
                    update.get(5, TimeUnit.SECONDS);

                    assertEquals("needle old", oldHit.snippet());
                    assertEquals(new LineRange(1, 2), oldHit.sourceLocation());
                    assertEquals(List.of("old heading"), oldHit.metadata().headingPath());
                    assertEquals(Map.of("generation", "old"), oldHit.metadata().attributes());

                    var newHit = reader.lexicalSearch().search(new SearchRequest("needle")).getFirst();
                    assertEquals("needle new", newHit.snippet());
                    assertEquals(new LineRange(10, 20), newHit.sourceLocation());
                    assertEquals(List.of("new heading"), newHit.metadata().headingPath());
                    assertEquals(Map.of("generation", "new"), newHit.metadata().attributes());
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

    private static void replace(SqliteStorage storage, String generation, LineRange lines) throws SQLException {
        Path path = Path.of("notes.txt");
        String content = "needle " + generation;
        var document = new Document(path, DocumentType.PLAIN_TEXT, content,
                ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
        storage.replaceFile(document, List.of(new Chunk(path, DocumentType.PLAIN_TEXT, 0, content, lines,
                new ChunkMetadata(List.of(generation + " heading"), Map.of("generation", generation)))));
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
