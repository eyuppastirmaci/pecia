package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkIndex;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkMatches;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.ids;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.sqlite.JDBC;

/** Runs discovery, extraction, chunking and storage for every text family, including SQLite rollback. */
final class PipelineScenario {

    private PipelineScenario() {}

    /**
     * Exercises the packaged core API as a consumer isolated from the build classpath.
     *
     * @param root temporary project directory used for the fixture
     * @param coreJar expected archive providing every production class and bundled resource
     * @param runtimeDirectory directory containing the core's runtime dependency JARs
     * @throws Exception if the fixture, packaged resources, or core API cannot be used
     * @throws AssertionError if the engine behavior or archive provenance is incorrect
     */
    static void verify(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        String configuration = """
            [index]
            include = ["docs/*.md"]
            exclude = ["docs/excluded.md"]
            [chunk]
            max_tokens = 64
            overlap_tokens = 0
            """;
        Files.writeString(root.resolve(".pecia.toml"), configuration);
        Files.writeString(root.resolve(".gitignore"), "ignored.md\n");
        Path docs = Files.createDirectory(root.resolve("docs"));
        String markdown = "# Installation\nintro\n## Windows\n- one\n- two\n";
        Path source = Files.writeString(docs.resolve("keep.md"), markdown);
        Files.writeString(docs.resolve("ignored.md"), "ignored by JGit");
        Files.writeString(docs.resolve("excluded.md"), "excluded by configuration");
        Files.writeString(docs.resolve("other.txt"), "not included by configuration");

        IndexService service = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
        IndexPreview preview = service.preview(docs);
        check(preview.target().equals(docs), "Preview must preserve the selected directory");
        check(preview.loadedConfig().fromFile(), "TOML configuration was not loaded");
        check(preview.loadedConfig().root().equals(root), "Configuration must be rooted above the target");
        check(preview.loadedConfig().config().maxTokens() == 64, "TOML chunk settings were not applied");
        check(preview.walkResult().complete(), "The fixture scan must complete");
        check(
                preview.walkResult().files().equals(List.of(Path.of("keep.md"))),
                "Preview must apply include, exclude, and inherited Git ignore rules with target-relative" + " paths");

        DocumentType type = new FileTypeDetector().detect(source).orElseThrow();
        DocumentExtractionService extraction = new DocumentExtractionService(
                new FileContentLoader(preview.loadedConfig().config().maxFileBytes()), new TextDocumentExtractor());
        Document document = extraction.extract(new ExtractionRequest(source, Path.of("docs/keep.md"), type));
        check(document.type() == DocumentType.MARKDOWN, "The source must be detected as Markdown");
        check(document.content().equals(markdown), "UTF-8 extraction must preserve the content");
        check(
                document.contentHash().equals(ContentHash.sha256(markdown.getBytes(StandardCharsets.UTF_8))),
                "Extraction must retain the raw-byte hash");

        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        check(tokenizer.count("Hello world!") == 3, "Bundled WordPiece tokenization must work offline");
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(
                tokenizer,
                preview.loadedConfig().config().maxTokens(),
                preview.loadedConfig().config().overlapTokens());
        check(factory.getChunker(document) instanceof MarkdownChunker, "Markdown must use its dedicated strategy");
        List<Chunk> chunks = factory.getChunker(document).chunk(document);
        check(
                chunks.stream()
                        .map(chunk -> chunk.metadata().headingPath())
                        .toList()
                        .equals(List.of(List.of("Installation"), List.of("Installation", "Windows"))),
                "CommonMark chunking must preserve the heading hierarchy");

        for (Chunk chunk : chunks) {
            check(chunk.sourcePath().equals(Path.of("docs/keep.md")), "Chunks must preserve the source identity");
            check(tokenizer.countModelInput(chunk.content()) <= 64, "Chunks must respect the loaded token budget");
        }

        check(
                Files.readString(root.resolve(".pecia.toml")).equals(configuration),
                "The source config must be unchanged");
        check(
                !Files.exists(root.resolve(".pecia")) && !Files.exists(docs.resolve(".pecia")),
                "The shared engine must not create an index during these operations");
        check(!Files.exists(docs.resolve(".pecia.toml")), "Preview must not create a config in its target");

        Path database = root.resolve(".pecia/index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            check(Files.size(database) > 0, "Packaged SQLite must initialize a file database");
            var storedFile = storage.replaceFile(document, chunks);
            check(
                    storage.replaceFile(document, chunks).id() == storedFile.id(),
                    "Packaged replacement must retain the file ID across repeated indexing");
            checkOriginalSearch(database, storage.chunks().findByFileId(storedFile.id()));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            check(Files.isRegularFile(database), "Packaged SQLite must reopen the initialized database");
            var stored = storage.files().findByPath(document.sourcePath()).orElseThrow();
            check(
                    stored.contentHash().equals(document.contentHash()) && stored.documentType() == document.type(),
                    "Packaged file repository must preserve the manifest across reopening");
            check(
                    storage.chunks().findByFileId(stored.id()).stream()
                            .map(value -> value.chunk())
                            .toList()
                            .equals(chunks),
                    "Packaged chunk repository must preserve exact chunks and metadata across reopening");
            checkOriginalSearch(database, storage.chunks().findByFileId(stored.id()));
        }

        verifyStorageRecovery(root, database, document, chunks, factory);
        verifyStorageFamilies(root, extraction, factory);
    }

    /* Exercises rollback and recovery through the public packaged API with a real SQLite write failure. */
    private static void verifyStorageRecovery(
            Path root, Path database, Document document, List<Chunk> chunks, DocumentChunkerFactory factory)
            throws Exception {
        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TRIGGER fail_packaged_write BEFORE INSERT ON chunk_attributes
                BEGIN SELECT RAISE(ABORT, 'packaged write failure'); END;
                """);
        }

        String text = "# Güncel 😀\r\nYeni içerik, é ve ı.\r\n";
        Document replacement = new Document(
                document.sourcePath(),
                DocumentType.MARKDOWN,
                text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
        List<Chunk> updated = factory.getChunker(replacement).chunk(replacement);

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            var before = storage.files().findByPath(document.sourcePath()).orElseThrow();
            var beforeChunks = storage.chunks().findByFileId(before.id());

            try {
                storage.replaceFile(replacement, updated);
                throw new AssertionError("The injected packaged write failure must abort replacement");
            } catch (SQLException expected) {
                check(expected.getMessage().contains("packaged write failure"), "Expected the injected SQLite failure");
            }

            check(
                    storage.files()
                            .findByPath(document.sourcePath())
                            .orElseThrow()
                            .equals(before),
                    "Failed replacement must restore the complete manifest");
            check(
                    storage.chunks().findByFileId(before.id()).equals(beforeChunks),
                    "Failed replacement must preserve chunk IDs and every metadata value");
            checkOriginalSearch(database, beforeChunks);
            checkMatches(database, "yeni OR güncel");
        }

        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER fail_packaged_write");
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            var before = storage.files().findByPath(document.sourcePath()).orElseThrow();
            check(before.contentHash().equals(document.contentHash()), "Rollback must survive database reopening");
            check(
                    storage.chunks().findByFileId(before.id()).stream()
                            .map(value -> value.chunk())
                            .toList()
                            .equals(chunks),
                    "Original chunk content must survive rollback and reopening");
            checkOriginalSearch(database, storage.chunks().findByFileId(before.id()));
            checkMatches(database, "yeni OR güncel");
            var saved = storage.replaceFile(replacement, updated);
            check(saved.id() == before.id(), "Retry must preserve the file ID");
            check(saved.contentHash().equals(replacement.contentHash()), "Retry must update the raw-byte hash");
            check(
                    storage.chunks().findByFileId(saved.id()).stream()
                            .map(value -> value.chunk())
                            .toList()
                            .equals(updated),
                    "Retry must persist the replacement chunks");
            checkReplacementSearch(database, storage.chunks().findByFileId(saved.id()));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            var saved = storage.files().findByPath(document.sourcePath()).orElseThrow();
            check(
                    storage.chunks().findByFileId(saved.id()).stream()
                            .map(value -> value.chunk())
                            .toList()
                            .equals(updated),
                    "Successful retry must survive reopening");
            checkReplacementSearch(database, storage.chunks().findByFileId(saved.id()));
            check(storage.files().delete(saved.id()), "Deleting the manifest must succeed");
            check(storage.chunks().findByFileId(saved.id()).isEmpty(), "Deleting the file must delete its chunks");
            checkIndex(database, List.of());
            checkMatches(database, "yeni OR güncel OR keep OR intro OR installation OR windows");
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            check(storage.files().findAll().isEmpty(), "Cascade deletion must survive reopening");
            checkIndex(database, List.of());
            checkMatches(database, "yeni OR güncel OR keep OR intro OR installation OR windows");
        }

        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                    SELECT (SELECT count(*) FROM files) + (SELECT count(*) FROM chunks)
                         + (SELECT count(*) FROM chunk_headings) + (SELECT count(*) FROM chunk_attributes)
                    """)) {
            check(rows.next() && rows.getInt(1) == 0, "Packaged cascade deletion must leave no orphan metadata");
        }
    }

    /* Runs extraction, real chunking and persistence for every text family without any embedding runtime. */
    private static void verifyStorageFamilies(
            Path root, DocumentExtractionService extraction, DocumentChunkerFactory factory) throws Exception {
        Path database = root.resolve(".pecia/families.db");

        for (DocumentType type : DocumentType.values()) {
            Path relative = Path.of("docs", type.name() + ".txt");
            Path source = Files.writeString(root.resolve(relative), "\uFEFF# Başlık 😀\r\nİstanbul é\r\nson");
            Document document = extraction.extract(new ExtractionRequest(source, relative, type));
            List<Chunk> expected = factory.getChunker(document).chunk(document);
            check(!expected.isEmpty(), "Each family fixture must produce chunks");

            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                storage.replaceFile(document, expected);
            }

            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                var saved = storage.files().findByPath(relative).orElseThrow();
                check(
                        saved.contentHash().equals(ContentHash.sha256(Files.readAllBytes(source))),
                        "Stored hash must include raw BOM and CRLF bytes");
                check(
                        storage.chunks().findByFileId(saved.id()).stream()
                                .map(value -> value.chunk())
                                .toList()
                                .equals(expected),
                        "Stored family chunks must preserve exact extraction/chunking output: " + type);
                var storedChunks = storage.chunks().findByFileId(saved.id());
                checkIndex(database, storedChunks);
                checkMatches(database, "source_path: docs", ids(storedChunks));
                long[] bodyIds = ids(storedChunks.stream()
                        .filter(value -> value.chunk().content().contains("son"))
                        .toList());
                check(bodyIds.length > 0, "The family fixture must have searchable body content: " + type);
                checkMatches(database, "content: son", bodyIds);
                Document empty = new Document(relative, type, "", ContentHash.sha256(new byte[0]));
                check(
                        storage.replaceFile(empty, List.of()).id() == saved.id(),
                        "Empty replacement must retain file identity");
                checkIndex(database, List.of());
                checkMatches(database, "docs OR son OR başlık");
            }

            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                var saved = storage.files().findByPath(relative).orElseThrow();
                check(storage.chunks().findByFileId(saved.id()).isEmpty(), "Empty replacement must survive reopening");
                checkIndex(database, List.of());
                checkMatches(database, "docs OR son OR başlık");
            }
        }
    }

    private static void checkOriginalSearch(Path database, List<StoredChunk> chunks) throws Exception {
        check(chunks.size() == 2, "The original Markdown fixture must have two chunks");
        checkIndex(database, chunks);
        checkMatches(database, "content: intro", chunks.getFirst().id());
        checkMatches(database, "content: one", chunks.getLast().id());
        checkMatches(database, "headings: installation", ids(chunks));
        checkMatches(database, "headings: windows", chunks.getLast().id());
        checkMatches(database, "source_path: keep", ids(chunks));
        checkMatches(database, "content: keep");
    }

    private static void checkReplacementSearch(Path database, List<StoredChunk> chunks) throws Exception {
        check(chunks.size() == 1, "The replacement fixture must have one chunk");
        checkIndex(database, chunks);
        checkMatches(database, "content: yeni", ids(chunks));
        checkMatches(database, "headings: güncel", ids(chunks));
        checkMatches(database, "source_path: keep", ids(chunks));
        checkMatches(database, "intro OR installation OR windows OR one");
    }
}
