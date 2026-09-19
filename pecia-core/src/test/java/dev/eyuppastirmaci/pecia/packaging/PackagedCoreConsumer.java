package dev.eyuppastirmaci.pecia.packaging;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.search.LexicalSearch;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.commonmark.parser.Parser;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.sqlite.JDBC;
import org.tomlj.Toml;

/** Exercises the public core API and bundled resources from an isolated consumer classpath. */
public final class PackagedCoreConsumer {
    private static final String V1_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    private static final String V2_RESOURCE = "/db/migration/V2__add_chunk_fts.sql";
    private static final String V3_RESOURCE = "/db/migration/V3__add_file_indexing_profiles.sql";
    private static final String V4_RESOURCE = "/db/migration/V4__add_chunk_identities.sql";

    private PackagedCoreConsumer() {}

    /**
     * Exercises the packaged core API as a consumer isolated from the build classpath.
     *
     * @param root temporary project directory used for the fixture
     * @param coreJar expected archive providing every production class and bundled resource
     * @param runtimeDirectory directory containing the core's runtime dependency JARs
     * @throws Exception if the fixture, packaged resources, or core API cannot be used
     * @throws AssertionError if the engine behavior or archive provenance is incorrect
     */
    public static void verify(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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

    /**
     * Verifies historical backfill through the public API without any source file or development
     * resource.
     */
    public static void verifyV1Migration(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("legacy.db");
        Path source = Path.of("archive/legacypath.md");
        ContentHash hash = ContentHash.sha256("historical raw bytes".getBytes(StandardCharsets.UTF_8));
        StoredFile file = new StoredFile(7, source, DocumentType.MARKDOWN, hash);
        List<StoredChunk> expected = List.of(
                new StoredChunk(
                        41,
                        file.id(),
                        new Chunk(
                                source,
                                file.documentType(),
                                0,
                                "legacybody İstanbul 😀\r\nunchanged",
                                new LineRange(3, 4),
                                new ChunkMetadata(
                                        List.of("Legacyparent", "Legacychild"),
                                        Map.of("startOffset", "3", "endOffset", "40", "custom", "quote ' \r\nİ😀")))),
                new StoredChunk(
                        42,
                        file.id(),
                        new Chunk(
                                source,
                                file.documentType(),
                                1,
                                "legacysecond é",
                                new LineRange(8, 8),
                                ChunkMetadata.empty())));

        try (Connection connection = openDatabase(database)) {
            connection.setAutoCommit(false);
            try (var input = SqliteStorage.class.getResourceAsStream(V1_RESOURCE);
                    var statement = connection.createStatement()) {
                check(input != null, "Historical schema must exist in the core JAR");
                statement.executeUpdate(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            execute(
                    connection,
                    "INSERT INTO index_metadata VALUES (1, ?, 1)",
                    root.toUri().toASCIIString());
            execute(
                    connection,
                    "INSERT INTO files VALUES (?, ?, ?, ?)",
                    file.id(),
                    "archive/legacypath.md",
                    file.documentType().name(),
                    hash.value());
            execute(connection, "INSERT INTO files VALUES (9, 'empty.md', 'PLAIN_TEXT', ?)", hash.value());
            for (StoredChunk stored : expected) {
                Chunk chunk = stored.chunk();
                LineRange lines = (LineRange) chunk.sourceLocation();
                execute(
                        connection,
                        "INSERT INTO chunks VALUES (?, ?, ?, ?, ?, ?)",
                        stored.id(),
                        file.id(),
                        chunk.index(),
                        chunk.content(),
                        lines.startLine(),
                        lines.endLine());
                // Insert in reverse order to prove backfill uses heading positions, not insertion order.
                for (int position = chunk.metadata().headingPath().size() - 1; position >= 0; position--) {
                    execute(
                            connection,
                            "INSERT INTO chunk_headings VALUES (?, ?, ?)",
                            stored.id(),
                            position,
                            chunk.metadata().headingPath().get(position));
                }
                for (var attribute : chunk.metadata().attributes().entrySet()) {
                    execute(
                            connection,
                            "INSERT INTO chunk_attributes VALUES (?, ?, ?)",
                            stored.id(),
                            attribute.getKey(),
                            attribute.getValue());
                }
            }
            execute(connection, "PRAGMA user_version = 1");
            connection.commit();
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT name FROM sqlite_schema WHERE name = 'chunks_fts'")) {
                check(!result.next(), "The historical fixture must not already have an FTS index");
            }
        }

        check(!Files.exists(root.resolve(source)), "The migration fixture must have no original source file");
        for (int attempt = 0; attempt < 2; attempt++) {
            try (SqliteStorage storage = SqliteStorage.open(database, root)) {
                check(
                        storage.files().findByPath(source).orElseThrow().equals(file),
                        "Migration must preserve file ID, type, path and hash");
                check(
                        storage.chunks().findByFileId(file.id()).equals(expected),
                        "Migration must preserve chunk IDs, text, headings, attributes, offsets and line" + " ranges");
                check(
                        storage.files().findIndexingProfile(file.id()).isEmpty(),
                        "Migrated historical files must have an unknown indexing profile");
                check(
                        storage.files()
                                        .findByPath(Path.of("empty.md"))
                                        .orElseThrow()
                                        .id()
                                == 9,
                        "Migration must retain files without chunks");
                checkIndex(database, expected);
                checkMatches(database, "content: legacybody", 41);
                checkMatches(database, "content: legacysecond", 42);
                checkMatches(database, "headings: \"legacyparent legacychild\"", 41);
                checkMatches(database, "source_path: legacypath", 41, 42);
                checkMatches(database, "source_path: empty");
                checkMatches(database, "content: legacyparent");
            }
        }
        check(!Files.exists(root.resolve(source)), "Migration must not reconstruct or read missing source files");
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

    /* All observations use a separate JDBC connection after public storage writes have committed. */
    private static void checkIndex(Path database, List<StoredChunk> expected) throws Exception {
        try (Connection connection = openDatabase(database)) {
            List<FtsRow> actual = new ArrayList<>();
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "SELECT rowid, content, headings, source_path FROM chunks_fts ORDER BY rowid")) {
                while (result.next()) {
                    actual.add(new FtsRow(
                            result.getLong(1), result.getString(2), result.getString(3), result.getString(4)));
                }
            }
            List<FtsRow> expectedRows = expected.stream()
                    .sorted(Comparator.comparingLong(StoredChunk::id))
                    .map(stored -> new FtsRow(
                            stored.id(),
                            stored.chunk().content(),
                            String.join(" ", stored.chunk().metadata().headingPath()),
                            stored.chunk().sourcePath().toString().replace('\\', '/')))
                    .toList();
            check(
                    actual.equals(expectedRows),
                    "Packaged FTS rows must preserve source IDs and all searchable fields: " + actual);
            execute(connection, "INSERT INTO chunks_fts(chunks_fts) VALUES ('integrity-check')");
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("PRAGMA user_version")) {
                check(result.next() && result.getInt(1) == 4, "Packaged storage must use schema version 4");
            }
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "SELECT index_format_version FROM index_metadata WHERE singleton = 1")) {
                check(result.next() && result.getInt(1) == 4, "Packaged storage must use index format 4");
            }
        }
    }

    private static void checkMatches(Path database, String expression, long... expectedIds) throws SQLException {
        try (Connection connection = openDatabase(database);
                var query = connection.prepareStatement(
                        "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY rowid")) {
            query.setString(1, expression);
            List<Long> actual = new ArrayList<>();
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getLong(1));
                }
            }
            check(
                    actual.equals(Arrays.stream(expectedIds).boxed().toList()),
                    "Unexpected packaged MATCH results for " + expression + ": " + actual);
        }
    }

    private static long[] ids(List<StoredChunk> chunks) {
        return chunks.stream().mapToLong(StoredChunk::id).sorted().toArray();
    }

    private static Connection openDatabase(Path database) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("foreign_keys", "true");
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), properties);
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private record FtsRow(long id, String content, String headings, String sourcePath) {}

    /** Verifies persisted profiles, atomic replacement, and legacy compatibility through the packaged API. */
    public static void verifyIndexingProfiles(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("profiles.db");
        Path source = Path.of("notes.txt");
        IndexingProfile originalProfile = new IndexingProfile("packaged-tokenizer-v1", 128, 16);
        IndexingProfile replacementProfile = new IndexingProfile("packaged-tokenizer-v2", 64, 8);
        Document original = new Document(
                source,
                DocumentType.PLAIN_TEXT,
                "originalneedle",
                ContentHash.sha256("originalneedle".getBytes(StandardCharsets.UTF_8)));
        Document replacement = new Document(
                source,
                DocumentType.PLAIN_TEXT,
                "replacementneedle",
                ContentHash.sha256("replacementneedle".getBytes(StandardCharsets.UTF_8)));
        List<Chunk> originalChunks = List.of(new Chunk(
                source, DocumentType.PLAIN_TEXT, 0, original.content(), new LineRange(1, 1), ChunkMetadata.empty()));
        List<Chunk> replacementChunks = List.of(new Chunk(
                source, DocumentType.PLAIN_TEXT, 0, replacement.content(), new LineRange(1, 1), ChunkMetadata.empty()));
        StoredFile originalFile;
        List<StoredChunk> persistedChunks;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            originalFile = storage.replaceFile(original, originalChunks, originalProfile);
            persistedChunks = storage.chunks().findByFileId(originalFile.id());
        }
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    storage.files()
                            .findIndexingProfile(originalFile.id())
                            .orElseThrow()
                            .equals(originalProfile),
                    "Packaged profile metadata must survive close and read-only reopen");
            check(
                    storage.chunks().findByFileId(originalFile.id()).equals(persistedChunks),
                    "Profile persistence must preserve the associated chunks");
            List<SearchHit> legacyHits = storage.lexicalSearch().search(new SearchRequest("originalneedle"));
            check(
                    legacyHits.size() == 1 && legacyHits.getFirst().stableId().isEmpty(),
                    "Legacy profile hits must remain searchable without inventing a complete chunking identity");
        }

        try (Connection connection = openDatabase(database)) {
            execute(connection, """
                CREATE TRIGGER fail_profile BEFORE INSERT ON file_indexing_profiles
                BEGIN SELECT RAISE(ABORT, 'injected profile failure'); END
                """);
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            try {
                storage.replaceFile(replacement, replacementChunks, replacementProfile);
                throw new AssertionError("A failed profile write must abort file replacement");
            } catch (SQLException failure) {
                check(
                        failure.getMessage().contains("injected profile failure"),
                        "The packaged failure must come from the profile write");
            }
            check(
                    storage.files().findByPath(source).orElseThrow().equals(originalFile),
                    "Profile failure must roll back the file manifest");
            check(
                    storage.chunks().findByFileId(originalFile.id()).equals(persistedChunks),
                    "Profile failure must roll back chunk replacement");
            check(
                    storage.files()
                            .findIndexingProfile(originalFile.id())
                            .orElseThrow()
                            .equals(originalProfile),
                    "Profile failure must retain the previous profile");
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("originalneedle"))
                                    .size()
                            == 1,
                    "Profile failure must preserve the previous FTS rows");
            check(
                    storage.lexicalSearch()
                            .search(new SearchRequest("replacementneedle"))
                            .isEmpty(),
                    "Profile failure must not publish replacement FTS rows");
            try (Connection connection = openDatabase(database)) {
                execute(connection, "DROP TRIGGER fail_profile");
            }
            StoredFile replaced = storage.replaceFile(replacement, replacementChunks, replacementProfile);
            check(replaced.id() == originalFile.id(), "Profile-aware replacement must retain the file identity");
        }

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    storage.files()
                            .findIndexingProfile(originalFile.id())
                            .orElseThrow()
                            .equals(replacementProfile),
                    "A successful retry must commit the replacement profile");
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("replacementneedle"))
                                    .size()
                            == 1,
                    "A successful retry must commit replacement FTS rows");
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            storage.replaceFile(replacement, replacementChunks);
        }
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    storage.files().findIndexingProfile(originalFile.id()).isEmpty(),
                    "Legacy replacement must clear the profile when chunk compatibility is unknown");
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("replacementneedle"))
                                    .size()
                            == 1,
                    "Legacy replacement must keep the document searchable");
        }
    }

    /** Verifies deterministic identities and atomic profile persistence through the packaged API. */
    public static void verifyChunkIdentities(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("chunk-identities.db");
        Path source = Path.of("docs", "İstanbul.md");
        TokenizerCompatibility tokenizer =
                new TokenizerCompatibility("packaged-wordpiece-v1", "b".repeat(64), 30_522, 256, 2);
        ChunkingIdentity originalIdentity =
                new ChunkingIdentity("packaged-extraction-v1", "packaged-chunking-v1", tokenizer, 128, 16);
        ChunkingIdentity replacementIdentity =
                new ChunkingIdentity("packaged-extraction-v1", "packaged-chunking-v2", tokenizer, 64, 8);
        Document original = new Document(
                source,
                DocumentType.MARKDOWN,
                "originalneedle\nsecondneedle",
                ContentHash.sha256("originalneedle\nsecondneedle".getBytes(StandardCharsets.UTF_8)));
        List<Chunk> originalChunks = List.of(
                new Chunk(
                        source,
                        original.type(),
                        0,
                        "originalneedle",
                        new LineRange(1, 1),
                        new ChunkMetadata(List.of("Başlık"), Map.of("custom", "İ😀"))),
                new Chunk(source, original.type(), 1, "secondneedle", new LineRange(2, 2), ChunkMetadata.empty()));
        Document replacement = new Document(
                source,
                original.type(),
                "replacementneedle",
                ContentHash.sha256("replacementneedle".getBytes(StandardCharsets.UTF_8)));
        List<Chunk> replacementChunks = List.of(new Chunk(
                source, replacement.type(), 0, replacement.content(), new LineRange(1, 1), ChunkMetadata.empty()));
        StoredFile originalFile;
        List<StoredChunk> persistedChunks;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            originalFile = storage.replaceFile(original, originalChunks, originalIdentity);
            persistedChunks = checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks);
            List<ChunkId> originalIds = persistedChunks.stream()
                    .map(chunk -> chunk.stableId().orElseThrow())
                    .toList();
            storage.replaceFile(original, originalChunks, originalIdentity);
            persistedChunks = checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks);
            check(
                    persistedChunks.stream()
                            .map(chunk -> chunk.stableId().orElseThrow())
                            .toList()
                            .equals(originalIds),
                    "Repeated packaged replacement must preserve deterministic chunk IDs");
        }

        byte[] beforeRead = Files.readAllBytes(database);
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks)
                            .equals(persistedChunks),
                    "Full identities, chunks, and metadata must survive read-only reopen");
            checkSearchIdentity(
                    storage.lexicalSearch()
                            .search(new SearchRequest("originalneedle"))
                            .getFirst(),
                    persistedChunks.getFirst(),
                    originalIdentity);
        }
        check(Arrays.equals(beforeRead, Files.readAllBytes(database)), "Reading identities must not modify the index");

        try (Connection connection = openDatabase(database)) {
            execute(connection, """
                CREATE TRIGGER fail_chunking_profile BEFORE INSERT ON file_chunking_profiles
                BEGIN SELECT RAISE(ABORT, 'injected chunking profile failure'); END
                """);
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            try {
                storage.replaceFile(replacement, replacementChunks, replacementIdentity);
                throw new AssertionError("A failed full profile write must abort replacement");
            } catch (SQLException failure) {
                check(
                        failure.getMessage().contains("injected chunking profile failure"),
                        "The packaged failure must come from the final profile write");
            }
            check(
                    storage.files().findByPath(source).orElseThrow().equals(originalFile),
                    "Full profile failure must restore the original file manifest");
            check(
                    checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks)
                            .equals(persistedChunks),
                    "Full profile failure must restore chunks, metadata, profile, and deterministic IDs");
            checkSearchIdentity(
                    storage.lexicalSearch()
                            .search(new SearchRequest("originalneedle"))
                            .getFirst(),
                    persistedChunks.getFirst(),
                    originalIdentity);
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("originalneedle"))
                                    .size()
                            == 1,
                    "Full profile failure must preserve committed search data");
            check(
                    storage.lexicalSearch()
                            .search(new SearchRequest("replacementneedle"))
                            .isEmpty(),
                    "Full profile failure must not expose replacement search data");
            try (Connection connection = openDatabase(database)) {
                execute(connection, "DROP TRIGGER fail_chunking_profile");
            }

            StoredFile replaced = storage.replaceFile(replacement, replacementChunks, replacementIdentity);
            check(replaced.id() == originalFile.id(), "Full profile replacement must retain the file identity");
            List<StoredChunk> replacedChunks =
                    checkChunkIdentities(storage, replaced.id(), replacementIdentity, replacementChunks);
            check(
                    !replacedChunks
                            .getFirst()
                            .stableId()
                            .equals(persistedChunks.getFirst().stableId()),
                    "A changed chunking profile must produce new deterministic IDs");

            storage.replaceFile(replacement, replacementChunks);
            check(
                    storage.files().findChunkingIdentity(replaced.id()).isEmpty(),
                    "Unprofiled replacement must remove the full chunking identity");
            check(
                    storage.chunks().findByFileId(replaced.id()).stream()
                            .allMatch(chunk -> chunk.stableId().isEmpty()),
                    "Unprofiled chunks must expose unknown deterministic identities");
            List<SearchHit> unprofiledHits = storage.lexicalSearch().search(new SearchRequest("replacementneedle"));
            check(
                    unprofiledHits.size() == 1
                            && unprofiledHits.getFirst().stableId().isEmpty(),
                    "Unprofiled replacement must retain searchable content with an unknown deterministic ID");

            storage.replaceFile(replacement, replacementChunks, replacementIdentity);
            try (Connection connection = openDatabase(database)) {
                execute(connection, "UPDATE chunks SET content = 'directneedle' WHERE file_id = ?", replaced.id());
            }
            List<SearchHit> invalidatedHits = storage.lexicalSearch().search(new SearchRequest("directneedle"));
            check(
                    invalidatedHits.size() == 1
                            && invalidatedHits.getFirst().stableId().isEmpty(),
                    "Direct chunk mutation must keep refreshed search content while invalidating deterministic IDs");

            Document empty = new Document(source, original.type(), "", ContentHash.sha256(new byte[0]));
            storage.replaceFile(empty, List.of(), replacementIdentity);
            checkChunkIdentities(storage, replaced.id(), replacementIdentity, List.of());
        }
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            checkChunkIdentities(storage, originalFile.id(), replacementIdentity, List.of());
        }
    }

    private static List<StoredChunk> checkChunkIdentities(
            SqliteStorage storage, long fileId, ChunkingIdentity identity, List<Chunk> expectedChunks)
            throws SQLException {
        ChunkingIdentity persisted =
                storage.files().findChunkingIdentity(fileId).orElseThrow();
        check(
                persisted.equals(identity),
                "The complete extraction, chunking, tokenizer, and budget identity must persist");
        check(
                persisted.fingerprint().equals(identity.fingerprint()),
                "The persisted fingerprint must match its identity");
        List<StoredChunk> chunks = storage.chunks().findByFileId(fileId);
        check(
                chunks.stream().map(StoredChunk::chunk).toList().equals(expectedChunks),
                "Identity persistence must preserve all chunk content, locations, and metadata");
        ChunkIdGenerator generator = new ChunkIdGenerator(identity);
        for (StoredChunk chunk : chunks) {
            check(
                    chunk.stableId().orElseThrow().equals(generator.generate(chunk.chunk())),
                    "The packaged storage ID must match the public deterministic generator");
        }
        return chunks;
    }

    /** Verifies incremental indexing uses complete identities and preserves IDs across content changes. */
    public static void verifyIncrementalChunkIdentities(Path root, Path coreJar, Path runtimeDirectory)
            throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path source = Path.of("guide.md");
        String indexScope = "[index]\ninclude = ['guide.md', 'empty.txt']\n";
        Files.writeString(root.resolve(".pecia.toml"), indexScope);
        Files.writeString(root.resolve(source), "# Guide\n\noriginalneedle\n");
        Files.writeString(root.resolve("empty.txt"), "");
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        IndexService service = new IndexService(loader);
        QueryService query = new QueryService(loader);
        IndexResult first = service.index(root);
        check(
                first.status() == IndexResult.Status.COMPLETE
                        && first.indexedFiles() == 2
                        && first.writtenChunks() == 1,
                "The first index must persist populated and empty files");
        Path database = first.context().databasePath();
        ChunkingIdentity originalIdentity = ChunkingIdentity.from(
                first.context().loadedConfig().config(),
                MiniLmTokenizer.bundled().identity());
        List<StoredChunk> originalChunks;
        StoredFile originalFile;
        SearchHit originalHit;
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            originalFile = storage.files().findByPath(source).orElseThrow();
            originalChunks = checkIndexedIdentities(storage, originalIdentity, 2);
            originalHit =
                    query.search(root, new SearchRequest("originalneedle")).getFirst();
            checkSearchIdentity(originalHit, originalChunks.getFirst(), originalIdentity);
        }

        byte[] beforeRepeat = Files.readAllBytes(database);
        IndexResult repeated = service.index(root);
        check(
                repeated.indexedFiles() == 0 && repeated.unchangedFiles() == 2 && repeated.writtenChunks() == 0,
                "Complete matching identities must skip populated and empty files");
        check(
                query.search(root, new SearchRequest("originalneedle")).equals(List.of(originalHit)),
                "An unchanged indexing pass must preserve the complete search hit and deterministic ID");
        check(
                Arrays.equals(beforeRepeat, Files.readAllBytes(database)),
                "An unchanged indexing run must leave the complete database untouched");

        Files.writeString(root.resolve(source), "# Guide\n\nreplacementneedle\n");
        IndexResult changed = service.index(root);
        check(
                changed.indexedFiles() == 1 && changed.unchangedFiles() == 1 && changed.writtenChunks() == 1,
                "Content changes must reindex the changed file even when its chunk identity is unchanged");
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            StoredFile changedFile = storage.files().findByPath(source).orElseThrow();
            List<StoredChunk> changedChunks = checkIndexedIdentities(storage, originalIdentity, 2);
            check(
                    changedFile.id() == originalFile.id()
                            && !changedFile.contentHash().equals(originalFile.contentHash()),
                    "Content replacement must retain the file ID and update its freshness hash");
            check(
                    changedChunks
                            .getFirst()
                            .stableId()
                            .equals(originalChunks.getFirst().stableId()),
                    "Content changes must preserve path, position, and profile-based chunk IDs");
            SearchHit changedHit =
                    query.search(root, new SearchRequest("replacementneedle")).getFirst();
            checkSearchIdentity(changedHit, changedChunks.getFirst(), originalIdentity);
            check(
                    changedHit.stableId().equals(originalHit.stableId())
                            && changedHit.snippet().contains("replacementneedle")
                            && !changedHit.snippet().contains("originalneedle")
                            && query.search(root, new SearchRequest("originalneedle"))
                                    .isEmpty(),
                    "A stable search ID must accompany refreshed snippets and searchable content");
        }

        Files.writeString(root.resolve(".pecia.toml"), indexScope + "[chunk]\nmax_tokens = 128\noverlap_tokens = 8\n");
        IndexResult rechunked = service.index(root);
        check(
                rechunked.indexedFiles() == 2 && rechunked.unchangedFiles() == 0 && rechunked.writtenChunks() == 1,
                "A chunk budget change must reindex both populated and empty files: " + rechunked);
        ChunkingIdentity updatedIdentity = ChunkingIdentity.from(
                rechunked.context().loadedConfig().config(),
                MiniLmTokenizer.bundled().identity());
        check(!updatedIdentity.equals(originalIdentity), "Changed chunk settings must change the full identity");
        List<StoredChunk> updatedChunks;
        SearchHit updatedHit;
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            updatedChunks = checkIndexedIdentities(storage, updatedIdentity, 2);
            updatedHit =
                    query.search(root, new SearchRequest("replacementneedle")).getFirst();
            checkSearchIdentity(updatedHit, updatedChunks.getFirst(), updatedIdentity);
            check(
                    !updatedChunks
                            .getFirst()
                            .stableId()
                            .equals(originalChunks.getFirst().stableId()),
                    "Chunk budget changes must replace deterministic IDs even when boundaries remain the same");
            check(
                    !updatedHit.stableId().equals(originalHit.stableId()),
                    "Rechunking must expose the replacement identity through the query API");
        }

        Files.writeString(
                root.resolve(".pecia.toml"),
                indexScope + "[chunk]\nmax_tokens = 128\noverlap_tokens = 8\n[embed]\nconcurrency = 4\n");
        IndexResult embeddingOnly = service.index(root);
        check(
                embeddingOnly.indexedFiles() == 0
                        && embeddingOnly.unchangedFiles() == 2
                        && embeddingOnly.writtenChunks() == 0,
                "Embedding concurrency must not invalidate chunking compatibility");
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    checkIndexedIdentities(storage, updatedIdentity, 2).equals(updatedChunks),
                    "An embedding-only setting change must retain chunk rows and identities");
        }
        check(
                query.search(root, new SearchRequest("replacementneedle")).equals(List.of(updatedHit)),
                "An embedding-only setting change must preserve the complete search hit");
    }

    private static void checkSearchIdentity(SearchHit hit, StoredChunk chunk, ChunkingIdentity identity) {
        check(
                hit.chunkId() == chunk.id()
                        && hit.sourcePath().equals(chunk.chunk().sourcePath())
                        && hit.chunkIndex() == chunk.chunk().index(),
                "A packaged search hit must retain the matching persisted row and source position");
        check(
                hit.stableId().equals(chunk.stableId())
                        && hit.stableId().orElseThrow().equals(new ChunkIdGenerator(identity).generate(chunk.chunk())),
                "A packaged search hit must expose the stored deterministic ID and match the public generator");
    }

    /** Verifies V3 migration preserves legacy data until a single indexing pass creates full identities. */
    public static void verifyV3IncrementalUpgrade(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        String content = "legacyneedle";
        Files.writeString(root.resolve("legacy.txt"), content);
        Files.writeString(root.resolve("empty.txt"), "");
        Path database = Files.createDirectories(root.resolve(".pecia")).resolve("index.db");
        IndexingProfile legacyProfile = IndexingProfile.from(
                PeciaConfig.defaults(), MiniLmTokenizer.bundled().identity());
        try (Connection connection = openDatabase(database)) {
            connection.setAutoCommit(false);
            for (String resource : List.of(V1_RESOURCE, V2_RESOURCE, V3_RESOURCE)) {
                try (var input = SqliteStorage.class.getResourceAsStream(resource);
                        var statement = connection.createStatement()) {
                    check(input != null, "Historical migration must be included in the core JAR: " + resource);
                    statement.executeUpdate(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            execute(
                    connection,
                    "INSERT INTO index_metadata VALUES (1, ?, 3)",
                    root.toUri().toASCIIString());
            execute(
                    connection,
                    "INSERT INTO files VALUES (7, 'legacy.txt', 'PLAIN_TEXT', ?)",
                    ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)).value());
            execute(
                    connection,
                    "INSERT INTO files VALUES (9, 'empty.txt', 'PLAIN_TEXT', ?)",
                    ContentHash.sha256(new byte[0]).value());
            execute(connection, "INSERT INTO chunks VALUES (41, 7, 0, ?, 1, 1)", content);
            for (long fileId : List.of(7L, 9L)) {
                execute(
                        connection,
                        "INSERT INTO file_indexing_profiles VALUES (?, ?, ?, ?)",
                        fileId,
                        legacyProfile.tokenizerKey(),
                        legacyProfile.maxTokens(),
                        legacyProfile.overlapTokens());
            }
            execute(connection, "PRAGMA user_version = 3");
            connection.commit();
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            for (StoredFile file : storage.files().findAll()) {
                check(
                        storage.files()
                                .findIndexingProfile(file.id())
                                .orElseThrow()
                                .equals(legacyProfile),
                        "Migration must retain the legacy profile for comparison");
                check(
                        storage.files().findChunkingIdentity(file.id()).isEmpty(),
                        "Schema migration must not guess extraction or chunking compatibility");
            }
            List<StoredChunk> legacyChunks = storage.chunks().findByFileId(7);
            check(
                    legacyChunks.size() == 1
                            && legacyChunks.getFirst().id() == 41
                            && legacyChunks.getFirst().stableId().isEmpty(),
                    "Schema migration must preserve historical rows with unknown deterministic IDs");
            List<SearchHit> legacyHits = storage.lexicalSearch().search(new SearchRequest("legacyneedle"));
            check(
                    legacyHits.size() == 1
                            && legacyHits.getFirst().chunkId() == 41
                            && legacyHits.getFirst().stableId().isEmpty(),
                    "Migration must retain searchable legacy rows without inventing deterministic IDs");
        }

        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        IndexService service = new IndexService(loader);
        QueryService query = new QueryService(loader);
        check(
                query.search(root, new SearchRequest("legacyneedle"))
                        .getFirst()
                        .stableId()
                        .isEmpty(),
                "Read-only queries must expose unknown IDs for migrated legacy content before reindexing");
        IndexResult upgraded = service.index(root);
        check(
                upgraded.status() == IndexResult.Status.COMPLETE
                        && upgraded.indexedFiles() == 2
                        && upgraded.unchangedFiles() == 0
                        && upgraded.writtenChunks() == 1,
                "Legacy profiles must force exactly one reindex, including empty files");
        ChunkingIdentity identity = ChunkingIdentity.from(
                upgraded.context().loadedConfig().config(),
                MiniLmTokenizer.bundled().identity());
        List<StoredChunk> upgradedChunks;
        SearchHit upgradedHit;
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            upgradedChunks = checkIndexedIdentities(storage, identity, 2);
            upgradedHit = query.search(root, new SearchRequest("legacyneedle")).getFirst();
            checkSearchIdentity(upgradedHit, upgradedChunks.getFirst(), identity);
            check(
                    storage.files()
                                            .findByPath(Path.of("legacy.txt"))
                                            .orElseThrow()
                                            .id()
                                    == 7
                            && storage.files()
                                            .findByPath(Path.of("empty.txt"))
                                            .orElseThrow()
                                            .id()
                                    == 9,
                    "Upgrading chunk compatibility must retain existing file identities");
        }
        IndexResult repeated = service.index(root);
        check(
                repeated.indexedFiles() == 0 && repeated.unchangedFiles() == 2 && repeated.writtenChunks() == 0,
                "The second indexing pass must skip upgraded files");
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    checkIndexedIdentities(storage, identity, 2).equals(upgradedChunks),
                    "Skipping migrated files must preserve their new rows and deterministic IDs");
        }
        check(
                query.search(root, new SearchRequest("legacyneedle")).equals(List.of(upgradedHit)),
                "Queries must preserve upgraded IDs after the first unchanged indexing pass");
    }

    private static List<StoredChunk> checkIndexedIdentities(
            SqliteStorage storage, ChunkingIdentity identity, int expectedFiles) throws SQLException {
        List<StoredFile> files = storage.files().findAll();
        check(files.size() == expectedFiles, "The manifest must contain the expected indexed files");
        List<StoredChunk> chunks = new ArrayList<>();
        ChunkIdGenerator generator = new ChunkIdGenerator(identity);
        for (StoredFile file : files) {
            check(
                    storage.files()
                            .findChunkingIdentity(file.id())
                            .orElseThrow()
                            .equals(identity),
                    "Every indexed file, including empty files, must have the complete current identity");
            check(
                    storage.files().findIndexingProfile(file.id()).isEmpty(),
                    "Production indexing must replace the legacy indexing profile");
            for (StoredChunk chunk : storage.chunks().findByFileId(file.id())) {
                check(
                        chunk.stableId().orElseThrow().equals(generator.generate(chunk.chunk())),
                        "Each indexed chunk must expose its expected deterministic ID");
                chunks.add(chunk);
            }
        }
        return List.copyOf(chunks);
    }

    /** Verifies folder indexing, replacement, and search through the packaged core API. */
    public static void verifyFolderIndex(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Files.writeString(root.resolve("Auth.java"), "class Auth { String JWT_SECRET; }");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nİstanbul documentationneedle\n");
        Files.writeString(root.resolve("empty.txt"), "");
        IndexService service = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
        IndexResult result = service.index(root);

        check(result.status() == IndexResult.Status.COMPLETE, "Packaged folder index must complete");
        check(
                result.candidateCount() == 3 && result.indexedFiles() == 3 && result.writtenChunks() == 2,
                "Packaged folder counters must include empty files");
        check(
                result.unchangedFiles() == 0 && result.deletedFiles() == 0,
                "A first packaged index must not report unchanged files or deletions");
        IndexResult repeated = service.index(root);
        check(
                repeated.status() == IndexResult.Status.COMPLETE
                        && repeated.candidateCount() == 3
                        && repeated.indexedFiles() == 0
                        && repeated.unchangedFiles() == 3
                        && repeated.writtenChunks() == 0,
                "Repeated folder indexing must skip unchanged files including empty files");

        try (SqliteStorage storage = SqliteStorage.open(result.context().databasePath(), root)) {
            check(storage.files().findAll().size() == 3, "Packaged manifest must persist all admitted files");

            List<SearchHit> code = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));

            check(
                    code.size() == 1 && code.getFirst().sourcePath().equals(Path.of("Auth.java")),
                    "Packaged indexed code must be searchable");

            List<SearchHit> markdown = storage.lexicalSearch().search(new SearchRequest("documentationneedle"));

            check(
                    markdown.size() == 1
                            && markdown.getFirst().metadata().headingPath().equals(List.of("Guide")),
                    "Packaged indexing must preserve Markdown headings");
        }
    }

    /** Verifies read-only queries after source deletion and failure when the packaged index is missing. */
    public static void verifyReadOnlyQuery(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Files.writeString(root.resolve(".pecia.toml"), "[store]\npath = 'cache/search.db'\n");
        Files.writeString(root.resolve("Auth.java"), "class Auth { String JWT_SECRET; }");
        Path child = Files.createDirectory(root.resolve("docs"));
        Files.writeString(child.resolve("guide.md"), "# Guide\n\nİstanbul documentationneedle\n");
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
        IndexResult indexed = new IndexService(loader).index(root);
        ChunkingIdentity identity = ChunkingIdentity.from(
                indexed.context().loadedConfig().config(),
                MiniLmTokenizer.bundled().identity());
        StoredChunk codeChunk;
        StoredChunk markdownChunk;
        try (SqliteStorage storage =
                SqliteStorage.openReadOnly(indexed.context().databasePath(), root)) {
            long codeFile = storage.files()
                    .findByPath(Path.of("Auth.java"))
                    .orElseThrow()
                    .id();
            long markdownFile = storage.files()
                    .findByPath(Path.of("docs/guide.md"))
                    .orElseThrow()
                    .id();
            codeChunk = storage.chunks().findByFileId(codeFile).getFirst();
            markdownChunk = storage.chunks().findByFileId(markdownFile).getFirst();
        }
        byte[] before = Files.readAllBytes(indexed.context().databasePath());
        Files.delete(root.resolve("Auth.java"));
        Files.delete(child.resolve("guide.md"));
        QueryService query = new QueryService(loader);
        List<SearchHit> code = query.search(child, new SearchRequest("JWT_SECRET", 1));

        check(
                code.size() == 1 && code.getFirst().sourcePath().equals(Path.of("Auth.java")),
                "Packaged query must search the stored project index from a child context");
        check(
                code.getFirst().sourceLocation().equals(new LineRange(1, 1)),
                "Packaged query must preserve source lines");
        checkSearchIdentity(code.getFirst(), codeChunk, identity);

        List<SearchHit> markdown = query.search(root, new SearchRequest("İstanbul documentationneedle"));

        check(
                markdown.size() == 1
                        && markdown.getFirst().metadata().headingPath().equals(List.of("Guide")),
                "Packaged query must preserve Unicode and headings after source deletion");
        check(
                markdown.getFirst().snippet().contains("documentationneedle"),
                "Packaged query must expose the stored snippet");
        checkSearchIdentity(markdown.getFirst(), markdownChunk, identity);
        check(query.search(root, new SearchRequest("absent")).isEmpty(), "No match is a successful empty query");
        check(
                Arrays.equals(before, Files.readAllBytes(indexed.context().databasePath())),
                "Query must not mutate the packaged index");

        Files.delete(indexed.context().databasePath());

        try {
            query.search(root, new SearchRequest("needle"));
            throw new AssertionError("Missing packaged index must fail");
        } catch (QueryException failure) {
            check(failure.reason() == QueryException.Reason.INDEX_NOT_FOUND, "Missing index must be classified");
        }

        check(!Files.exists(indexed.context().databasePath()), "Query must not recreate a missing packaged index");
    }

    /** Verifies lexical ranking, metadata, Unicode queries, and persistence through the packaged core API. */
    public static void verifyLexicalSearch(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("lexical.db");
        String content = "JWT_SECRET authentication middleware café İstanbul";
        var metadata = new ChunkMetadata(List.of("Guide", "Authentication"), Map.of("language", "java"));
        var lines = new LineRange(118, 161);
        try (var storage = SqliteStorage.open(database, root)) {
            // Equal-length paths/content/metadata make the binary path tie-break observable.
            for (String name : List.of("z.java", "a.java")) {
                var path = Path.of(name);
                var document = new Document(
                        path,
                        DocumentType.SOURCE_CODE,
                        content,
                        ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
                storage.replaceFile(document, List.of(new Chunk(path, document.type(), 0, content, lines, metadata)));
            }
            var search = storage.lexicalSearch();
            var hits = search.search(new SearchRequest("JWT_SECRET"));
            check(
                    hits.stream()
                            .map(SearchHit::sourcePath)
                            .toList()
                            .equals(List.of(Path.of("a.java"), Path.of("z.java"))),
                    "Packaged API must preserve deterministic rank ties");
            for (var hit : hits) {
                check(hit.chunkId() > 0 && hit.chunkIndex() == 0, "Packaged hit identity must come from storage");
                check(hit.stableId().isEmpty(), "Unprofiled hits must expose an unknown deterministic identity");
                check(hit.sourceLocation().equals(lines), "Packaged hit must keep the full source line range");
                check(hit.metadata().equals(metadata), "Packaged hit must preserve ordered headings and attributes");
                check(hit.snippet().equals(content), "Packaged hit must expose a plain content snippet");
                check(hit.documentType() == DocumentType.SOURCE_CODE, "Packaged hit must retain source type");
                check(hit.score().kind() == SearchScore.Kind.SQLITE_BM25, "Packaged score must identify BM25");
            }
            check(hits.equals(search.search(new SearchRequest("JWT_SECRET"))), "Repeated searches must be stable");
            check(
                    hits.subList(0, 1).equals(search.search(new SearchRequest("JWT_SECRET", 1))),
                    "Limit must preserve rank");
            check(
                    search.search(new SearchRequest("café İstanbul")).size() == 2,
                    "Unicode queries must work from the JAR");
            check(search.search(new SearchRequest("!!!")).isEmpty(), "Punctuation-only queries must be empty");
            check(search.search(new SearchRequest("missing")).isEmpty(), "No match must be a successful empty result");
        }
        try (var reopened = SqliteStorage.open(database, root)) {
            check(
                    reopened.lexicalSearch()
                                    .search(new SearchRequest("JWT_SECRET"))
                                    .size()
                            == 2,
                    "Public search must work after reopening persisted storage");
        }
    }

    /* Checks both code sources and resource URLs so development outputs cannot mask an incomplete distribution. */
    private static void verifyArchiveOrigins(Path coreJar, Path runtimeDirectory) throws Exception {
        for (Class<?> type : List.of(
                IndexService.class,
                IndexPreview.class,
                PeciaConfigLoader.class,
                PeciaConfigParser.class,
                DocumentExtractionService.class,
                Document.class,
                FileContentLoader.class,
                TextDocumentExtractor.class,
                FileTypeDetector.class,
                MiniLmTokenizer.class,
                DocumentChunkerFactory.class,
                ChunkingIdentity.class,
                ChunkIdGenerator.class,
                MarkdownChunker.class,
                Chunk.class,
                ChunkId.class,
                TokenizerCompatibility.class,
                StoredChunk.class,
                SqliteStorage.class,
                LexicalSearch.class,
                SearchRequest.class,
                SearchHit.class,
                SearchScore.class,
                SearchException.class,
                QueryService.class,
                QueryException.class,
                IndexingProfile.class,
                IndexAccessException.class)) {
            Path origin = Path.of(
                    type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(
                    Files.isSameFile(coreJar, origin),
                    type.getName() + " must load from the packaged core JAR: " + origin);
        }

        for (Class<?> type : List.of(Parser.class, FastIgnoreRule.class, Toml.class, JDBC.class)) {
            Path origin = Path.of(
                    type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(
                    origin.toString().endsWith(".jar") && Files.isSameFile(runtimeDirectory, origin.getParent()),
                    type.getName() + " must load from a packaged runtime dependency: " + origin);
        }

        String tokenizerResources = "/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/";

        for (String name : List.of(
                tokenizerResources + "vocab.txt",
                tokenizerResources + "NOTICE.txt",
                tokenizerResources + "LICENSE.txt",
                "/META-INF/licenses/commonmark-LICENSE.txt",
                V1_RESOURCE,
                V2_RESOURCE,
                V3_RESOURCE,
                V4_RESOURCE)) {
            URL resource = MiniLmTokenizer.class.getResource(name);
            check(resource != null && resource.getProtocol().equals("jar"), "Resource must load from a JAR: " + name);
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            connection.setUseCaches(false);
            Path archive = Path.of(connection.getJarFileURL().toURI());
            check(Files.isSameFile(coreJar, archive), "Resource must belong to the core JAR: " + name);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
