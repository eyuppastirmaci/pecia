package dev.eyuppastirmaci.pecia.storage.sqlite;

import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.applyFts;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertConsistent;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.assertMatches;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.execute;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertChunk;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertFile;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.insertHeading;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.openVersionOne;
import static dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport.FtsRow;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqliteFtsSynchronizationTest {
    @TempDir
    Path root;

    @Test
    void synchronizesChunkInsertionContentUpdateAndDeletion() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "docs/lifecycle.md");
            assertTrue(rows(connection).isEmpty(), "A file without chunks has no FTS row");
            insertChunk(connection, 11, 1, 0, "originaltoken");

            assertEquals(List.of(new FtsRow(11, "originaltoken", "", "docs/lifecycle.md")), rows(connection));
            assertMatches(connection, "content: originaltoken", 11);
            assertMatches(connection, "source_path: lifecycle", 11);
            assertConsistent(connection);

            execute(connection, "UPDATE chunks SET content = ? WHERE id = ?", "replacementtoken", 11);
            assertMatches(connection, "originaltoken");
            assertMatches(connection, "content: replacementtoken", 11);
            assertEquals(List.of(new FtsRow(11, "replacementtoken", "", "docs/lifecycle.md")), rows(connection));
            assertConsistent(connection);

            execute(connection, "DELETE FROM chunks WHERE id = ?", 11);
            assertMatches(connection, "replacementtoken");
            assertMatches(connection, "lifecycle");
            assertTrue(rows(connection).isEmpty());
            assertConsistent(connection);
        }
    }

    @Test
    void movingAChunkToAnotherFileChangesItsPathAndPreservesItsOtherFields() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "docs/previoushome.md");
            insertFile(connection, 2, "archive/currenthome.md");
            insertChunk(connection, 11, 1, 0, "travellingcontent");
            insertHeading(connection, 11, 0, "travellingheading");
            insertChunk(connection, 22, 2, 1, "residentcontent");
            assertMatches(connection, "source_path: previoushome", 11);
            assertMatches(connection, "source_path: currenthome", 22);

            execute(connection, "UPDATE chunks SET file_id = ? WHERE id = ?", 2, 11);

            assertMatches(connection, "previoushome");
            assertMatches(connection, "source_path: currenthome", 11, 22);
            assertMatches(connection, "content: travellingcontent", 11);
            assertMatches(connection, "headings: travellingheading", 11);
            assertEquals(
                    List.of(
                            new FtsRow(11, "travellingcontent", "travellingheading", "archive/currenthome.md"),
                            new FtsRow(22, "residentcontent", "", "archive/currenthome.md")),
                    rows(connection));
            assertConsistent(connection);
        }
    }

    @Test
    void refreshesOrderedRepeatedHeadingsOnInsertionUpdateAndDeletion() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "notes.md");
            insertChunk(connection, 11, 1, 0, "bodytoken");
            insertHeading(connection, 11, 0, "Parent heading");
            insertHeading(connection, 11, 2, "Parent heading");
            insertHeading(connection, 11, 1, "Child heading");

            assertEquals(
                    List.of(new FtsRow(11, "bodytoken", "Parent heading Child heading Parent heading", "notes.md")),
                    rows(connection));
            assertMatches(connection, "headings: parent", 11);
            assertMatches(connection, "headings: \"parent heading child heading parent heading\"", 11);
            assertConsistent(connection);

            execute(
                    connection,
                    "UPDATE chunk_headings SET heading = ? WHERE chunk_id = ? AND position = ?",
                    "Updated heading",
                    11,
                    1);
            assertMatches(connection, "headings: child");
            assertMatches(connection, "headings: updated", 11);
            assertMatches(connection, "headings: \"parent heading updated heading parent heading\"", 11);
            assertConsistent(connection);

            execute(connection, "DELETE FROM chunk_headings WHERE chunk_id = ? AND position = ?", 11, 2);
            assertEquals(
                    List.of(new FtsRow(11, "bodytoken", "Parent heading Updated heading", "notes.md")),
                    rows(connection));
            assertMatches(connection, "headings: parent", 11);
            assertMatches(connection, "headings: \"updated heading parent heading\"");
            assertConsistent(connection);

            execute(connection, "DELETE FROM chunk_headings WHERE chunk_id = ?", 11);
            assertEquals(List.of(new FtsRow(11, "bodytoken", "", "notes.md")), rows(connection));
            assertMatches(connection, "headings: parent");
            assertMatches(connection, "headings: updated");
            assertMatches(connection, "content: bodytoken", 11);
            assertConsistent(connection);
        }
    }

    @Test
    void headingPositionChangesRefreshThePhraseOrder() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "notes.md");
            insertChunk(connection, 11, 1, 0, "bodytoken");
            insertHeading(connection, 11, 0, "Firstlevel");
            insertHeading(connection, 11, 1, "Secondlevel");
            assertMatches(connection, "headings: \"firstlevel secondlevel\"", 11);

            // A spare position avoids violating the source table's composite primary key while swapping.
            execute(connection, "UPDATE chunk_headings SET position = 2 WHERE chunk_id = ? AND position = 0", 11);
            assertMatches(connection, "headings: \"firstlevel secondlevel\"");
            assertMatches(connection, "headings: \"secondlevel firstlevel\"", 11);
            assertConsistent(connection);

            execute(connection, "UPDATE chunk_headings SET position = 0 WHERE chunk_id = ? AND position = 1", 11);
            execute(connection, "UPDATE chunk_headings SET position = 1 WHERE chunk_id = ? AND position = 2", 11);
            assertEquals(List.of(new FtsRow(11, "bodytoken", "Secondlevel Firstlevel", "notes.md")), rows(connection));
            assertMatches(connection, "headings: \"secondlevel firstlevel\"", 11);
            assertMatches(connection, "headings: \"firstlevel secondlevel\"");
            assertConsistent(connection);
        }
    }

    @Test
    void movingHeadingsRefreshesBothTheOldAndNewChunkIncludingAnEmptiedSource() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "notes.md");
            insertChunk(connection, 11, 1, 0, "sourcebody");
            insertChunk(connection, 22, 1, 1, "destinationbody");
            insertHeading(connection, 11, 0, "Retained");
            insertHeading(connection, 11, 1, "Travelling");
            insertHeading(connection, 22, 0, "Resident");
            assertMatches(connection, "headings: travelling", 11);

            execute(
                    connection,
                    "UPDATE chunk_headings SET chunk_id = ? WHERE chunk_id = ? AND position = ?",
                    22,
                    11,
                    1);
            assertEquals(
                    List.of(
                            new FtsRow(11, "sourcebody", "Retained", "notes.md"),
                            new FtsRow(22, "destinationbody", "Resident Travelling", "notes.md")),
                    rows(connection));
            assertMatches(connection, "headings: travelling", 22);
            assertMatches(connection, "headings: retained", 11);
            assertMatches(connection, "headings: \"resident travelling\"", 22);
            assertConsistent(connection);

            execute(
                    connection,
                    "UPDATE chunk_headings SET chunk_id = ?, position = ? WHERE chunk_id = ? AND position =" + " ?",
                    22,
                    2,
                    11,
                    0);
            assertEquals(
                    List.of(
                            new FtsRow(11, "sourcebody", "", "notes.md"),
                            new FtsRow(22, "destinationbody", "Resident Travelling Retained", "notes.md")),
                    rows(connection));
            assertMatches(connection, "headings: retained", 22);
            assertMatches(connection, "headings: \"resident travelling retained\"", 22);
            assertMatches(connection, "content: sourcebody", 11);
            assertConsistent(connection);
        }
    }

    @Test
    void changingAFilePathUpdatesEveryOwnedChunkAndLeavesOtherFilesAlone() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "docs/oldlocation.md");
            insertFile(connection, 2, "docs/unchanged.md");
            insertChunk(connection, 11, 1, 0, "firstbody");
            insertChunk(connection, 12, 1, 1, "secondbody");
            insertHeading(connection, 11, 0, "Overview");
            insertChunk(connection, 21, 2, 0, "otherbody");
            assertMatches(connection, "source_path: oldlocation", 11, 12);

            execute(connection, "UPDATE files SET source_path = ? WHERE id = ?", "archive/newlocation.txt", 1);

            assertMatches(connection, "oldlocation");
            assertMatches(connection, "source_path: newlocation", 11, 12);
            assertMatches(connection, "source_path: unchanged", 21);
            assertMatches(connection, "headings: overview", 11);
            assertEquals(
                    List.of(
                            new FtsRow(11, "firstbody", "Overview", "archive/newlocation.txt"),
                            new FtsRow(12, "secondbody", "", "archive/newlocation.txt"),
                            new FtsRow(21, "otherbody", "", "docs/unchanged.md")),
                    rows(connection));
            assertConsistent(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void fileCascadeDeletionCannotRecreateFtsRowsThroughHeadingTriggers(int recursiveTriggers) throws Exception {
        try (Connection connection = openVersionOne(root)) {
            execute(connection, "PRAGMA recursive_triggers = " + recursiveTriggers);
            applyFts(connection);
            insertFile(connection, 1, "docs/removedpath.md");
            insertFile(connection, 2, "docs/retainedpath.md");
            insertChunk(connection, 11, 1, 0, "removedfirst");
            insertChunk(connection, 12, 1, 1, "removedsecond");
            insertHeading(connection, 11, 0, "Removedparent");
            insertHeading(connection, 11, 1, "Removedchild");
            insertHeading(connection, 12, 0, "Removedparent");
            execute(
                    connection,
                    "INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (?, ?, ?)",
                    11,
                    "startOffset",
                    "0");
            insertChunk(connection, 21, 2, 0, "retainedbody");
            insertHeading(connection, 21, 0, "Retainedheading");
            assertMatches(connection, "source_path: removedpath", 11, 12);
            assertConsistent(connection);

            execute(connection, "DELETE FROM files WHERE id = ?", 1);

            assertMatches(connection, "removedfirst OR removedsecond OR removedparent OR removedchild OR removedpath");
            assertMatches(connection, "content: retainedbody", 21);
            assertMatches(connection, "headings: retainedheading", 21);
            assertMatches(connection, "source_path: retainedpath", 21);
            assertEquals(
                    List.of(new FtsRow(21, "retainedbody", "Retainedheading", "docs/retainedpath.md")),
                    rows(connection));
            assertConsistent(connection);
        }
    }

    @Test
    void unindexedFileChunkAndAttributeChangesDoNotRewriteTheFtsIndex() throws Exception {
        try (Connection connection = openVersionOne(root)) {
            applyFts(connection);
            insertFile(connection, 1, "docs/stablepath.md");
            insertChunk(connection, 11, 1, 0, "stablebody");
            insertHeading(connection, 11, 0, "Stableheading");
            List<FtsRow> before = rows(connection);

            assertSingleSourceChange(
                    connection,
                    "UPDATE files SET content_hash = ?, document_type = ? WHERE id = ?",
                    "b".repeat(64),
                    "PLAIN_TEXT",
                    1);
            assertSingleSourceChange(
                    connection,
                    "UPDATE chunks SET start_line = ?, end_line = ?, chunk_index = ? WHERE id = ?",
                    2,
                    8,
                    5,
                    11);
            assertSingleSourceChange(
                    connection,
                    "INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (?, ?, ?)",
                    11,
                    "startOffset",
                    "10");
            assertSingleSourceChange(
                    connection,
                    "UPDATE chunk_attributes SET value = ? WHERE chunk_id = ? AND name = ?",
                    "20",
                    11,
                    "startOffset");
            assertSingleSourceChange(
                    connection, "DELETE FROM chunk_attributes WHERE chunk_id = ? AND name = ?", 11, "startOffset");

            assertEquals(before, rows(connection));
            assertMatches(connection, "content: stablebody", 11);
            assertMatches(connection, "headings: stableheading", 11);
            assertMatches(connection, "source_path: stablepath", 11);
            assertConsistent(connection);
        }
    }

    private static void assertSingleSourceChange(Connection connection, String sql, Object... parameters)
            throws SQLException {
        long before = totalChanges(connection);
        execute(connection, sql, parameters);
        // SQLite includes trigger and FTS shadow-table writes in total_changes(), unlike changes().
        assertEquals(1L, totalChanges(connection) - before, "Only the source row should change: " + sql);
    }

    private static long totalChanges(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT total_changes()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
