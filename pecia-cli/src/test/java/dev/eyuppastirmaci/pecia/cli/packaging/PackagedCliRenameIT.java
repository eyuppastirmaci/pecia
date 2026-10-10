package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertIndexSummary;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.hitLocations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Case-only renames through the packaged CLI must leave only the new spelling indexed and searchable. They are
 * ordinary renames where the filesystem distinguishes case, so they run everywhere; child targets typed with
 * another spelling need a case-folding filesystem and are skipped elsewhere by a runtime probe.
 */
class PackagedCliRenameIT {

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;
    private Path database;

    @BeforeEach
    void prepareProject() throws Exception {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
        database = project.resolve(".pecia/index.db");
        cli.enableOffline();
        // A Git marker pins the project root for child targets; discovery always skips it.
        Files.createDirectory(project.resolve(".git"));
        Files.createDirectory(project.resolve("docs"));
        Files.writeString(project.resolve("docs/notes.txt"), "renameneedle\n");
        assertIndexSummary(cli.run(project, "index"), database, 1, 1);
    }

    @Test
    void caseOnlyFileRenameLeavesOnlyTheNewSpellingSearchable() throws Exception {
        renameSpelling(project.resolve("docs/notes.txt"), "Notes.txt");

        assertIndexSummary(cli.run(project, "index"), database, 1, 1, 1, 1);
        assertEquals(List.of("docs/Notes.txt:1-1"), hitLocations(cli.runReadOnly(project, "query", "renameneedle")));
        assertIndexSummary(cli.run(project, "index"), database, 1, 0, 0);
    }

    @Test
    void caseOnlyDirectoryRenameLeavesOnlyTheNewSpellingSearchable() throws Exception {
        renameSpelling(project.resolve("docs"), "Docs");

        assertIndexSummary(cli.run(project, "index"), database, 1, 1, 1, 1);
        assertEquals(List.of("Docs/notes.txt:1-1"), hitLocations(cli.runReadOnly(project, "query", "renameneedle")));
        assertIndexSummary(cli.run(project, "index"), database, 1, 0, 0);
    }

    @Test
    void renamedDirectoryReindexedThroughItsOldSpellingDropsTheOldRecords() throws Exception {
        assumeTrue(foldsCase(), "Filesystem distinguishes case");
        renameSpelling(project.resolve("docs"), "Docs");

        assertIndexSummary(cli.run(project, "index", "docs"), database, 1, 1, 1, 1);
        Result query = cli.runReadOnly(project, "query", "renameneedle");
        assertEquals(List.of("Docs/notes.txt:1-1"), hitLocations(query));
        assertIndexSummary(cli.run(project, "index", "DOCS"), database, 1, 0, 0);
        assertEquals(
                query.out(), cli.runReadOnly(project, "query", "renameneedle").out());
    }

    @Test
    void childTargetTypedWithAnotherCaseReusesTheStoredSpelling() throws Exception {
        assumeTrue(foldsCase(), "Filesystem distinguishes case");

        assertIndexSummary(cli.run(project, "index", "DOCS"), database, 1, 0, 0);
        assertEquals(List.of("docs/notes.txt:1-1"), hitLocations(cli.runReadOnly(project, "query", "renameneedle")));
    }

    private boolean foldsCase() throws IOException {
        Path probe = Files.writeString(sandbox.resolve("CaseProbe.tmp"), "");

        try {
            return Files.exists(sandbox.resolve("caseprobe.tmp"));
        } finally {
            Files.delete(probe);
        }
    }

    /** Renames through a temporary name because a case-only move is a no-op on case-insensitive filesystems. */
    private static void renameSpelling(Path path, String name) throws IOException {
        Path temporary = Files.move(path, path.resolveSibling(name + ".renaming"));
        Files.move(temporary, path.resolveSibling(name));
    }
}
