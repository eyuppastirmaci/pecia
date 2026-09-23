package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.V1_RESOURCE;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.V2_RESOURCE;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.V3_RESOURCE;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkIndex;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkIndexedIdentities;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkMatches;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkSearchIdentity;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.execute;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.openDatabase;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** Verifies V1 and V3 index upgrades using only the migrations bundled in the core JAR. */
final class MigrationScenario {

    private MigrationScenario() {}

    /**
     * Verifies historical backfill through the public API without any source file or development
     * resource.
     */
    static void verifyV1Migration(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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

    /** Verifies V3 migration preserves legacy data until a single indexing pass creates full identities. */
    static void verifyV3IncrementalUpgrade(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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
}
