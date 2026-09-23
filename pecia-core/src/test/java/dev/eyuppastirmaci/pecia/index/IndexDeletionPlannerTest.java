package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IndexDeletionPlannerTest {

    @TempDir
    Path root;

    @Test
    void plansMissingFilesAndRemovedSubtreesInStableOrderWithoutWriting() throws Exception {
        ProjectContext context = context(root);
        StoredFile nested = stored(1, "removed/deep/notes.txt");
        StoredFile first = stored(2, "a.txt");
        StoredFile present = stored(3, "present.txt");
        Files.writeString(root.resolve("present.txt"), "still here");
        WalkResult scan = scan(context);

        List<StoredFile> plan = new IndexDeletionPlanner(context).plan(scan, List.of(nested, present, first));

        assertEquals(List.of(first, nested), plan);
        assertThrows(UnsupportedOperationException.class, plan::clear);
        assertEquals("still here", Files.readString(root.resolve("present.txt")));
        assertFalse(Files.exists(context.databasePath()));
    }

    @Test
    void childScopeUsesPathComponentsAndRetainsCandidatesThatDisappearAfterDiscovery() throws Exception {
        Path child = Files.createDirectory(root.resolve("src"));
        ProjectContext context = context(child);
        Files.writeString(child.resolve("disappeared.txt"), "read failure later");
        WalkResult scan = scan(context);
        Files.delete(child.resolve("disappeared.txt"));
        StoredFile absent = stored(1, "src/deleted.txt");

        List<StoredFile> plan = new IndexDeletionPlanner(context)
                .plan(
                        scan,
                        List.of(
                                absent,
                                stored(2, "src/disappeared.txt"),
                                stored(3, "src-other/file.txt"),
                                stored(4, "outside.txt"),
                                stored(5, "src")));

        assertEquals(List.of(absent), plan);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[index]\ninclude = ['*.md']\n",
                "[index]\nexclude = ['**/*.txt']\n",
                "[index]\nexclude = ['child/**']\n"
            })
    void retainsMissingFilesOutsideConfiguredFilters(String config) throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), config);
        ProjectContext context = resolve(root);

        assertTrue(new IndexDeletionPlanner(context)
                .plan(scan(context), List.of(stored(1, "child/missing.txt")))
                .isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"child/\n!child/missing.txt\n", "**/*.txt\n"})
    void retainsIgnoredMissingFilesAndDoesNotReviveIgnoredParents(String rules) throws Exception {
        ProjectContext context = context(root);
        Files.writeString(root.resolve(".gitignore"), rules);

        assertTrue(new IndexDeletionPlanner(context)
                .plan(scan(context), List.of(stored(1, "child/missing.txt")))
                .isEmpty());
    }

    @Test
    void appliesInheritedAndNestedIgnoreRulesForChildTargets() throws Exception {
        Path child = Files.createDirectory(root.resolve("child"));
        ProjectContext context = context(child);
        Files.writeString(root.resolve(".gitignore"), "*.txt\n");
        Files.writeString(child.resolve(".gitignore"), "!allowed.txt\n");
        StoredFile allowed = stored(1, "child/allowed.txt");

        assertEquals(
                List.of(allowed),
                new IndexDeletionPlanner(context)
                        .plan(scan(context), List.of(allowed, stored(2, "child/ignored.txt"))));
    }

    @Test
    void retainsReservedDirectoriesAndConfiguredDatabaseSidecars() throws Exception {
        Files.writeString(
                root.resolve(".pecia.toml"), "[index]\ninclude = []\nexclude = []\n[store]\npath = 'state/db.txt'\n");
        ProjectContext context = resolve(root);

        assertTrue(new IndexDeletionPlanner(context)
                .plan(
                        scan(context),
                        List.of(
                                stored(1, ".git/missing.txt"),
                                stored(2, ".pecia/missing.txt"),
                                stored(3, "state/db.txt"),
                                stored(4, "state/db.txt-wal"),
                                stored(5, "state/db.txt-shm"),
                                stored(6, "state/db.txt-journal")))
                .isEmpty());
    }

    @Test
    void retainsExistingFilesAndPathsReplacedByDirectoriesOrNonDirectoryParents() throws Exception {
        ProjectContext context = context(root);
        Files.writeString(root.resolve("present.txt"), "binary\0");
        Files.createDirectory(root.resolve("directory.txt"));
        Files.writeString(root.resolve("parent"), "no longer a directory");

        assertTrue(new IndexDeletionPlanner(context)
                .plan(
                        new WalkResult(List.of(), List.of()),
                        List.of(stored(1, "present.txt"), stored(2, "directory.txt"), stored(3, "parent/missing.txt")))
                .isEmpty());
    }

    @Test
    void retainsDanglingLinksAndDoesNotTraverseLinkedParents() throws Exception {
        ProjectContext context = context(root);
        createLink(root.resolve("link.txt"), root.resolve("missing-target"));
        createLink(root.resolve("linked"), root.resolve("missing-directory"));

        assertTrue(new IndexDeletionPlanner(context)
                .plan(scan(context), List.of(stored(1, "link.txt"), stored(2, "linked/file.txt")))
                .isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"Guide.txt, guide.txt", "Ödeme.txt, Ödeme.txt"})
    void plansFilesWhoseStoredSpellingWasRenamedAway(String original, String renamed) throws Exception {
        ProjectContext context = context(root);
        Files.writeString(root.resolve(original), "renamed");
        StoredFile stored = stored(1, onlySource(context));
        renameSpelling(root.resolve(original), renamed);
        // Compare strings because Windows path equality ignores case.
        assumeFalse(
                onlySource(context).toString().equals(stored.sourcePath().toString()),
                "Filesystem does not preserve this spelling change");

        assertEquals(List.of(stored), new IndexDeletionPlanner(context).plan(scan(context), List.of(stored)));
    }

    @Test
    void plansFilesBelowADirectoryWhoseStoredSpellingWasRenamedAway() throws Exception {
        ProjectContext context = context(root);
        Files.createDirectory(root.resolve("Docs"));
        Files.writeString(root.resolve("Docs/a.txt"), "renamed");
        StoredFile stored = stored(1, onlySource(context));
        renameSpelling(root.resolve("Docs"), "docs");

        assertEquals(List.of(stored), new IndexDeletionPlanner(context).plan(scan(context), List.of(stored)));
    }

    @Test
    void caseSensitiveFilesystemsRetainDistinctSpellings() throws Exception {
        assumeFalse(caseInsensitive(), "Filesystem folds case");
        ProjectContext context = context(root);
        Files.writeString(root.resolve("Guide.txt"), "upper");
        Files.writeString(root.resolve("guide.txt"), "lower");

        assertTrue(new IndexDeletionPlanner(context)
                .plan(new WalkResult(List.of(), List.of()), List.of(stored(1, "Guide.txt"), stored(2, "guide.txt")))
                .isEmpty());
    }

    @Test
    void targetKeepsTheCallersSpellingOnCaseInsensitiveFilesystems() throws Exception {
        assumeTrue(caseInsensitive(), "Filesystem is case-sensitive");
        Files.createDirectory(root.resolve("Docs"));
        Files.writeString(root.resolve("Docs/present.txt"), "present");
        ProjectContext resolved = context(root);
        // The resolver canonicalizes child targets; the planner must still not fail on a caller-spelled target.
        ProjectContext context =
                new ProjectContext(root.resolve("docs"), resolved.loadedConfig(), resolved.databasePath());
        StoredFile deleted = stored(1, "docs/deleted.txt");

        assertEquals(
                List.of(deleted),
                new IndexDeletionPlanner(context)
                        .plan(new WalkResult(List.of(), List.of()), List.of(deleted, stored(2, "docs/present.txt"))));
    }

    @Test
    void refusesIncompleteScanEvenWhenManifestIsEmpty() throws Exception {
        ProjectContext context = context(root);
        Files.createDirectories(root.resolve("broken/.gitignore"));
        WalkResult incomplete = scan(context);
        assertFalse(incomplete.complete());

        assertThrows(IOException.class, () -> new IndexDeletionPlanner(context).plan(incomplete, List.of()));
    }

    @Test
    void abortsWholePlanWhenIgnoreRulesBecomeUnreadableAfterScan() throws Exception {
        ProjectContext context = context(root);
        Files.createDirectory(root.resolve("broken"));
        WalkResult scan = scan(context);
        Files.createDirectory(root.resolve("broken/.gitignore"));

        assertThrows(
                IOException.class,
                () -> new IndexDeletionPlanner(context)
                        .plan(scan, List.of(stored(1, "a.txt"), stored(2, "broken/file.txt"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "file", "link"})
    void refusesTargetThatDisappearsOrChangesAfterScan(String replacement) throws Exception {
        Path child = Files.createDirectory(root.resolve("child"));
        ProjectContext context = context(child);
        WalkResult scan = scan(context);
        Files.delete(child);

        if (replacement.equals("file")) {
            Files.writeString(child, "not a directory");
        } else if (replacement.equals("link")) {
            createLink(child, root);
        }

        assertThrows(
                IOException.class,
                () -> new IndexDeletionPlanner(context).plan(scan, List.of(stored(1, "child/file.txt"))));
    }

    @Test
    void accessDeniedIsNotTreatedAsAnAbsentFile() throws Exception {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        ProjectContext context = context(root);
        Path blocked = Files.createDirectory(root.resolve("blocked"));
        WalkResult scan = scan(context);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);

        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeTrue(!Files.isReadable(blocked), "Current account bypasses permissions");

            assertThrows(
                    IOException.class,
                    () -> new IndexDeletionPlanner(context)
                            .plan(scan, List.of(stored(1, "a.txt"), stored(2, "blocked/missing.txt"))));
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }
    }

    private void createLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symlink creation unavailable: " + unavailable.getMessage());
        }
    }

    /** Renames through a temporary name because a case-only move is a no-op on case-insensitive filesystems. */
    private static void renameSpelling(Path path, String name) throws IOException {
        Path temporary = Files.move(path, path.resolveSibling(name + ".renaming"));
        Files.move(temporary, path.resolveSibling(name));
    }

    private boolean caseInsensitive() throws IOException {
        Path probe = Files.writeString(root.resolve("CaseProbe.tmp"), "");

        try {
            return Files.exists(root.resolve("caseprobe.tmp"));
        } finally {
            Files.delete(probe);
        }
    }

    private Path onlySource(ProjectContext context) throws IOException {
        List<Path> files = scan(context).files();
        assertEquals(1, files.size());

        return context.sourcePath(files.getFirst());
    }

    private ProjectContext context(Path target) throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = ['**/*.txt']\n");

        return resolve(target);
    }

    private ProjectContext resolve(Path target) throws IOException {
        return new ProjectContextResolver(new PeciaConfigLoader(new PeciaConfigParser())).resolve(target);
    }

    private WalkResult scan(ProjectContext context) throws IOException {
        var config = context.loadedConfig().config();

        return new FileWalker(new GlobFilter(config.include(), config.exclude()))
                .scan(context.target(), context.projectRoot(), context.storageFiles());
    }

    private StoredFile stored(long id, String path) {
        return stored(id, Path.of(path));
    }

    private StoredFile stored(long id, Path path) {
        return new StoredFile(id, path, DocumentType.PLAIN_TEXT, ContentHash.sha256(new byte[0]));
    }
}
