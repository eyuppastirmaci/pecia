package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Started;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stopping the packaged CLI while it indexes leaves a consistent index that the next run completes. The test sends
 * SIGTERM, which starts the same shutdown hook as Ctrl+C's SIGINT; a child process may inherit SIGINT as ignored, and
 * the JVM then never handles it. Windows ends processes without running shutdown hooks, so the test runs on POSIX.
 */
@DisabledOnOs(OS.WINDOWS)
class PackagedCliInterruptIT {

    private static final int FILES = 200;
    private static final Duration PATIENCE = Duration.ofSeconds(60);
    // Indexing the whole corpus takes several seconds here and longer on slower CI machines.
    private static final Duration INDEXING = Duration.ofSeconds(120);
    private static final Pattern INDEXED = Pattern.compile("(?m)^indexed: (\\d+)$");

    @TempDir
    Path sandbox;

    @Test
    void aStoppedRunPrintsWhatItFinishedAndTheNextRunCompletesTheIndex() throws Exception {
        PackagedCli cli = PackagedCli.create(sandbox);
        Path project = cli.project();
        writeCorpus(project);
        Path database = project.resolve(".pecia/index.db");

        Started started = cli.start(project, "index", "--no-semantic");
        awaitCommittedFile(started.process(), database);
        started.process().destroy();

        assertTrue(started.process().waitFor(PATIENCE.toSeconds(), TimeUnit.SECONDS), "The run did not stop");
        // The JVM reports 128 plus the signal number once the shutdown hook returns.
        assertEquals(143, started.process().exitValue());
        String out = Files.readString(started.stdout());
        assertTrue(out.contains("candidates: " + FILES + "\n"), out);
        int indexed = indexed(out);
        assertTrue(indexed > 0 && indexed < FILES, out);
        assertEquals(
                "pecia index: cancelled; the files indexed so far are kept, and the next run continues from there\n",
                Files.readString(started.stderr()).replace("\r\n", "\n"));
        assertEquals("ok", query(database, "PRAGMA integrity_check"));
        assertEquals(String.valueOf(indexed), query(database, "SELECT count(*) FROM files"));

        Result next = cli.runWithin(INDEXING, project, "index", "--no-semantic");

        assertEquals(0, next.exitCode(), next.err());
        assertEquals(FILES - indexed, indexed(next.out()));
        assertTrue(next.out().contains("unchanged: " + indexed + "\n"), next.out());
    }

    @Test
    void aKilledRunLeavesAConsistentIndexThatTheNextRunCompletes() throws Exception {
        PackagedCli cli = PackagedCli.create(sandbox);
        Path project = cli.project();
        writeCorpus(project);
        Path database = project.resolve(".pecia/index.db");
        Path clean = Files.createDirectories(sandbox.resolve("clean")).toRealPath();
        writeCorpus(clean);

        Started started = cli.start(project, "index", "--no-semantic");
        awaitCommittedFile(started.process(), database);
        // A hard kill: no shutdown hook runs and no transaction is finished.
        started.process().destroyForcibly();

        assertTrue(started.process().waitFor(PATIENCE.toSeconds(), TimeUnit.SECONDS), "The run did not stop");
        int committed = Integer.parseInt(query(database, "SELECT count(*) FROM files"));
        assertTrue(committed > 0 && committed < FILES, committed + " files were committed before the kill");
        assertEquals("ok", query(database, "PRAGMA integrity_check"));
        assertEquals("0", query(database, "SELECT count(*) FROM pragma_foreign_key_check"));

        Result next = cli.runWithin(INDEXING, project, "index", "--no-semantic");
        Result fresh = cli.runWithin(INDEXING, clean, "index", "--no-semantic");

        assertEquals(0, next.exitCode(), next.err());
        assertEquals(FILES - committed, indexed(next.out()));
        assertEquals(0, fresh.exitCode(), fresh.err());

        for (String text : List.of("refund policy", "word42", "Note 199")) {
            Result recovered = cli.run(project, "query", "--mode=lexical", "--limit=20", text);
            Result expected = cli.run(clean, "query", "--mode=lexical", "--limit=20", text);

            assertEquals(0, recovered.exitCode(), recovered.err());
            assertEquals(expected.out(), recovered.out(), text);
        }
    }

    /** Writes enough Markdown files that indexing them takes several seconds. */
    private static void writeCorpus(Path project) throws Exception {
        StringBuilder paragraph = new StringBuilder();

        for (int line = 0; line < 60; line++) {
            paragraph
                    .append("word")
                    .append(line % 97)
                    .append(" refund policy line ")
                    .append(line)
                    .append(' ');
        }

        String body = (paragraph + "\n\n").repeat(6);

        for (int file = 0; file < FILES; file++) {
            Path directory = Files.createDirectories(project.resolve("dir" + file % 20));
            Files.writeString(directory.resolve("note" + file + ".md"), "# Note " + file + "\n\n" + body);
        }
    }

    /** Waits until the run has committed at least one file, so the stop lands in the middle of the run. */
    private static void awaitCommittedFile(Process process, Path database) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();

        while (true) {
            assertTrue(process.isAlive(), "The run ended before it could be stopped");
            assertTrue(System.nanoTime() < deadline, "The run committed no file in time");

            if (Files.exists(database)) {
                try {
                    if (Integer.parseInt(query(database, "SELECT count(*) FROM files")) > 0) {
                        return;
                    }
                } catch (SQLException busyOrUncreated) {
                    // The run may hold a write lock or still be creating the schema.
                }
            }

            Thread.sleep(20);
        }
    }

    private static String query(Path database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri());
                ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static int indexed(String out) {
        Matcher matcher = INDEXED.matcher(out);
        assertTrue(matcher.find(), out);
        return Integer.parseInt(matcher.group(1));
    }
}
