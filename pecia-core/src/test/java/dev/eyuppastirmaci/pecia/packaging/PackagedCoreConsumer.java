package dev.eyuppastirmaci.pecia.packaging;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
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
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.LexicalSearch;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.commonmark.parser.Parser;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.tomlj.Toml;
import org.sqlite.JDBC;

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

public final class PackagedCoreConsumer {
    private static final String V1_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    private static final String V2_RESOURCE = "/db/migration/V2__add_chunk_fts.sql";

    private PackagedCoreConsumer() {
    }

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
        check(preview.walkResult().files().equals(List.of(Path.of("keep.md"))),
                "Preview must apply include, exclude, and inherited Git ignore rules with target-relative paths");

        DocumentType type = new FileTypeDetector().detect(source).orElseThrow();
        DocumentExtractionService extraction = new DocumentExtractionService(
                new FileContentLoader(preview.loadedConfig().config().maxFileBytes()), new TextDocumentExtractor());
        Document document = extraction.extract(new ExtractionRequest(source, Path.of("docs/keep.md"), type));
        check(document.type() == DocumentType.MARKDOWN, "The source must be detected as Markdown");
        check(document.content().equals(markdown), "UTF-8 extraction must preserve the content");
        check(document.contentHash().equals(ContentHash.sha256(markdown.getBytes(StandardCharsets.UTF_8))),
                "Extraction must retain the raw-byte hash");

        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        check(tokenizer.count("Hello world!") == 3, "Bundled WordPiece tokenization must work offline");
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer,
                preview.loadedConfig().config().maxTokens(), preview.loadedConfig().config().overlapTokens());
        check(factory.getChunker(document) instanceof MarkdownChunker, "Markdown must use its dedicated strategy");
        List<Chunk> chunks = factory.getChunker(document).chunk(document);
        check(chunks.stream().map(chunk -> chunk.metadata().headingPath()).toList()
                    .equals(List.of(List.of("Installation"), List.of("Installation", "Windows"))),
                "CommonMark chunking must preserve the heading hierarchy");

        for (Chunk chunk : chunks) {
            check(chunk.sourcePath().equals(Path.of("docs/keep.md")), "Chunks must preserve the source identity");
            check(tokenizer.countModelInput(chunk.content()) <= 64, "Chunks must respect the loaded token budget");
        }

        check(Files.readString(root.resolve(".pecia.toml")).equals(configuration), "The source config must be unchanged");
        check(!Files.exists(root.resolve(".pecia")) && !Files.exists(docs.resolve(".pecia")),
                "The shared engine must not create an index during these operations");
        check(!Files.exists(docs.resolve(".pecia.toml")), "Preview must not create a config in its target");

        Path database = root.resolve(".pecia/index.db");

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            check(Files.size(database) > 0, "Packaged SQLite must initialize a file database");
            var storedFile = storage.replaceFile(document, chunks);
            check(storage.replaceFile(document, chunks).id() == storedFile.id(),
                    "Packaged replacement must retain the file ID across repeated indexing");
            checkOriginalSearch(database, storage.chunks().findByFileId(storedFile.id()));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            check(Files.isRegularFile(database), "Packaged SQLite must reopen the initialized database");
            var stored = storage.files().findByPath(document.sourcePath()).orElseThrow();
            check(stored.contentHash().equals(document.contentHash()) && stored.documentType() == document.type(),
                    "Packaged file repository must preserve the manifest across reopening");
            check(storage.chunks().findByFileId(stored.id()).stream().map(value -> value.chunk()).toList().equals(chunks),
                    "Packaged chunk repository must preserve exact chunks and metadata across reopening");
            checkOriginalSearch(database, storage.chunks().findByFileId(stored.id()));
        }

        verifyStorageRecovery(root, database, document, chunks, factory);
        verifyStorageFamilies(root, extraction, factory);
    }

    /** Verifies historical backfill through the public API without any source file or development resource. */
    public static void verifyV1Migration(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("legacy.db");
        Path source = Path.of("archive/legacypath.md");
        ContentHash hash = ContentHash.sha256("historical raw bytes".getBytes(StandardCharsets.UTF_8));
        StoredFile file = new StoredFile(7, source, DocumentType.MARKDOWN, hash);
        List<StoredChunk> expected = List.of(
                new StoredChunk(41, file.id(), new Chunk(source, file.documentType(), 0,
                        "legacybody İstanbul 😀\r\nunchanged", new LineRange(3, 4),
                        new ChunkMetadata(List.of("Legacyparent", "Legacychild"),
                                Map.of("startOffset", "3", "endOffset", "40", "custom", "quote ' \r\nİ😀")))),
                new StoredChunk(42, file.id(), new Chunk(source, file.documentType(), 1,
                        "legacysecond é", new LineRange(8, 8), ChunkMetadata.empty())));

        try (Connection connection = openDatabase(database)) {
            connection.setAutoCommit(false);
            try (var input = SqliteStorage.class.getResourceAsStream(V1_RESOURCE);
                 var statement = connection.createStatement()) {
                check(input != null, "Historical schema must exist in the core JAR");
                statement.executeUpdate(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            execute(connection, "INSERT INTO index_metadata VALUES (1, ?, 1)", root.toUri().toASCIIString());
            execute(connection, "INSERT INTO files VALUES (?, ?, ?, ?)", file.id(), "archive/legacypath.md",
                    file.documentType().name(), hash.value());
            execute(connection, "INSERT INTO files VALUES (9, 'empty.md', 'PLAIN_TEXT', ?)", hash.value());
            for (StoredChunk stored : expected) {
                Chunk chunk = stored.chunk();
                LineRange lines = (LineRange) chunk.sourceLocation();
                execute(connection, "INSERT INTO chunks VALUES (?, ?, ?, ?, ?, ?)", stored.id(), file.id(),
                        chunk.index(), chunk.content(), lines.startLine(), lines.endLine());
                // Insert in reverse order to prove backfill uses heading positions, not insertion order.
                for (int position = chunk.metadata().headingPath().size() - 1; position >= 0; position--) {
                    execute(connection, "INSERT INTO chunk_headings VALUES (?, ?, ?)", stored.id(), position,
                            chunk.metadata().headingPath().get(position));
                }
                for (var attribute : chunk.metadata().attributes().entrySet()) {
                    execute(connection, "INSERT INTO chunk_attributes VALUES (?, ?, ?)", stored.id(),
                            attribute.getKey(), attribute.getValue());
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
                check(storage.files().findByPath(source).orElseThrow().equals(file),
                        "Migration must preserve file ID, type, path and hash");
                check(storage.chunks().findByFileId(file.id()).equals(expected),
                        "Migration must preserve chunk IDs, text, headings, attributes, offsets and line ranges");
                check(storage.files().findByPath(Path.of("empty.md")).orElseThrow().id() == 9,
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
    private static void verifyStorageRecovery(Path root, Path database, Document document, List<Chunk> chunks,
                                              DocumentChunkerFactory factory) throws Exception {
        try (var connection = JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER fail_packaged_write BEFORE INSERT ON chunk_attributes
                    BEGIN SELECT RAISE(ABORT, 'packaged write failure'); END;
                    """);
        }

        String text = "# Güncel 😀\r\nYeni içerik, é ve ı.\r\n";
        Document replacement = new Document(document.sourcePath(), DocumentType.MARKDOWN, text,
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

            check(storage.files().findByPath(document.sourcePath()).orElseThrow().equals(before),
                    "Failed replacement must restore the complete manifest");
            check(storage.chunks().findByFileId(before.id()).equals(beforeChunks),
                    "Failed replacement must preserve chunk IDs and every metadata value");
            checkOriginalSearch(database, beforeChunks);
            checkMatches(database, "yeni OR güncel");
        }

        try (var connection = JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER fail_packaged_write");
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            var before = storage.files().findByPath(document.sourcePath()).orElseThrow();
            check(before.contentHash().equals(document.contentHash()), "Rollback must survive database reopening");
            check(storage.chunks().findByFileId(before.id()).stream().map(value -> value.chunk()).toList().equals(chunks),
                    "Original chunk content must survive rollback and reopening");
            checkOriginalSearch(database, storage.chunks().findByFileId(before.id()));
            checkMatches(database, "yeni OR güncel");
            var saved = storage.replaceFile(replacement, updated);
            check(saved.id() == before.id(), "Retry must preserve the file ID");
            check(saved.contentHash().equals(replacement.contentHash()), "Retry must update the raw-byte hash");
            check(storage.chunks().findByFileId(saved.id()).stream().map(value -> value.chunk()).toList().equals(updated),
                    "Retry must persist the replacement chunks");
            checkReplacementSearch(database, storage.chunks().findByFileId(saved.id()));
        }

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            var saved = storage.files().findByPath(document.sourcePath()).orElseThrow();
            check(storage.chunks().findByFileId(saved.id()).stream().map(value -> value.chunk()).toList().equals(updated),
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

        try (var connection = JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT (SELECT count(*) FROM files) + (SELECT count(*) FROM chunks)
                          + (SELECT count(*) FROM chunk_headings) + (SELECT count(*) FROM chunk_attributes)
                     """)) {
            check(rows.next() && rows.getInt(1) == 0, "Packaged cascade deletion must leave no orphan metadata");
        }
    }

    /* Runs extraction, real chunking and persistence for every text family without any embedding runtime. */
    private static void verifyStorageFamilies(Path root, DocumentExtractionService extraction,
                                              DocumentChunkerFactory factory) throws Exception {
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
                check(saved.contentHash().equals(ContentHash.sha256(Files.readAllBytes(source))),
                        "Stored hash must include raw BOM and CRLF bytes");
                check(storage.chunks().findByFileId(saved.id()).stream().map(value -> value.chunk()).toList().equals(expected),
                        "Stored family chunks must preserve exact extraction/chunking output: " + type);
                var storedChunks = storage.chunks().findByFileId(saved.id());
                checkIndex(database, storedChunks);
                checkMatches(database, "source_path: docs", ids(storedChunks));
                long[] bodyIds = ids(storedChunks.stream().filter(value -> value.chunk().content().contains("son")).toList());
                check(bodyIds.length > 0, "The family fixture must have searchable body content: " + type);
                checkMatches(database, "content: son", bodyIds);
                Document empty = new Document(relative, type, "", ContentHash.sha256(new byte[0]));
                check(storage.replaceFile(empty, List.of()).id() == saved.id(), "Empty replacement must retain file identity");
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
                 var result = statement.executeQuery("SELECT rowid, content, headings, source_path FROM chunks_fts ORDER BY rowid")) {
                while (result.next()) {
                    actual.add(new FtsRow(result.getLong(1), result.getString(2), result.getString(3), result.getString(4)));
                }
            }
            List<FtsRow> expectedRows = expected.stream().sorted(Comparator.comparingLong(StoredChunk::id))
                    .map(stored -> new FtsRow(stored.id(), stored.chunk().content(),
                            String.join(" ", stored.chunk().metadata().headingPath()),
                            stored.chunk().sourcePath().toString().replace('\\', '/'))).toList();
            check(actual.equals(expectedRows), "Packaged FTS rows must preserve source IDs and all searchable fields: " + actual);
            execute(connection, "INSERT INTO chunks_fts(chunks_fts) VALUES ('integrity-check')");
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
                check(result.next() && result.getInt(1) == 2, "Packaged storage must use schema version 2");
            }
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT index_format_version FROM index_metadata WHERE singleton = 1")) {
                check(result.next() && result.getInt(1) == 2, "Packaged storage must use index format 2");
            }
        }
    }

    private static void checkMatches(Path database, String expression, long... expectedIds) throws SQLException {
        try (Connection connection = openDatabase(database);
             var query = connection.prepareStatement("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY rowid")) {
            query.setString(1, expression);
            List<Long> actual = new ArrayList<>();
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getLong(1));
                }
            }
            check(actual.equals(Arrays.stream(expectedIds).boxed().toList()),
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

    private record FtsRow(long id, String content, String headings, String sourcePath) { }

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
                var document = new Document(path, DocumentType.SOURCE_CODE, content,
                        ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
                storage.replaceFile(document, List.of(new Chunk(path, document.type(), 0, content, lines, metadata)));
            }
            var search = storage.lexicalSearch();
            var hits = search.search(new SearchRequest("JWT_SECRET"));
            check(hits.stream().map(SearchHit::sourcePath).toList().equals(List.of(Path.of("a.java"), Path.of("z.java"))),
                    "Packaged API must preserve deterministic rank ties");
            for (var hit : hits) {
                check(hit.chunkId() > 0 && hit.chunkIndex() == 0, "Packaged hit identity must come from storage");
                check(hit.sourceLocation().equals(lines), "Packaged hit must keep the full source line range");
                check(hit.metadata().equals(metadata), "Packaged hit must preserve ordered headings and attributes");
                check(hit.snippet().equals(content), "Packaged hit must expose a plain content snippet");
                check(hit.documentType() == DocumentType.SOURCE_CODE, "Packaged hit must retain source type");
                check(hit.score().kind() == SearchScore.Kind.SQLITE_BM25, "Packaged score must identify BM25");
            }
            check(hits.equals(search.search(new SearchRequest("JWT_SECRET"))), "Repeated searches must be stable");
            check(hits.subList(0, 1).equals(search.search(new SearchRequest("JWT_SECRET", 1))), "Limit must preserve rank");
            check(search.search(new SearchRequest("café İstanbul")).size() == 2, "Unicode queries must work from the JAR");
            check(search.search(new SearchRequest("!!!")).isEmpty(), "Punctuation-only queries must be empty");
            check(search.search(new SearchRequest("missing")).isEmpty(), "No match must be a successful empty result");
        }
        try (var reopened = SqliteStorage.open(database, root)) {
            check(reopened.lexicalSearch().search(new SearchRequest("JWT_SECRET")).size() == 2,
                    "Public search must work after reopening persisted storage");
        }
    }

    /* Checks both code sources and resource URLs so development outputs cannot mask an incomplete distribution. */
    private static void verifyArchiveOrigins(Path coreJar, Path runtimeDirectory) throws Exception {
        for (Class<?> type : List.of(IndexService.class, IndexPreview.class, PeciaConfigLoader.class,
                PeciaConfigParser.class, DocumentExtractionService.class, Document.class, FileContentLoader.class,
                TextDocumentExtractor.class, FileTypeDetector.class, MiniLmTokenizer.class,
                DocumentChunkerFactory.class, MarkdownChunker.class, Chunk.class, SqliteStorage.class,
                LexicalSearch.class, SearchRequest.class, SearchHit.class, SearchScore.class, SearchException.class)) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(Files.isSameFile(coreJar, origin), type.getName() + " must load from the packaged core JAR: " + origin);
        }

        for (Class<?> type : List.of(Parser.class, FastIgnoreRule.class, Toml.class, JDBC.class)) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(origin.toString().endsWith(".jar") && Files.isSameFile(runtimeDirectory, origin.getParent()),
                    type.getName() + " must load from a packaged runtime dependency: " + origin);
        }

        String tokenizerResources = "/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/";

        for (String name : List.of(tokenizerResources + "vocab.txt", tokenizerResources + "NOTICE.txt",
                tokenizerResources + "LICENSE.txt", "/META-INF/licenses/commonmark-LICENSE.txt",
                V1_RESOURCE, V2_RESOURCE)) {
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
