package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueryServiceTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final QueryService service = new QueryService(loader);

    @Test
    void queriesStoredCorpusFromChildWithCustomStoreWithoutReadingSources() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[store]\npath = 'cache/search.db'\n");
        Files.writeString(root.resolve("Auth.java"), "class Auth { String JWT_SECRET; }");
        Path child = Files.createDirectory(root.resolve("docs"));
        Files.writeString(child.resolve("guide.md"), "# Guide\n\nİstanbul café documentation\n");
        IndexResult indexed = new IndexService(loader).index(root);
        ChunkingIdentity identity = ChunkingIdentity.from(
                indexed.context().loadedConfig().config(),
                MiniLmTokenizer.bundled().identity());
        ChunkIdGenerator generator = new ChunkIdGenerator(identity);
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
        // Broken ignore files would make discovery incomplete, but query must never scan.
        Files.createDirectory(child.resolve(".gitignore"));
        List<SearchHit> hits = service.search(child, new SearchRequest("JWT_SECRET"));

        assertEquals(Path.of("Auth.java"), hits.getFirst().sourcePath());
        assertEquals(codeChunk.id(), hits.getFirst().chunkId());
        assertEquals(codeChunk.stableId(), hits.getFirst().stableId());
        assertEquals(
                generator.generate(codeChunk.chunk()),
                hits.getFirst().stableId().orElseThrow());
        assertEquals(hits, service.search(root, new SearchRequest("JWT_SECRET")));

        List<SearchHit> markdown = service.search(child, new SearchRequest("İstanbul café"));

        assertEquals(1, markdown.size());
        assertEquals(List.of("Guide"), markdown.getFirst().metadata().headingPath());
        assertTrue(markdown.getFirst().snippet().contains("İstanbul café"));
        assertEquals(markdownChunk.id(), markdown.getFirst().chunkId());
        assertEquals(markdownChunk.stableId(), markdown.getFirst().stableId());
        assertEquals(
                generator.generate(markdownChunk.chunk()),
                markdown.getFirst().stableId().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> hits.clear());
        assertArrayEquals(before, Files.readAllBytes(indexed.context().databasePath()));
    }

    @Test
    void honorsLimitAndLiteralSyntaxAndReturnsEmptyForValidNoMatchQueries() throws Exception {
        for (String name : List.of("a", "b", "c")) {
            Files.writeString(root.resolve(name + ".txt"), "alpha OR beta");
        }

        new IndexService(loader).index(root);
        List<SearchHit> hits = service.search(root, new SearchRequest("alpha OR beta"));

        assertEquals(3, hits.size());
        assertEquals(hits.subList(0, 2), service.search(root, new SearchRequest("alpha OR beta", 2)));
        assertTrue(service.search(root, new SearchRequest("missing OR beta")).isEmpty());
        assertTrue(service.search(root, new SearchRequest("!!!")).isEmpty());
        assertTrue(service.search(root, new SearchRequest("absent")).isEmpty());
    }

    @Test
    void reportsMissingIndexWithoutCreatingFilesEvenForPunctuation() throws Exception {
        for (String text : List.of("word", "!!!")) {
            QueryException failure =
                    assertThrows(QueryException.class, () -> service.search(root, new SearchRequest(text)));

            assertEquals(QueryException.Reason.INDEX_NOT_FOUND, failure.reason());
            assertTrue(failure.getMessage().contains("index"));
        }

        try (Stream<Path> files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void validatesRequestBeforeOpeningStorageAndClosesSuccessfulSearch() throws Exception {
        Files.writeString(root.resolve("a.txt"), "needle");
        new IndexService(loader).index(root);
        AtomicReference<SqliteStorage> captured = new AtomicReference<SqliteStorage>();
        QueryService tracking = new QueryService(loader, context -> {
            SqliteStorage storage = SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
            captured.set(storage);

            return storage;
        });

        assertThrows(IllegalArgumentException.class, () -> tracking.search(root, new SearchRequest("a ".repeat(65))));
        assertThrows(IllegalArgumentException.class, () -> tracking.search(root, new SearchRequest(" ")));
        assertThrows(IllegalArgumentException.class, () -> tracking.search(root, new SearchRequest("a", 0)));
        assertThrows(NullPointerException.class, () -> tracking.search(root, null));
        assertNull(captured.get());
        assertEquals(1, tracking.search(root, new SearchRequest("needle")).size());
        assertThrows(SQLException.class, () -> captured.get().files().findAll());
    }

    @Test
    void closesAfterMappingFailurePreservesCauseAndAllowsRetry() throws Exception {
        Files.writeString(root.resolve("a.md"), "# Heading\n\nneedle");
        IndexResult indexed = new IndexService(loader).index(root);
        Path database = indexed.context().databasePath();
        execute(database, "UPDATE chunk_headings SET position = 2");
        AtomicReference<SqliteStorage> captured = new AtomicReference<SqliteStorage>();
        QueryService tracking = new QueryService(loader, context -> {
            SqliteStorage storage = SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
            captured.set(storage);

            return storage;
        });
        QueryException failure =
                assertThrows(QueryException.class, () -> tracking.search(root, new SearchRequest("needle")));

        assertEquals(QueryException.Reason.READ_FAILED, failure.reason());
        assertInstanceOf(SearchException.class, failure.getCause());
        assertThrows(SQLException.class, () -> captured.get().files().findAll());

        execute(database, "UPDATE chunk_headings SET position = 0");

        assertEquals(1, tracking.search(root, new SearchRequest("needle")).size());
    }

    @ParameterizedTest
    @CsvSource({
        "PRAGMA user_version = 77,INCOMPATIBLE_INDEX",
        "UPDATE index_metadata SET index_format_version = 77,INCOMPATIBLE_INDEX",
        "DROP TRIGGER chunks_fts_insert,CORRUPT_INDEX",
        "UPDATE index_metadata SET project_root_uri = 'file:///elsewhere/',WRONG_PROJECT"
    })
    void surfacesIndexValidationCategoriesWithoutChangingTheIndex(String mutation, QueryException.Reason reason)
            throws Exception {
        IndexResult indexed = new IndexService(loader).index(root);
        execute(indexed.context().databasePath(), mutation);
        byte[] before = Files.readAllBytes(indexed.context().databasePath());
        QueryException failure =
                assertThrows(QueryException.class, () -> service.search(root, new SearchRequest("needle")));

        assertEquals(reason, failure.reason());
        assertNotNull(failure.getCause());
        assertArrayEquals(before, Files.readAllBytes(indexed.context().databasePath()));
    }

    @Test
    void readsEmptyIndexAndAbsoluteExternalStore() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        Path database = root.resolve("external/index.db");
        Files.writeString(project.resolve(".pecia.toml"), "[store]\npath = '" + database + "'\n");
        new IndexService(loader).index(project);

        assertTrue(service.search(project, new SearchRequest("absent")).isEmpty());
    }

    private static void execute(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute(sql);
        }
    }
}
