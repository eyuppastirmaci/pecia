package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertNoStackTrace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The packaged CLI explains partial runs and storage failures on stderr and exits with 1. */
class PackagedCliFailureIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws Exception {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
    }

    @Test
    void anUnreadableFolderIsReportedWhileTheOtherFilesAreIndexed() throws Exception {
        Path blocked = Files.createDirectory(project.resolve("private"));
        Files.writeString(blocked.resolve("secret.txt"), "secretneedle\n");
        Files.writeString(project.resolve("notes.txt"), "openneedle\n");
        assumeTrue(Files.getFileStore(blocked).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);
        Result partial;

        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeTrue(!Files.isReadable(blocked), "Current account bypasses permissions");

            partial = cli.run(project, "index");
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }

        assertEquals(1, partial.exitCode());
        assertTrue(partial.out().contains("\nindexed: 1\n"), partial.out());
        List<String> lines = partial.err().lines().toList();
        assertEquals(2, lines.size(), partial.err());
        assertTrue(lines.get(0).startsWith("warning: private: PERMISSION_DENIED: "), lines.get(0));
        assertTrue(lines.get(0).endsWith(" (not scanned)"), lines.get(0));
        assertEquals(
                "pecia index: PARTIAL: 1 path could not be scanned; every other file was indexed, and no file was"
                        + " removed from the index",
                lines.get(1));
        assertNoStackTrace(partial.err());

        Result complete = cli.run(project, "index");

        assertEquals(0, complete.exitCode(), complete.err());
        assertTrue(complete.out().contains("\nindexed: 1\nrechunked: 0\nunchanged: 1\n"), complete.out());
    }

    @Test
    void aDamagedIndexIsLeftAloneAndExplained() throws Exception {
        Files.writeString(project.resolve("notes.txt"), "needle\n");
        Path database = Files.createDirectories(project.resolve(".pecia")).resolve("index.db");
        byte[] damaged = "not a database".getBytes(StandardCharsets.UTF_8);
        Files.write(database, damaged);

        Result failed = cli.run(project, "index");

        assertEquals(1, failed.exitCode());
        List<String> lines = failed.err().lines().toList();
        assertEquals(2, lines.size(), failed.err());
        assertTrue(lines.get(0).startsWith("pecia index: FAILED: CORRUPT: "), lines.get(0));
        assertEquals(
                "The index is damaged; delete " + database + " and run the command again to rebuild it from your"
                        + " files.",
                lines.get(1));
        assertNoStackTrace(failed.err());
        assertArrayEquals(damaged, Files.readAllBytes(database));

        Files.delete(database);
        Result rebuilt = cli.run(project, "index");

        assertEquals(0, rebuilt.exitCode(), rebuilt.err());
        assertTrue(rebuilt.out().contains("\nindexed: 1\n"), rebuilt.out());
    }
}
