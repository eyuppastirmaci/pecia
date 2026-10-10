package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hostile filesystem states for the hostile-input corpus, generated at test time because Git cannot carry them
 * portably. Each case that depends on the host skips with the reason listed in {@code hostile/README.md}.
 */
class HostileFilesystemTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final String TEXT = "payment validation\n";

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private Path root;

    @BeforeEach
    void createProject() throws IOException {
        root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Files.createDirectory(root.resolve(".git"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "..foo.txt",
                " leading-space.txt",
                "[brackets].txt",
                "{braces}.txt",
                "#hash%percent;semicolon.txt",
                "ünïcödé-ışğ.txt",
                "emoji-😀.txt"
            })
    void portableUnusualNamesAreIndexedAndSearchable(String name) throws Exception {
        assertIndexedAndSearchable(name);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "trailing-space .txt",
                "CON.txt",
                "NUL.txt",
                "colon:name.txt",
                "back\\slash.txt",
                "star*question?.txt",
                "line\nbreak.txt"
            })
    void namesWindowsCannotCreateAreIndexedAndSearchableElsewhere(String name) throws Exception {
        assumeFalse(WINDOWS, "Windows reserves these names or characters");

        assertIndexedAndSearchable(name);
    }

    @Test
    void extensionlessDeviceNameIsNotACandidate() throws Exception {
        assumeFalse(WINDOWS, "Windows reserves the NUL device name");
        Files.writeString(root.resolve("NUL"), TEXT);
        Files.writeString(root.resolve("keep.txt"), TEXT);

        IndexResult result = index();

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(List.of("keep.txt"), storedPaths(result));
    }

    @Test
    void symbolicLinkLoopsAndOutsideTargetsAreNotFollowed() throws Exception {
        Path outside = Files.writeString(
                Files.createDirectory(sandbox.resolve("outside")).resolve("secret.txt"), TEXT);
        Files.writeString(Files.createDirectory(root.resolve("docs")).resolve("keep.txt"), TEXT);
        createSymbolicLinkOrSkip(root.resolve("docs/loop"), root);
        createSymbolicLinkOrSkip(root.resolve("self"), root.resolve("self"));
        createSymbolicLinkOrSkip(root.resolve("secret.txt"), outside);

        IndexResult result = index();

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertEquals(List.of("docs/keep.txt"), storedPaths(result));
    }

    @Test
    void unreadableFileIsReportedAndOtherFilesAreIndexed() throws Exception {
        Path blocked = Files.writeString(root.resolve("blocked.txt"), TEXT);
        Files.writeString(root.resolve("keep.txt"), TEXT);

        withoutPermissions(blocked, () -> {
            IndexResult result = index();

            assertEquals(IndexResult.Status.PARTIAL, result.status());
            assertEquals(1, result.indexedFiles());
            assertEquals(1, result.issues().size());
            assertEquals(Path.of("blocked.txt"), result.issues().get(0).sourcePath());
            assertEquals(
                    ExtractionException.Reason.READ_FAILED,
                    result.issues().get(0).reason());
        });
    }

    @Test
    void unreadableDirectoryStopsIndexingBeforeStorageIsOpened() throws Exception {
        Path blocked = Files.createDirectory(root.resolve("blocked"));
        Files.writeString(blocked.resolve("private.txt"), TEXT);
        Files.writeString(root.resolve("keep.txt"), TEXT);

        withoutPermissions(blocked, () -> {
            IndexException failure = assertThrows(IndexException.class, this::index);

            assertEquals(IndexResult.Status.INCOMPLETE_SCAN, failure.result().status());
            assertEquals(1, failure.scanIssues().size());
            // The issue currently names only the path; a typed cause is expected to replace this detail.
            assertEquals(blocked, failure.scanIssues().get(0).path());
            assertFalse(Files.exists(root.resolve(".pecia")), "An incomplete scan must not create storage");
        });
    }

    @Test
    void fileDeletedAfterDiscoveryKeepsItsRecordsAndSkipsCleanup() throws Exception {
        Path doomed = Files.writeString(root.resolve("doomed.txt"), TEXT);
        Files.writeString(root.resolve("keep.txt"), TEXT);
        IndexResult first = index();
        Files.writeString(root.resolve("keep.txt"), "payment validation changed\n");

        IndexResult result = indexAfterDiscovery(() -> Files.delete(doomed));

        assertEquals(IndexResult.Status.PARTIAL, result.status());
        assertEquals(1, result.indexedFiles());
        assertEquals(0, result.deletedFiles());
        assertEquals(
                ExtractionException.Reason.READ_FAILED, result.issues().get(0).reason());
        assertEquals(storedPaths(first), storedPaths(result), "The deleted file's records must be retained");
    }

    @Test
    void fileReplacedByADirectoryAfterDiscoveryIsReportedAsNotARegularFile() throws Exception {
        Path replaced = Files.writeString(root.resolve("replaced.txt"), TEXT);

        IndexResult result = indexAfterDiscovery(() -> {
            Files.delete(replaced);
            Files.createDirectory(replaced);
        });

        assertEquals(IndexResult.Status.PARTIAL, result.status());
        assertEquals(
                ExtractionException.Reason.NOT_REGULAR_FILE,
                result.issues().get(0).reason());
    }

    @Test
    void fileChangedAfterDiscoveryIsIndexedWithItsNewContent() throws Exception {
        Path changed = Files.writeString(root.resolve("changed.txt"), TEXT);

        IndexResult result = indexAfterDiscovery(() -> Files.writeString(changed, "replacement wording\n"));

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        IndexDump dump = IndexDump.read(result.context().databasePath(), root, List.of("replacement", "payment"));
        assertEquals(1, dump.queries().get(0).hits().size());
        assertEquals(0, dump.queries().get(1).hits().size());
    }

    private void assertIndexedAndSearchable(String name) throws Exception {
        try {
            Files.writeString(root.resolve(name), TEXT);
        } catch (IOException | RuntimeException unavailable) {
            assumeTrue(false, "The file system cannot create this name: " + unavailable);
        }

        IndexResult result = index();

        // Every name sits in the project root, so its stored path is the name itself.
        assertEquals(
                IndexResult.Status.COMPLETE,
                result.status(),
                () -> result.issues().toString());
        assertEquals(List.of(name), storedPaths(result));
        IndexDump dump = IndexDump.read(result.context().databasePath(), root, List.of("payment"));
        assertEquals(name, dump.queries().get(0).hits().get(0).sourcePath());
    }

    private IndexResult index() throws Exception {
        return new IndexService(loader).index(root);
    }

    /** Runs the change after discovery has listed the candidates and before any file is read. */
    private IndexResult indexAfterDiscovery(FilesystemChange change) throws Exception {
        IndexService service = new IndexService(loader, context -> {
            change.apply();
            return SqliteStorage.open(context.databasePath(), context.projectRoot());
        });

        return service.index(root);
    }

    private List<String> storedPaths(IndexResult result) throws Exception {
        return IndexDump.read(result.context().databasePath(), root, List.of()).files().stream()
                .map(IndexDump.FileEntry::sourcePath)
                .toList();
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic link creation unavailable: " + unavailable.getMessage());
        }
    }

    private static void withoutPermissions(Path path, FilesystemCheck check) throws Exception {
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(path);

        try {
            Files.setPosixFilePermissions(path, Set.of());
            assumeFalse(Files.isReadable(path), "The current account bypasses permissions");
            check.run();
        } finally {
            Files.setPosixFilePermissions(path, original);
        }
    }

    @FunctionalInterface
    private interface FilesystemChange {

        void apply() throws IOException;
    }

    @FunctionalInterface
    private interface FilesystemCheck {

        void run() throws Exception;
    }
}
