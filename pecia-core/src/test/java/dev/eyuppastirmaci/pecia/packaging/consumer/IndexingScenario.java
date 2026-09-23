package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkIndexedIdentities;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkSearchIdentity;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Verifies folder indexing and incremental identity preservation through the packaged services. */
final class IndexingScenario {

    private IndexingScenario() {}

    /** Verifies folder indexing, replacement, and search through the packaged core API. */
    static void verifyFolderIndex(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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

    /** Verifies incremental indexing uses complete identities and preserves IDs across content changes. */
    static void verifyIncrementalChunkIdentities(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
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
}
