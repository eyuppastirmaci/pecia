package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkSearchIdentity;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Verifies lexical search and read-only queries through the packaged search API. */
final class RetrievalScenario {

    private RetrievalScenario() {}

    /** Verifies lexical ranking, metadata, Unicode queries, and persistence through the packaged core API. */
    static void verifyLexicalSearch(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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

    /** Verifies read-only queries after source deletion and failure when the packaged index is missing. */
    static void verifyReadOnlyQuery(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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
}
