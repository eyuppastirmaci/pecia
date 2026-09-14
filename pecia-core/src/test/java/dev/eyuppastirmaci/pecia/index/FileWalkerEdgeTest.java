package dev.eyuppastirmaci.pecia.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileWalkerEdgeTest {
    @TempDir Path root;
    private final FileWalker walker = new FileWalker(new GlobFilter(List.of("**/*.md"), List.of()));

    @Test
    void rejectsMissingTargetsFilesAndOutsideRoots() throws IOException {
        assertThrows(IOException.class, () -> walker.scan(root.resolve("missing"), root));
        Path file = write("file.md", "text");
        assertThrows(IOException.class, () -> walker.scan(file, root));
        assertThrows(IOException.class, () -> walker.scan(root, root.resolve("child")));
    }

    @Test
    void inheritedRulesAndProjectRelativeGlobsApplyToSubdirectoryScan() throws IOException {
        write(".gitignore", "*.md\n");
        write("docs/.gitignore", "!keep.md\n");
        write("docs/keep.md", "keep");
        write("docs/drop.md", "drop");
        FileWalker scoped = new FileWalker(new GlobFilter(List.of("docs/*.md"), List.of()));
        WalkResult result = scoped.scan(root.resolve("docs"), root);
        assertTrue(result.complete());
        assertEquals(List.of(Path.of("keep.md")), result.files());
    }

    @Test
    void cannotReviveAnIgnoredAncestorByScanningItsChild() throws IOException {
        write(".gitignore", "ignored/\n");
        write("ignored/child/.gitignore", "!keep.md\n");
        write("ignored/child/keep.md", "text");
        assertTrue(walker.scan(root.resolve("ignored/child"), root).files().isEmpty());
    }

    @Test
    void excludedAncestorAlsoAppliesWhenTargetIsDeeper() throws IOException {
        write("build/deep/keep.md", "text");
        FileWalker scoped = new FileWalker(new GlobFilter(List.of(), List.of("**/build/**")));
        assertTrue(scoped.scan(root.resolve("build/deep"), root).files().isEmpty());
    }

    @Test
    void invalidNestedIgnoreFileProducesPartialResultsAndNoScopeLeak() throws IOException {
        Files.createDirectories(root.resolve("bad/.gitignore"));
        write("bad/private.md", "text");
        write("good/keep.md", "text");
        WalkResult result = walker.scan(root, root);
        assertFalse(result.complete());
        assertEquals(List.of(Path.of("good/keep.md")), result.files());
        assertEquals(root.resolve("bad/.gitignore"), result.issues().getFirst().path());
        assertThrows(IOException.class, () -> walker.walk(root));
    }

    @Test
    void invalidInheritedIgnoreDoesNotExposeDescendants() throws IOException {
        Files.createDirectory(root.resolve(".gitignore"));
        write("child/keep.md", "text");
        WalkResult result = walker.scan(root.resolve("child"), root);
        assertFalse(result.complete());
        assertTrue(result.files().isEmpty());
    }

    @Test
    void preservesNamesAndSortsByPortablePaths() throws IOException {
        write("z.md", "text");
        write("a/Z.md", "text");
        write("A.md", "text");
        List<Path> expected = List.of(Path.of("A.md"), Path.of("a/Z.md"), Path.of("z.md"));
        assertEquals(expected, walker.scan(root, root).files());
        assertEquals(expected, walker.scan(root, root).files());
    }

    @Test
    void skipsLinksAndRejectsLinkedTargetsIncludingIntermediateLinks() throws IOException {
        Path actual = Files.createDirectories(root.resolve("actual/sub"));
        write("actual/sub/keep.md", "text");
        Path link = root.resolve("linked");

        try {
            Files.createSymbolicLink(link, actual.getParent());
            Files.createSymbolicLink(root.resolve("alias.md"), actual.resolve("keep.md"));
            Files.createSymbolicLink(actual.resolve("loop"), root);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symlink creation unavailable: " + unavailable.getMessage());
        }

        assertEquals(List.of(Path.of("actual/sub/keep.md")), walker.scan(root, root).files());
        assertThrows(IOException.class, () -> walker.scan(link, root));
        assertThrows(IOException.class, () -> walker.scan(link.resolve("sub"), root));
    }

    @Test
    void inaccessibleDirectoryIsReportedWhenPermissionsAreEnforced() throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Path blocked = Files.createDirectory(root.resolve("blocked"));
        write("blocked/private.md", "text");
        write("keep.md", "text");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(blocked);

        try {
            Files.setPosixFilePermissions(blocked, Set.of());
            assumeTrue(!Files.isReadable(blocked), "Current account bypasses permissions");
            WalkResult result = walker.scan(root, root);
            assertFalse(result.complete());
            assertEquals(List.of(Path.of("keep.md")), result.files());
            assertTrue(result.issues().stream().anyMatch(issue -> issue.path().equals(blocked)));
        } finally {
            Files.setPosixFilePermissions(blocked, original);
        }
    }

    private Path write(String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());

        return Files.writeString(file, content);
    }
}
