package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.IndexWriteObserver;
import dev.eyuppastirmaci.pecia.testing.LegacyIndexFixture;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * Migrates working copies of indexes written by the code of each earlier schema version. Fixtures from version 4 on
 * already store chunk identities, so reindexing rewrites them only when they were written with another tokenizer
 * algorithm.
 */
class LegacyIndexMigrationTest {

    private static final int CURRENT_VERSION = LegacyIndexFixture.CURRENT_VERSION;
    // The first schema version that stores deterministic chunk identities.
    private static final int IDENTITY_VERSION = 4;
    // The source rows a migration must carry over unchanged, keyed by table in primary-key order.
    private static final List<String> PRESERVED_ROWS = List.of(
            "SELECT id, source_path, document_type, content_hash FROM files ORDER BY id",
            "SELECT id, file_id, chunk_index, content, start_line, end_line FROM chunks ORDER BY id",
            "SELECT chunk_id, position, heading FROM chunk_headings ORDER BY chunk_id, position",
            "SELECT chunk_id, name, value FROM chunk_attributes ORDER BY chunk_id, name");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService indexService = new IndexService(loader);
    private final QueryService queryService = new QueryService(loader);

    @ParameterizedTest(name = "schema v{0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void queriesRequireMigrationWithoutChangingTheIndex(int version) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Path database = LegacyIndexFixture.install(version, root);
        byte[] before = Files.readAllBytes(database);

        // Every fixture is older than the current schema.
        QueryException failure =
                assertThrows(QueryException.class, () -> queryService.search(root, new SearchRequest("payment")));
        assertEquals(QueryException.Reason.MIGRATION_REQUIRED, failure.reason());

        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @ParameterizedTest(name = "schema v{0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void migrationPreservesStoredRowsAndBackfillsFullTextSearch(int version) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Path database = LegacyIndexFixture.install(version, root);
        List<List<List<Object>>> before = preservedRows(database);
        assertEquals(14, before.get(1).size(), "Every fixture stores the corpus's 14 chunks");
        List<List<Object>> indexingProfiles =
                version == 3 ? rows(database, "SELECT * FROM file_indexing_profiles ORDER BY file_id") : List.of();

        SqliteStorage.open(database, root).close();

        assertEquals(List.of(CURRENT_VERSION, CURRENT_VERSION), versions(database));
        assertEquals(before, preservedRows(database));
        if (version == 3) {
            assertEquals(indexingProfiles, rows(database, "SELECT * FROM file_indexing_profiles ORDER BY file_id"));
        }
        assertEquals(
                rows(database, "SELECT id, content FROM chunks ORDER BY id"),
                rows(database, "SELECT rowid, content FROM chunks_fts ORDER BY rowid"),
                "Full-text rows must mirror the chunks after migration");
        // Chunks migrated from before identities have none until reindexing; later fixtures already store them.
        assertEquals(
                version < IDENTITY_VERSION ? 0 : count(database, "SELECT count(*) FROM chunks"),
                count(database, "SELECT count(*) FROM chunk_identities"));
        // Every fixture predates embeddings, so migrated indexes start without vectors.
        assertEquals(0, count(database, "SELECT count(*) FROM embedding_profiles"));
        assertEquals(0, count(database, "SELECT count(*) FROM chunk_embeddings"));
    }

    @ParameterizedTest(name = "schema v{0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void migratedIndexAnswersQueriesWithStableIdsOnlyWhereTheSourceHadThem(int version) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Path database = LegacyIndexFixture.install(version, root);
        SqliteStorage.open(database, root).close();

        for (String query : LexicalTestCorpus.QUERIES) {
            List<SearchHit> hits = queryService.search(root, new SearchRequest(query));

            assertFalse(hits.isEmpty(), query);
            assertTrue(
                    hits.stream().allMatch(hit -> hit.stableId().isPresent() == (version >= IDENTITY_VERSION)),
                    "Stable IDs for '" + query + "'");
        }
    }

    @ParameterizedTest(name = "schema v{0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void reindexingRewritesLegacyRecordsOnceAndMatchesAFreshIndex(int version) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Path database = LegacyIndexFixture.install(version, root);
        SqliteStorage.open(database, root).close();
        boolean rewritten = version < IDENTITY_VERSION || !usesCurrentTokenizer(database);
        IndexWriteObserver observer = IndexWriteObserver.start(database);

        IndexResult result = indexService.index(root);
        IndexWriteObserver.Writes writes = observer.finish();

        int corpusSize = LexicalTestCorpus.files().size();
        TreeSet<String> corpus = new TreeSet<>();
        LexicalTestCorpus.files().keySet().forEach(path -> corpus.add(SqlitePath.encode(path)));
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(corpusSize, result.candidateCount());
        assertEquals(0, result.deletedFiles());
        if (rewritten) {
            assertEquals(corpusSize, result.indexedFiles());
            assertEquals(corpus, writes.writtenFiles());
        } else {
            assertEquals(0, result.indexedFiles());
            assertEquals(0, writes.writeCount());
        }

        Path fresh = Files.createDirectory(sandbox.resolve("fresh")).toRealPath();
        LexicalTestCorpus.write(fresh);
        IndexResult freshResult = indexService.index(fresh);
        assertEquals(
                IndexDump.read(freshResult.context().databasePath(), fresh, LexicalTestCorpus.QUERIES),
                IndexDump.read(database, root, LexicalTestCorpus.QUERIES));

        IndexWriteObserver rerun = IndexWriteObserver.start(database);
        assertEquals(corpusSize, indexService.index(root).unchangedFiles());
        assertEquals(0, rerun.finish().writeCount(), "A follow-up run must write nothing");
    }

    /** Returns whether every stored chunking profile was written with the current tokenizer algorithm. */
    private static boolean usesCurrentTokenizer(Path database) throws SQLException {
        String current = MiniLmTokenizer.bundled().identity().algorithm();

        return rows(database, "SELECT DISTINCT tokenizer_algorithm FROM file_chunking_profiles")
                .equals(List.of(List.of(current)));
    }

    private static List<List<List<Object>>> preservedRows(Path database) throws SQLException {
        List<List<List<Object>>> tables = new ArrayList<>();

        for (String query : PRESERVED_ROWS) {
            tables.add(rows(database, query));
        }

        return tables;
    }

    private static List<Integer> versions(Path database) throws SQLException {
        List<Integer> versions = new ArrayList<>();

        try (Connection connection = open(database);
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                versions.add(result.getInt(1));
            }
            try (var result = statement.executeQuery("SELECT index_format_version FROM index_metadata")) {
                versions.add(result.getInt(1));
            }
        }

        return versions;
    }

    private static List<List<Object>> rows(Path database, String query) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();

        try (Connection connection = open(database);
                var statement = connection.createStatement();
                var result = statement.executeQuery(query)) {
            int columns = result.getMetaData().getColumnCount();

            while (result.next()) {
                List<Object> row = new ArrayList<>();

                for (int column = 1; column <= columns; column++) {
                    row.add(result.getObject(column));
                }

                rows.add(row);
            }
        }

        return rows;
    }

    private static long count(Path database, String query) throws SQLException {
        return ((Number) rows(database, query).getFirst().getFirst()).longValue();
    }

    private static Connection open(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }
}
