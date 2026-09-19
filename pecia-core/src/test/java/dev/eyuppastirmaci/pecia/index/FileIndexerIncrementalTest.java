package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
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
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        FileIndexer indexer = countingIndexer(context, identity(context), extractions, chunkings);
        FileIndexer.Result first;

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            first = indexer.index(SOURCE, storage);
            assertFalse(first.unchanged());
            assertEquals(
                    identity(context),
                    storage.files().findChunkingIdentity(first.file().id()).orElseThrow());
        }

        byte[] databaseBefore = Files.readAllBytes(context.databasePath());

        try (var storage = SqliteStorage.openReadOnly(context.databasePath(), root)) {
            var chunksBefore = storage.chunks().findByFileId(first.file().id());
            assertTrue(chunksBefore.stream().allMatch(chunk -> chunk.stableId().isPresent()));
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
            var initialId = storage.chunks()
                    .findByFileId(first.file().id())
                    .getFirst()
                    .stableId()
                    .orElseThrow();
            Files.writeString(file, "newneedle");
            Files.setLastModifiedTime(file, originalTime);
            var changed = indexer.index(SOURCE, storage);

            assertFalse(changed.unchanged());
            assertEquals(first.file().id(), changed.file().id());
            assertEquals(hash("newneedle"), changed.file().contentHash());
            assertEquals(1, changed.chunkCount());
            assertEquals(
                    initialId,
                    storage.chunks()
                            .findByFileId(changed.file().id())
                            .getFirst()
                            .stableId()
                            .orElseThrow());
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
    @ValueSource(
            strings = {
                "extractionVersion",
                "chunkingVersion",
                "algorithm",
                "vocabularySha256",
                "vocabularySize",
                "maxInputTokens",
                "specialTokenCount",
                "maxTokens",
                "overlapTokens"
            })
    void changedProcessingProfilesForceReprocessingWithIdenticalBytes(String setting) throws Exception {
        Files.writeString(root.resolve(SOURCE), "needle");
        ProjectContext originalContext = context();

        try (var storage = SqliteStorage.open(originalContext.databasePath(), root)) {
            var first = new FileIndexer(originalContext).index(SOURCE, storage);
            var originalId = storage.chunks()
                    .findByFileId(first.file().id())
                    .getFirst()
                    .stableId()
                    .orElseThrow();
            String config =
                    switch (setting) {
                        case "maxTokens" -> "[chunk]\nmax_tokens = 128\n";
                        case "overlapTokens" -> "[chunk]\noverlap_tokens = 8\n";
                        default -> "";
                    };
            Files.writeString(root.resolve(".pecia.toml"), config);
            ProjectContext updatedContext = context();
            ChunkingIdentity updated = changedIdentity(identity(updatedContext), setting);

            AtomicInteger extractions = new AtomicInteger();
            AtomicInteger chunkings = new AtomicInteger();
            FileIndexer indexer = countingIndexer(updatedContext, updated, extractions, chunkings);
            var changed = indexer.index(SOURCE, storage);

            assertFalse(changed.unchanged());
            assertEquals(first.file(), changed.file());
            assertEquals(
                    updated,
                    storage.files().findChunkingIdentity(first.file().id()).orElseThrow());
            assertNotEquals(
                    originalId,
                    storage.chunks()
                            .findByFileId(first.file().id())
                            .getFirst()
                            .stableId()
                            .orElseThrow());
            assertTrue(indexer.index(SOURCE, storage).unchanged());
            assertEquals(1, extractions.get());
            assertEquals(1, chunkings.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"modelId", "revision"})
    void modelProvenanceChangesSkipExtractionChunkingAndWrites(String setting) throws Exception {
        Files.writeString(root.resolve(SOURCE), "needle");
        ProjectContext context = context();
        var original = MiniLmTokenizer.bundled().identity();
        var updated = new TokenizerIdentity(
                setting.equals("modelId") ? "different/model" : original.modelId(),
                setting.equals("revision") ? "different-revision" : original.revision(),
                original.algorithm(),
                original.vocabularySha256(),
                original.vocabularySize(),
                original.maxInputTokens(),
                original.specialTokenCount());
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger chunkings = new AtomicInteger();
        FileIndexer indexer = countingIndexer(
                context, ChunkingIdentity.from(context.loadedConfig().config(), updated), extractions, chunkings);
        FileIndexer.Result first;

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            first = new FileIndexer(context).index(SOURCE, storage);
        }

        byte[] databaseBefore = Files.readAllBytes(context.databasePath());

        try (var storage = SqliteStorage.openReadOnly(context.databasePath(), root)) {
            var chunksBefore = storage.chunks().findByFileId(first.file().id());
            var result = indexer.index(SOURCE, storage);

            assertTrue(result.unchanged());
            assertEquals(first.file(), result.file());
            assertEquals(0, result.chunkCount());
            assertEquals(
                    chunksBefore, storage.chunks().findByFileId(first.file().id()));
            assertEquals(
                    identity(context),
                    storage.files().findChunkingIdentity(first.file().id()).orElseThrow());
            assertEquals(0, extractions.get());
            assertEquals(0, chunkings.get());
        }

        assertArrayEquals(databaseBefore, Files.readAllBytes(context.databasePath()));
    }

    @ParameterizedTest
    @CsvSource({
        "needle,unprofiled",
        "needle,legacyProfile",
        "needle,changedDocumentType",
        "'',unprofiled",
        "'',legacyProfile"
    })
    void legacyProfilesAndChangedDocumentTypesAreReprocessedOnce(String text, String storedState) throws Exception {
        Files.writeString(root.resolve(SOURCE), text);
        ProjectContext context = context();
        var config = context.loadedConfig().config();
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        Document legacy = new Document(
                SOURCE,
                storedState.equals("changedDocumentType") ? DocumentType.MARKDOWN : DocumentType.PLAIN_TEXT,
                text,
                hash(text));
        var chunks = DocumentChunkerFactory.create(tokenizer, config.maxTokens(), config.overlapTokens())
                .getChunker(legacy)
                .chunk(legacy);

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            var file =
                    switch (storedState) {
                        case "legacyProfile" ->
                            storage.replaceFile(legacy, chunks, IndexingProfile.from(config, tokenizer.identity()));
                        case "changedDocumentType" -> storage.replaceFile(legacy, chunks, identity(context));
                        default -> storage.replaceFile(legacy, chunks);
                    };
            AtomicInteger extractions = new AtomicInteger();
            AtomicInteger chunkings = new AtomicInteger();
            FileIndexer indexer = countingIndexer(context, identity(context), extractions, chunkings);
            var result = indexer.index(SOURCE, storage);

            assertFalse(result.unchanged());
            assertEquals(file.id(), result.file().id());
            assertEquals(DocumentType.PLAIN_TEXT, result.file().documentType());
            assertEquals(text.isBlank() ? 0 : 1, result.chunkCount());
            assertEquals(
                    identity(context),
                    storage.files().findChunkingIdentity(file.id()).orElseThrow());
            assertTrue(storage.files().findIndexingProfile(file.id()).isEmpty());
            assertTrue(storage.chunks().findByFileId(file.id()).stream()
                    .allMatch(chunk -> chunk.stableId().isPresent()));
            assertTrue(indexer.index(SOURCE, storage).unchanged());
            assertEquals(1, extractions.get());
            assertEquals(1, chunkings.get());
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
                    identity(originalContext),
                    storage.files().findChunkingIdentity(first.file().id()).orElseThrow());
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
                    identity(context),
                    storage.files().findChunkingIdentity(first.file().id()).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file_chunking_profiles", "chunk_identities"})
    void failedIdentityWritePreservesOldStateAndRetryProcessesTheChangedFile(String table) throws Exception {
        Path file = Files.writeString(root.resolve(SOURCE), "oldneedle");
        ProjectContext context = context();

        try (var storage = SqliteStorage.open(context.databasePath(), root)) {
            FileIndexer indexer = new FileIndexer(context);
            var first = indexer.index(SOURCE, storage);
            var chunks = storage.chunks().findByFileId(first.file().id());
            Files.writeString(file, "newneedle");
            sql(
                    context,
                    "CREATE TRIGGER fail_identity BEFORE INSERT ON " + table
                            + " BEGIN SELECT RAISE(ABORT, 'injected'); END");

            assertThrows(SQLException.class, () -> indexer.index(SOURCE, storage));
            assertEquals(first.file(), storage.files().findByPath(SOURCE).orElseThrow());
            assertEquals(chunks, storage.chunks().findByFileId(first.file().id()));
            assertEquals(
                    identity(context),
                    storage.files().findChunkingIdentity(first.file().id()).orElseThrow());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("oldneedle"))
                            .size());
            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("newneedle"))
                    .isEmpty());

            sql(context, "DROP TRIGGER fail_identity");
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
                identity(context));

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
            ProjectContext context, ChunkingIdentity identity, AtomicInteger extractions, AtomicInteger chunkings) {
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
                identity);
    }

    private ProjectContext context() throws Exception {
        return new ProjectContextResolver(new PeciaConfigLoader(new PeciaConfigParser())).resolve(root);
    }

    private static ChunkingIdentity identity(ProjectContext context) {
        return ChunkingIdentity.from(
                context.loadedConfig().config(), MiniLmTokenizer.bundled().identity());
    }

    private static ChunkingIdentity changedIdentity(ChunkingIdentity original, String setting) {
        TokenizerCompatibility tokenizer = original.tokenizer();
        TokenizerCompatibility updated = new TokenizerCompatibility(
                setting.equals("algorithm") ? "different-algorithm" : tokenizer.algorithm(),
                setting.equals("vocabularySha256") ? "a".repeat(64) : tokenizer.vocabularySha256(),
                setting.equals("vocabularySize") ? tokenizer.vocabularySize() + 1 : tokenizer.vocabularySize(),
                setting.equals("maxInputTokens") ? tokenizer.maxInputTokens() + 1 : tokenizer.maxInputTokens(),
                setting.equals("specialTokenCount")
                        ? tokenizer.specialTokenCount() + 1
                        : tokenizer.specialTokenCount());

        return new ChunkingIdentity(
                setting.equals("extractionVersion") ? "different-extraction" : original.extractionVersion(),
                setting.equals("chunkingVersion") ? "different-chunking" : original.chunkingVersion(),
                updated,
                original.maxTokens(),
                original.overlapTokens());
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
