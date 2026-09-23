package dev.eyuppastirmaci.pecia.packaging.consumer;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.commonmark.parser.Parser;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.sqlite.JDBC;
import org.tomlj.Toml;

/** Assertions, JDBC probes and archive-origin checks shared by the packaged-core scenarios. */
final class ConsumerChecks {

    static final String V1_RESOURCE = "/db/migration/V1__create_initial_schema.sql";
    static final String V2_RESOURCE = "/db/migration/V2__add_chunk_fts.sql";
    static final String V3_RESOURCE = "/db/migration/V3__add_file_indexing_profiles.sql";
    static final String V4_RESOURCE = "/db/migration/V4__add_chunk_identities.sql";

    private ConsumerChecks() {}

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /* Checks both code sources and resource URLs so development outputs cannot mask an incomplete distribution. */
    static void verifyArchiveOrigins(Path coreJar, Path runtimeDirectory) throws Exception {
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

    /* All observations use a separate JDBC connection after public storage writes have committed. */
    static void checkIndex(Path database, List<StoredChunk> expected) throws Exception {
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

    static void checkMatches(Path database, String expression, long... expectedIds) throws SQLException {
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

    static long[] ids(List<StoredChunk> chunks) {
        return chunks.stream().mapToLong(StoredChunk::id).sorted().toArray();
    }

    static Connection openDatabase(Path database) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("foreign_keys", "true");
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), properties);
    }

    static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    static void checkSearchIdentity(SearchHit hit, StoredChunk chunk, ChunkingIdentity identity) {
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

    static List<StoredChunk> checkIndexedIdentities(SqliteStorage storage, ChunkingIdentity identity, int expectedFiles)
            throws SQLException {
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

    record FtsRow(long id, String content, String headings, String sourcePath) {}
}
