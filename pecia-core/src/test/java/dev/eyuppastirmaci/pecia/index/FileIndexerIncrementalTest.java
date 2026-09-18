package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.DocumentExtractor;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContent;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileIndexerIncrementalTest {
    private static final Path SOURCE = Path.of("notes.txt");

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"needle İstanbul 😀", "", " \r\n\t"})
    void unchangedFilesSkipExtractionChunkingAndWritesEvenOnReadOnlyStorage(String text) throws Exception {
        Files.writeString(root.resolve(SOURCE), text);
        ProjectContext context = context();
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger chunkings = new AtomicInteger();
        FileIndexer indexer = countingIndexer(context, profile(context), extractions, chunkings);
        FileIndexer.Result first;

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            first = indexer.index(SOURCE, storage);
            assertFalse(first.unchanged());
            assertEquals(
                    profile(context),
                    storage.files().findIndexingProfile(first.file().id()).orElseThrow());
        }

        byte[] databaseBefore = Files.readAllBytes(context.databasePath());

        try (var storage = SqliteStorage.openReadOnly(context.databasePath(), root)) {
            var chunksBefore = storage.chunks().findByFileId(first.file().id());
            FileIndexer.Result repeated = indexer.index(SOURCE, storage);

            assertTrue(repeated.unchanged());
            assertEquals(first.file(), repeated.file());
            assertEquals(0, repeated.chunkCount());
            assertEquals(
                    chunksBefore, storage.chunks().findByFileId(first.file().id()));
            assertEquals(1, extractions.get());
            assertEquals(1, chunkings.get());
        }

        assertArrayEquals(databaseBefore, Files.readAllBytes(context.databasePath()));
    }

    @Test
    void sameSizeAndTimestampStillDetectChangedBytesAndReplaceSearchResults() throws Exception {
        Path file = Files.writeString(root.resolve(SOURCE), "oldneedle");
        FileTime originalTime = Files.getLastModifiedTime(file);
        ProjectContext context = context();
        FileIndexer indexer = new FileIndexer(context);

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            var first = indexer.index(SOURCE, storage);
            Files.writeString(file, "newneedle");
            Files.setLastModifiedTime(file, originalTime);
            var changed = indexer.index(SOURCE, storage);

            assertFalse(changed.unchanged());
            assertEquals(first.file().id(), changed.file().id());
            assertEquals(hash("newneedle"), changed.file().contentHash());
            assertEquals(1, changed.chunkCount());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("oldneedle"))
                    .isEmpty());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("newneedle"))
                            .size());
            assertTrue(indexer.index(SOURCE, storage).unchanged());
        }
    }

    @Test
    void timestampOnlyChangesDoNotRequireReprocessing() throws Exception {
        Path file = Files.writeString(root.resolve(SOURCE), "needle");
        ProjectContext context = context();

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer indexer = new FileIndexer(context);
            indexer.index(SOURCE, storage);
            Files.setLastModifiedTime(
                    file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() - 60000));

            assertTrue(indexer.index(SOURCE, storage).unchanged());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"maxTokens", "overlapTokens", "tokenizer"})
    void changedProcessingProfilesForceReprocessingWithIdenticalBytes(String setting) throws Exception {
        Files.writeString(root.resolve(SOURCE), "needle");
        ProjectContext originalContext = context();

        try (var storage = SqliteStorage.open(originalContext.databasePath(), root)) {
            var first = new FileIndexer(originalContext).index(SOURCE, storage);
            String config =
                    switch (setting) {
                        case "maxTokens" -> "[chunk]\nmax_tokens = 128\n";
                        case "overlapTokens" -> "[chunk]\noverlap_tokens = 8\n";
                        default -> "";
                    };
            Files.writeString(root.resolve(".pecia.toml"), config);
            ProjectContext updatedContext = context();
            IndexingProfile updated = profile(updatedContext);

            if (setting.equals("tokenizer")) {
                updated = new IndexingProfile("different-tokenizer", updated.maxTokens(), updated.overlapTokens());
            }

            AtomicInteger extractions = new AtomicInteger();
            AtomicInteger chunkings = new AtomicInteger();
            FileIndexer indexer = countingIndexer(updatedContext, updated, extractions, chunkings);
            var changed = indexer.index(SOURCE, storage);

            assertFalse(changed.unchanged());
            assertEquals(first.file(), changed.file());
            assertEquals(
                    updated,
                    storage.files().findIndexingProfile(first.file().id()).orElseThrow());
            assertTrue(indexer.index(SOURCE, storage).unchanged());
            assertEquals(1, extractions.get());
            assertEquals(1, chunkings.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyProfilesAndChangedDocumentTypesAreReprocessed(boolean changedType) throws Exception {
        Files.writeString(root.resolve(SOURCE), "needle");
        ProjectContext context = context();

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            Document legacy = new Document(
                    SOURCE, changedType ? DocumentType.MARKDOWN : DocumentType.PLAIN_TEXT, "needle", hash("needle"));
            var file = changedType
                    ? storage.replaceFile(legacy, List.of(), profile(context))
                    : storage.replaceFile(legacy, List.of());
            FileIndexer indexer = new FileIndexer(context);
            var result = indexer.index(SOURCE, storage);

            assertFalse(result.unchanged());
            assertEquals(file.id(), result.file().id());
            assertEquals(DocumentType.PLAIN_TEXT, result.file().documentType());
            assertEquals(1, result.chunkCount());
            assertTrue(indexer.index(SOURCE, storage).unchanged());
        }
    }

    @Test
    void lowerAdmissionLimitIsEnforcedBeforeAnUnchangedDecision() throws Exception {
        Files.writeString(root.resolve(SOURCE), "needle larger than the new limit");
        ProjectContext originalContext = context();

        try (var storage = SqliteStorage.open(originalContext.databasePath(), root)) {
            var first = new FileIndexer(originalContext).index(SOURCE, storage);
            var chunks = storage.chunks().findByFileId(first.file().id());
            Files.writeString(root.resolve(".pecia.toml"), "[index]\nmax_file_bytes = 4\n");
            FileIndexer indexer = new FileIndexer(context());
            ExtractionException failure = assertThrows(ExtractionException.class, () -> indexer.index(SOURCE, storage));

            assertEquals(ExtractionException.Reason.TOO_LARGE, failure.reason());
            assertEquals(chunks, storage.chunks().findByFileId(first.file().id()));
            assertEquals(
                    profile(originalContext),
                    storage.files().findIndexingProfile(first.file().id()).orElseThrow());
        }
    }

    @Test
    void deletingOrReplacingAFileWithADirectoryCannotReturnUnchanged() throws Exception {
        Path file = Files.writeString(root.resolve(SOURCE), "needle");
        ProjectContext context = context();

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer indexer = new FileIndexer(context);
            var first = indexer.index(SOURCE, storage);
            Files.delete(file);

            assertEquals(
                    ExtractionException.Reason.READ_FAILED,
                    assertThrows(ExtractionException.class, () -> indexer.index(SOURCE, storage))
                            .reason());
            Files.createDirectory(file);
            assertEquals(
                    ExtractionException.Reason.NOT_REGULAR_FILE,
                    assertThrows(ExtractionException.class, () -> indexer.index(SOURCE, storage))
                            .reason());
            assertEquals(first.file(), storage.files().findByPath(SOURCE).orElseThrow());
            assertEquals(
                    profile(context),
                    storage.files().findIndexingProfile(first.file().id()).orElseThrow());
        }
    }

    @Test
    void failedProfileWritePreservesOldStateAndRetryProcessesTheChangedFile() throws Exception {
        Path file = Files.writeString(root.resolve(SOURCE), "oldneedle");
        ProjectContext context = context();

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer indexer = new FileIndexer(context);
            var first = indexer.index(SOURCE, storage);
            var chunks = storage.chunks().findByFileId(first.file().id());
            Files.writeString(file, "newneedle");
            sql(
                    context,
                    "CREATE TRIGGER fail_profile BEFORE INSERT ON file_indexing_profiles "
                            + "BEGIN SELECT RAISE(ABORT, 'injected'); END");

            assertThrows(SQLException.class, () -> indexer.index(SOURCE, storage));
            assertEquals(first.file(), storage.files().findByPath(SOURCE).orElseThrow());
            assertEquals(chunks, storage.chunks().findByFileId(first.file().id()));
            assertEquals(
                    profile(context),
                    storage.files().findIndexingProfile(first.file().id()).orElseThrow());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("oldneedle"))
                            .size());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("newneedle"))
                    .isEmpty());

            sql(context, "DROP TRIGGER fail_profile");
            assertFalse(indexer.index(SOURCE, storage).unchanged());
            assertTrue(indexer.index(SOURCE, storage).unchanged());
        }
    }

    @Test
    void extractionUsesTheHashedSnapshotEvenIfTheSourceChangesBeforeDecoding() throws Exception {
        Path file = Files.writeString(root.resolve(SOURCE), "snapshotneedle");
        ProjectContext context = context();
        DocumentExtractor changing = new DocumentExtractor() {
            @Override
            public boolean supports(DocumentType type) {
                return true;
            }

            @Override
            public Document extract(ExtractionRequest request, FileContent content) throws ExtractionException {
                try {
                    Files.writeString(file, "laterneedle");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }

                return new TextDocumentExtractor().extract(request, content);
            }
        };
        var config = context.loadedConfig().config();
        FileIndexer indexer = new FileIndexer(
                context,
                new DocumentExtractionService(new FileContentLoader(config.maxFileBytes()), changing),
                DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), config.maxTokens(), config.overlapTokens()),
                profile(context));

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            var first = indexer.index(SOURCE, storage);
            assertEquals(hash("snapshotneedle"), first.file().contentHash());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("snapshotneedle"))
                            .size());
            assertEquals("laterneedle", Files.readString(file));
            var next = new FileIndexer(context).index(SOURCE, storage);
            assertFalse(next.unchanged());
            assertEquals(hash("laterneedle"), next.file().contentHash());
        }
    }

    private FileIndexer countingIndexer(
            ProjectContext context, IndexingProfile profile, AtomicInteger extractions, AtomicInteger chunkings) {
        var config = context.loadedConfig().config();
        TextDocumentExtractor delegate = new TextDocumentExtractor();
        DocumentExtractor extractor = new DocumentExtractor() {
            @Override
            public boolean supports(DocumentType type) {
                return delegate.supports(type);
            }

            @Override
            public Document extract(ExtractionRequest request, FileContent content) throws ExtractionException {
                extractions.incrementAndGet();
                return delegate.extract(request, content);
            }
        };
        DocumentChunkerFactory factory =
                DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), config.maxTokens(), config.overlapTokens());
        DocumentChunker chunker = document -> {
            chunkings.incrementAndGet();
            return factory.getChunker(document).chunk(document);
        };

        return new FileIndexer(
                context,
                new DocumentExtractionService(new FileContentLoader(config.maxFileBytes()), extractor),
                new DocumentChunkerFactory(chunker, chunker, chunker),
                profile);
    }

    private ProjectContext context() throws Exception {
        return new ProjectContextResolver(new PeciaConfigLoader(new PeciaConfigParser())).resolve(root);
    }

    private static IndexingProfile profile(ProjectContext context) {
        return IndexingProfile.from(
                context.loadedConfig().config(), MiniLmTokenizer.bundled().identity());
    }

    private static ContentHash hash(String text) {
        return ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sql(ProjectContext context, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + context.databasePath().toUri());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
