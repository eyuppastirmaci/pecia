package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

class IndexDumpTest {

    private static final List<String> QUERIES = List.of("needle", "JWT_SECRET", "ödeme");

    @TempDir
    Path sandbox;

    private final IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

    @Test
    void isStableAcrossReadsAndIndependentOfTheProjectRoot() throws Exception {
        Path first = createCorpus(sandbox.resolve("first"));
        Path second = createCorpus(sandbox.resolve("second/nested root"));

        IndexDump firstDump = indexAndDump(first);
        IndexDump secondDump = indexAndDump(second);

        assertEquals(firstDump, dump(first));
        assertEquals(firstDump, secondDump);
        assertEquals(4, firstDump.files().size());
        assertFalse(firstDump.chunks().isEmpty());
        assertEquals(firstDump.chunks().size(), firstDump.ftsRows().size());
        assertTrue(firstDump.queries().stream().allMatch(query -> !query.hits().isEmpty()));
    }

    @Test
    void recordsHeadingsOrderedAttributesStableIdsAndChunkLocatedFtsRows() throws Exception {
        Path root = createCorpus(sandbox.resolve("project"));

        IndexDump dump = indexAndDump(root);

        IndexDump.ChunkEntry guide = dump.chunks().stream()
                .filter(chunk -> chunk.sourcePath().equals("docs/Ödeme.md"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("Ödeme rehberi"), guide.headings());
        assertEquals(
                List.of("endOffset", "startOffset"),
                List.copyOf(guide.attributes().keySet()));
        assertEquals(64, guide.stableId().length());
        IndexDump.FtsEntry fts = dump.ftsRows().stream()
                .filter(row -> "docs/Ödeme.md".equals(row.chunkPath()))
                .findFirst()
                .orElseThrow();
        assertEquals(guide.content(), fts.content());
        assertEquals("Ödeme rehberi", fts.headings());
        assertEquals("docs/Ödeme.md", fts.sourcePath());
        assertTrue(dump.files().stream().allMatch(file -> file.chunkingFingerprint() != null));
    }

    @Test
    void singleCharacterEditChangesFilesChunksFtsRowsAndResults() throws Exception {
        Path root = createCorpus(sandbox.resolve("project"));
        IndexDump before = indexAndDump(root);
        // One changed character that also changes the term frequency, so the ranked score must move as well.
        Files.writeString(root.resolve("notes.txt"), "needle notes needlx\n");

        IndexDump after = indexAndDump(root);

        assertNotEquals(before.files(), after.files());
        assertNotEquals(before.chunks(), after.chunks());
        assertNotEquals(before.ftsRows(), after.ftsRows());
        assertNotEquals(before.queries(), after.queries());
    }

    @Test
    void ftsOnlyCorruptionAndOrphanedRowsChangeOnlyTheFtsAndResultSections() throws Exception {
        Path root = createCorpus(sandbox.resolve("project"));
        IndexDump before = indexAndDump(root);
        Path database = database(root);

        execute(
                database,
                "UPDATE chunks_fts SET content = content || ' tampered' WHERE rowid = (SELECT min(rowid)"
                        + " FROM chunks_fts)");
        IndexDump tampered = dump(root);

        assertEquals(before.files(), tampered.files());
        assertEquals(before.chunks(), tampered.chunks());
        assertNotEquals(before.ftsRows(), tampered.ftsRows());

        execute(
                database,
                "INSERT INTO chunks_fts(rowid, content, headings, source_path) VALUES (999999, 'needle',"
                        + " '', 'ghost.txt')");
        IndexDump orphaned = dump(root);

        assertEquals(before.chunks(), orphaned.chunks());
        IndexDump.FtsEntry orphan = orphaned.ftsRows().getLast();
        assertNull(orphan.chunkPath());
        assertNull(orphan.chunkIndex());
        assertEquals("ghost.txt", orphan.sourcePath());
        assertNotEquals(tampered.queries(), orphaned.queries());
    }

    @Test
    void addingAnUnrelatedMatchChangesRankedScores() throws Exception {
        Path root = createCorpus(sandbox.resolve("project"));
        IndexDump before = indexAndDump(root);
        Files.writeString(root.resolve("zz-extra.txt"), "needle needle needle\n");

        IndexDump after = indexAndDump(root);

        IndexDump.HitEntry notesBefore = hit(before, "needle", "notes.txt");
        IndexDump.HitEntry notesAfter = hit(after, "needle", "notes.txt");
        assertEquals(notesBefore.stableId(), notesAfter.stableId());
        assertNotEquals(notesBefore.score(), notesAfter.score());
    }

    private Path createCorpus(Path root) throws IOException {
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("docs/Ödeme.md"), "# Ödeme rehberi\n\nödeme needle İstanbul\n");
        Files.writeString(root.resolve("src/Auth.java"), "class Auth {\r\n    String JWT_SECRET;\r\n}\r\n");
        Files.writeString(root.resolve("notes.txt"), "needle notes needle\n");
        Files.writeString(root.resolve("empty.txt"), "");
        return root.toRealPath();
    }

    private IndexDump indexAndDump(Path root) throws Exception {
        IndexResult result = indexService.index(root);
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        return dump(root);
    }

    private static IndexDump dump(Path root) throws Exception {
        return IndexDump.read(database(root), root, QUERIES);
    }

    private static Path database(Path root) {
        return root.resolve(".pecia/index.db");
    }

    private static IndexDump.HitEntry hit(IndexDump dump, String query, String sourcePath) {
        return dump.queries().stream()
                .filter(entry -> entry.query().equals(query))
                .flatMap(entry -> entry.hits().stream())
                .filter(hit -> hit.sourcePath().equals(sourcePath))
                .findFirst()
                .orElseThrow();
    }

    private static void execute(Path database, String sql) throws SQLException {
        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
