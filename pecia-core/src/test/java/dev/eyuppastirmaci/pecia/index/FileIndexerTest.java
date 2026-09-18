package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileIndexerTest {

    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"notes.txt,PLAIN_TEXT", "guide.md,MARKDOWN", "Auth.java,SOURCE_CODE", "settings.yaml,STRUCTURED_TEXT"})
    void indexesEachFamilyWithRawByteHashAndSourceMetadata(String name, DocumentType type) throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "");
        Path child = Files.createDirectory(root.resolve("ödeme"));
        String text = type == DocumentType.MARKDOWN
                ? "# Guide\r\n\r\nneedle İstanbul 😀\r\n"
                : "needle İstanbul 😀\r\nsecond line\r\n";
        byte[] bytes = ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8);
        Files.write(child.resolve(name), bytes);
        ProjectContext context = context(child);

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), context.projectRoot())) {
            FileIndexer.Result result = new FileIndexer(context).index(Path.of(name), storage);

            assertEquals(Path.of("ödeme").resolve(name), result.file().sourcePath());
            assertEquals(type, result.file().documentType());
            assertEquals(ContentHash.sha256(bytes), result.file().contentHash());

            List<StoredChunk> chunks =
                    storage.chunks().findByFileId(result.file().id());

            assertEquals(result.chunkCount(), chunks.size());
            assertFalse(chunks.isEmpty());

            List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest("needle"));

            assertEquals(1, hits.size());
            assertEquals(result.file().sourcePath(), hits.getFirst().sourcePath());
            assertTrue(hits.getFirst().snippet().contains("İstanbul 😀"));
            assertEquals(
                    type == DocumentType.MARKDOWN ? new LineRange(1, 3) : new LineRange(1, 2),
                    hits.getFirst().sourceLocation());

            if (type == DocumentType.MARKDOWN) {
                assertEquals(List.of("Guide"), hits.getFirst().metadata().headingPath());
            }

            assertFalse(chunks.getFirst().chunk().content().startsWith("\uFEFF"));
        }
    }

    @Test
    void explicitlyIncludedUnknownExtensionUsesTextFallback() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['*.custom']\n");
        Files.writeString(root.resolve("notes.custom"), "fallbackneedle");
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        List<Path> candidates =
                new IndexService(loader).preview(root).walkResult().files();

        assertEquals(List.of(Path.of("notes.custom")), candidates);

        ProjectContext context = context(root);

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer.Result result = new FileIndexer(context).index(candidates.getFirst(), storage);

            assertEquals(DocumentType.PLAIN_TEXT, result.file().documentType());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("fallbackneedle"))
                            .size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \r\n\t"})
    void replacementAndEmptyContentDoNotAccumulateChunks(String empty) throws Exception {
        Path path = root.resolve("notes.txt");
        ProjectContext context = context(root);
        FileIndexer indexer = new FileIndexer(context);

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), root)) {
            Files.writeString(path, "oldneedle");
            FileIndexer.Result first = indexer.index(Path.of("notes.txt"), storage);
            Files.writeString(path, "newneedle");
            FileIndexer.Result second = indexer.index(Path.of("notes.txt"), storage);

            assertEquals(first.file().id(), second.file().id());
            FileIndexer.Result repeated = indexer.index(Path.of("notes.txt"), storage);
            assertEquals(second.file(), repeated.file());
            assertTrue(repeated.unchanged());
            assertEquals(0, repeated.chunkCount());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("oldneedle"))
                    .isEmpty());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("newneedle"))
                            .size());

            Files.writeString(path, empty);
            FileIndexer.Result cleared = indexer.index(Path.of("notes.txt"), storage);

            assertEquals(0, cleared.chunkCount());
            assertEquals(
                    ContentHash.sha256(empty.getBytes(StandardCharsets.UTF_8)),
                    cleared.file().contentHash());
            assertTrue(storage.chunks().findByFileId(first.file().id()).isEmpty());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("newneedle"))
                    .isEmpty());
            assertEquals(1, storage.files().findAll().size());

            Files.writeString(root.resolve("empty.txt"), empty);

            assertEquals(0, indexer.index(Path.of("empty.txt"), storage).chunkCount());
            assertEquals(2, storage.files().findAll().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_UTF8", "BINARY_CONTENT", "TOO_LARGE", "READ_FAILED"})
    void extractionFailuresPreserveOldDataAndTypedReason(String reason) throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\nmax_file_bytes = 32\n");
        Path path = Files.writeString(root.resolve("notes.txt"), "oldneedle");
        ProjectContext context = context(root);
        FileIndexer indexer = new FileIndexer(context);

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer.Result before = indexer.index(Path.of("notes.txt"), storage);
            List<StoredChunk> chunks =
                    storage.chunks().findByFileId(before.file().id());

            switch (reason) {
                case "INVALID_UTF8" -> Files.write(path, new byte[] {(byte) 0xc3, 0x28});
                case "BINARY_CONTENT" -> Files.writeString(path, "nul\0");
                case "TOO_LARGE" -> Files.writeString(path, "x".repeat(33));
                case "READ_FAILED" -> Files.delete(path);
                default -> throw new AssertionError(reason);
            }

            ExtractionException failure =
                    assertThrows(ExtractionException.class, () -> indexer.index(Path.of("notes.txt"), storage));

            assertEquals(ExtractionException.Reason.valueOf(reason), failure.reason());
            assertEquals(path, failure.path());
            assertEquals(
                    before.file(),
                    storage.files().findByPath(Path.of("notes.txt")).orElseThrow());
            assertEquals(chunks, storage.chunks().findByFileId(before.file().id()));
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("oldneedle"))
                            .size());

            if (Files.exists(path)) {
                Files.copy(path, root.resolve("rejected.txt"));
            }

            assertThrows(ExtractionException.class, () -> indexer.index(Path.of("rejected.txt"), storage));
            assertTrue(storage.files().findByPath(Path.of("rejected.txt")).isEmpty());
        }
    }

    @Test
    void chunkingFailureOccursBeforeAnyReplacementAndIsNotSwallowed() throws Exception {
        Files.writeString(root.resolve("notes.txt"), "oldneedle");
        ProjectContext context = context(root);
        IllegalStateException failure = new IllegalStateException("chunker failed");
        DocumentChunker broken = document -> {
            throw failure;
        };
        FileIndexer indexer = new FileIndexer(
                context, extraction(), new DocumentChunkerFactory(broken, broken, broken), profile(context));

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer.Result before = new FileIndexer(context).index(Path.of("notes.txt"), storage);
            Files.writeString(root.resolve("notes.txt"), "newneedle");

            assertSame(
                    failure,
                    assertThrows(IllegalStateException.class, () -> indexer.index(Path.of("notes.txt"), storage)));
            assertEquals(
                    before.file(),
                    storage.files().findByPath(Path.of("notes.txt")).orElseThrow());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("oldneedle"))
                            .size());

            Files.writeString(root.resolve("new.txt"), "newneedle");

            assertThrows(IllegalStateException.class, () -> indexer.index(Path.of("new.txt"), storage));
            assertTrue(storage.files().findByPath(Path.of("new.txt")).isEmpty());
        }
    }

    @Test
    void sqlFailureAfterFirstChunkRestoresManifestChunksAndFtsThenAllowsRetry() throws Exception {
        Files.writeString(root.resolve("notes.txt"), "oldneedle");
        ProjectContext context = context(root);
        DocumentChunker two = d -> List.of(
                new Chunk(d.sourcePath(), d.type(), 0, "newneedle first", new LineRange(1, 1), ChunkMetadata.empty()),
                new Chunk(d.sourcePath(), d.type(), 1, "newneedle second", new LineRange(2, 2), ChunkMetadata.empty()));
        FileIndexer indexer =
                new FileIndexer(context, extraction(), new DocumentChunkerFactory(two, two, two), profile(context));

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer.Result before = new FileIndexer(context).index(Path.of("notes.txt"), storage);
            List<StoredChunk> chunks =
                    storage.chunks().findByFileId(before.file().id());
            Files.writeString(root.resolve("notes.txt"), "newneedle first\nnewneedle second");
            sql(
                    context,
                    "CREATE TRIGGER fail_second BEFORE INSERT ON chunks WHEN NEW.chunk_index = 1 BEGIN SELECT"
                            + " RAISE(ABORT, 'injected'); END");

            assertThrows(SQLException.class, () -> indexer.index(Path.of("notes.txt"), storage));
            assertEquals(
                    before.file(),
                    storage.files().findByPath(Path.of("notes.txt")).orElseThrow());
            assertEquals(chunks, storage.chunks().findByFileId(before.file().id()));
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("newneedle"))
                    .isEmpty());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("oldneedle"))
                            .size());

            Files.writeString(root.resolve("new.txt"), "newneedle");

            assertThrows(SQLException.class, () -> indexer.index(Path.of("new.txt"), storage));
            assertTrue(storage.files().findByPath(Path.of("new.txt")).isEmpty());

            sql(context, "DROP TRIGGER fail_second");

            assertEquals(2, indexer.index(Path.of("notes.txt"), storage).chunkCount());
            assertEquals(
                    2,
                    storage.lexicalSearch()
                            .search(new SearchRequest("newneedle"))
                            .size());
        }
    }

    @Test
    void keepsTheExtractionSnapshotHashWhenTheSourceChangesBeforePersistence() throws Exception {
        Path path = Files.writeString(root.resolve("notes.txt"), "snapshotneedle");
        ProjectContext context = context(root);
        DocumentChunkerFactory delegate = DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), 256, 32);
        DocumentChunker changing = d -> {
            try {
                Files.writeString(path, "laterneedle");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }

            return delegate.getChunker(d).chunk(d);
        };
        FileIndexer indexer = new FileIndexer(
                context, extraction(), new DocumentChunkerFactory(changing, changing, changing), profile(context));

        try (SqliteStorage storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer.Result result = indexer.index(Path.of("notes.txt"), storage);

            assertEquals(
                    ContentHash.sha256("snapshotneedle".getBytes(StandardCharsets.UTF_8)),
                    result.file().contentHash());
            assertEquals("laterneedle", Files.readString(path));
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("snapshotneedle"))
                            .size());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("laterneedle"))
                    .isEmpty());
        }
    }

    private ProjectContext context(Path target) throws Exception {
        return new ProjectContextResolver(new PeciaConfigLoader(new PeciaConfigParser())).resolve(target);
    }

    private static DocumentExtractionService extraction() {
        return new DocumentExtractionService(new FileContentLoader(1024), new TextDocumentExtractor());
    }

    private static IndexingProfile profile(ProjectContext context) {
        return IndexingProfile.from(
                context.loadedConfig().config(), MiniLmTokenizer.bundled().identity());
    }

    private static void sql(ProjectContext context, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + context.databasePath().toUri());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
