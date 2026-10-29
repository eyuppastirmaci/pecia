package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertIndexSummary;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertNoStackTrace;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.initialized;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliIndexingIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
    }

    @Test
    void defaultIndexPersistsManifestChunksAndFtsWithoutAccumulatingReplacements() throws Exception {
        Files.writeString(project.resolve("notes.txt"), "legacyneedle notes\n");
        Files.writeString(project.resolve("README.md"), "# Guide\n\nAPI_TOKEN configuration.\n");
        Files.createDirectory(project.resolve("src"));
        String source = "class Auth { String token = \"JWT_SECRET\"; }\n";
        Files.writeString(project.resolve("src/Auth.java"), source);
        Path database = project.resolve(".pecia/index.db");
        Path initializationLog = sandbox.resolve("index-initialization.log");

        Result first = cli.run(project, List.of(OfflineSandbox.initializationLogArgument(initializationLog)), "index");

        assertIndexSummary(first, database, 3, 3);
        assertTrue(initialized(initializationLog, MiniLmTokenizer.class));
        assertFalse(Files.exists(project.resolve(".pecia.toml")));

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            List<StoredFile> files = storage.files().findAll();

            assertEquals(
                    List.of(Path.of("README.md"), Path.of("notes.txt"), Path.of("src/Auth.java")),
                    files.stream().map(file -> file.sourcePath()).toList());

            for (StoredFile file : files) {
                assertEquals(1, storage.chunks().findByFileId(file.id()).size());
            }

            StoredFile auth =
                    storage.files().findByPath(Path.of("src/Auth.java")).orElseThrow();
            assertEquals(DocumentType.SOURCE_CODE, auth.documentType());
            assertEquals(ContentHash.sha256(source.getBytes(StandardCharsets.UTF_8)), auth.contentHash());

            List<SearchHit> codeHits = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));
            assertEquals(1, codeHits.size());
            assertEquals(auth.sourcePath(), codeHits.getFirst().sourcePath());
            assertEquals(new LineRange(1, 1), codeHits.getFirst().sourceLocation());

            List<SearchHit> markdownHits = storage.lexicalSearch().search(new SearchRequest("API_TOKEN"));
            assertEquals(1, markdownHits.size());
            assertEquals(Path.of("README.md"), markdownHits.getFirst().sourcePath());
            assertEquals(List.of("Guide"), markdownHits.getFirst().metadata().headingPath());
            assertEquals(
                    1,
                    storage.lexicalSearch()
                            .search(new SearchRequest("legacyneedle"))
                            .size());
        }

        Files.writeString(project.resolve("notes.txt"), "replacementneedle notes\n");

        assertIndexSummary(cli.run(project, "index"), database, 3, 1, 1);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            List<StoredFile> files = storage.files().findAll();

            assertEquals(3, files.size());

            for (StoredFile file : files) {
                assertEquals(1, storage.chunks().findByFileId(file.id()).size());
            }

            assertTrue(storage.lexicalSearch()
                    .search(new SearchRequest("legacyneedle"))
                    .isEmpty());

            List<SearchHit> replacement = storage.lexicalSearch().search(new SearchRequest("replacementneedle"));
            assertEquals(1, replacement.size());
            assertEquals(Path.of("notes.txt"), replacement.getFirst().sourcePath());
        }
    }

    @Test
    void explicitChildIndexUsesRootConfigAndPersistsProjectRelativePaths() throws Exception {
        Files.writeString(project.resolve(".pecia.toml"), "[store]\npath = \"state/search.sqlite\"\n");
        Files.writeString(project.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(project.resolve("outside.txt"), "outside target\n");
        Path docs = Files.createDirectory(project.resolve("docs"));
        Files.writeString(docs.resolve("Ödeme notları.txt"), "childneedle ödeme\n");
        Files.writeString(docs.resolve("ignored.txt"), "ignored target\n");
        Path database = project.resolve("state/search.sqlite");

        assertIndexSummary(cli.run(project, "index", "docs"), database, 1, 1);
        assertIndexSummary(cli.run(docs, "index"), database, 1, 0, 0);

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            List<StoredFile> files = storage.files().findAll();

            assertEquals(1, files.size());
            assertEquals(Path.of("docs/Ödeme notları.txt"), files.getFirst().sourcePath());
            assertEquals(1, storage.chunks().findByFileId(files.getFirst().id()).size());

            List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest("childneedle"));
            assertEquals(1, hits.size());
            assertEquals(files.getFirst().sourcePath(), hits.getFirst().sourcePath());
        }

        assertFalse(Files.exists(project.resolve(".pecia")));
        assertFalse(Files.exists(docs.resolve("state")));
    }

    @Test
    void partialIndexKeepsRejectedFilesSearchableAndUpdatesSuccessfulFiles() throws Exception {
        Path rejected = project.resolve("bad.txt");
        Files.writeString(rejected, "retainedneedle\n");
        Files.writeString(project.resolve("good.txt"), "outdatedneedle\n");
        Path database = project.resolve(".pecia/index.db");
        assertIndexSummary(cli.run(project, "index"), database, 2, 2);
        Files.write(rejected, new byte[] {(byte) 0xc3, 0x28});
        Files.writeString(project.resolve("good.txt"), "updatedneedle\n");

        Result partial = cli.run(project, "index");

        assertEquals(1, partial.exitCode());
        assertEquals(
                "index: " + database
                        + "\ncandidates: 2\nindexed: 1\nrechunked: 0\nunchanged: 0\ndeleted: 0\nchunks: 1\nrejected: 1\nfailed: 0\n",
                partial.out());
        assertTrue(partial.err().startsWith("warning: bad.txt: INVALID_UTF8: "), partial.err());
        assertNoStackTrace(partial.err());

        Result retained = cli.run(project, "query", "retainedneedle");
        Result updated = cli.run(project, "query", "updatedneedle");
        Result outdated = cli.run(project, "query", "outdatedneedle");

        assertSuccess(retained);
        assertTrue(retained.out().startsWith("bad.txt:1-1\n"), retained.out());
        assertTrue(retained.out().contains("  retainedneedle\n"), retained.out());
        assertSuccess(updated);
        assertTrue(updated.out().startsWith("good.txt:1-1\n"), updated.out());
        assertSuccess(outdated);
        assertEquals("No results.\n", outdated.out());
    }

    @Test
    void indexingDeletedFileRemovesSearchResultsAndReportsCleanup() throws Exception {
        Path source = Files.writeString(project.resolve("removed.txt"), "deletedneedle");
        assertSuccess(cli.run(project, "index"));
        Files.delete(source);

        Result cleanup = cli.run(project, "index");

        assertSuccess(cleanup);
        assertEquals(
                "index: " + project.resolve(".pecia/index.db")
                        + "\ncandidates: 0\nindexed: 0\nrechunked: 0\nunchanged: 0\ndeleted: 1\nchunks: 0\nrejected: 0\nfailed: 0\n",
                cleanup.out());
        Result query = cli.run(project, "query", "deletedneedle");
        assertSuccess(query);
        assertEquals("No results.\n", query.out());
        assertIndexSummary(cli.run(project, "index"), project.resolve(".pecia/index.db"), 0, 0);
    }

    @Test
    void unchangedAndTimestampOnlyRerunsLeaveEveryProjectAndIndexByteUntouched() throws Exception {
        cli.enableOffline();
        Files.createDirectories(project.resolve("docs"));
        Files.createDirectories(project.resolve("src"));
        Path guide =
                Files.writeString(project.resolve("docs/Ödeme.md"), "# Ödeme rehberi\n\nİstanbul ödeme notları.\n");
        Files.writeString(project.resolve("src/Auth.java"), "class Auth {\r\n    String JWT_SECRET;\r\n}\r\n");
        Files.write(
                project.resolve("notes.txt"), "\uFEFFnotes with a byte order mark\n".getBytes(StandardCharsets.UTF_8));
        Files.writeString(project.resolve("empty.txt"), "");
        Path database = project.resolve(".pecia/index.db");
        assertIndexSummary(cli.run(project, "index"), database, 4, 3);

        // runReadOnly compares every project entry and file hash, including the index and its sidecar files.
        assertIndexSummary(cli.runReadOnly(project, "index"), database, 4, 0, 0);
        Files.setLastModifiedTime(
                guide,
                FileTime.from(Files.getLastModifiedTime(guide).toInstant().plus(Duration.ofHours(1))));
        assertIndexSummary(cli.runReadOnly(project, "index"), database, 4, 0, 0);
    }
}
