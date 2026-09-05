package dev.eyuppastirmaci.pecia.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.FileTime;
import java.util.Set;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.NOT_REGULAR_FILE;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.READ_FAILED;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.TOO_LARGE;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.FILE_CHANGED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileContentLoaderTest {

    @TempDir
    Path root;

    @Test
    void readsARegularFileAtTheExactLimit() throws Exception {
        Path file = Files.write(root.resolve("exact.txt"), new byte[]{1, 2, 3, 4});
        FileContent content = new FileContentLoader(4).load(file);

        assertEquals(file, content.file());
        assertEquals(4, content.size());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, content.bytes());
    }

    @Test
    void rejectsAFileOverTheLimit() throws IOException {
        Path file = Files.write(root.resolve("large.txt"), new byte[]{1, 2, 3, 4, 5});
        ExtractionException failure = assertThrows(ExtractionException.class,
                () -> new FileContentLoader(4).load(file));

        assertEquals(TOO_LARGE, failure.reason());
        assertEquals(file, failure.path());
    }

    @Test
    void rejectsMissingFilesAndDirectoriesWithClassifiedReasons() {
        Path missing = root.resolve("missing.txt");
        ExtractionException missingFailure = assertThrows(ExtractionException.class,
                () -> new FileContentLoader(16).load(missing));
        ExtractionException directoryFailure = assertThrows(ExtractionException.class,
                () -> new FileContentLoader(16).load(root));

        assertEquals(READ_FAILED, missingFailure.reason());
        assertEquals(NOT_REGULAR_FILE, directoryFailure.reason());
    }

    @Test
    void requiresPositiveLimitAndAbsoluteNormalizedPaths() {
        assertThrows(IllegalArgumentException.class, () -> new FileContentLoader(0));
        assertThrows(IllegalArgumentException.class, () -> new FileContentLoader(-1));
        assertThrows(NullPointerException.class, () -> new FileContentLoader(16).load(null));
        assertThrows(IllegalArgumentException.class,
                () -> new FileContentLoader(16).load(Path.of("relative.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> new FileContentLoader(16).load(root.resolve("folder/../file.txt")));
    }

    @Test
    void fileContentOwnsItsByteArray() {
        byte[] source = {1, 2, 3};
        FileContent content = new FileContent(root.resolve("bytes.txt"), source);

        source[0] = 9;
        byte[] returned = content.bytes();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, content.bytes());
        assertEquals(ContentHash.sha256(new byte[]{1, 2, 3}), content.contentHash());
    }

    @Test
    void metadataOnlyChangesDoNotChangeTheContentHash() throws Exception {
        Path file = Files.writeString(root.resolve("stable.txt"), "same bytes");
        FileContentLoader loader = new FileContentLoader(64);
        ContentHash before = loader.load(file).contentHash();

        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
        ContentHash afterMetadataChange = loader.load(file).contentHash();
        Path renamed = Files.move(file, root.resolve("renamed.txt"));
        ContentHash afterRename = loader.load(renamed).contentHash();

        assertEquals(before, afterMetadataChange);
        assertEquals(before, afterRename);
    }

    @Test
    void detectsObservableChangesAcrossAReadSnapshot() {
        FileTime time = FileTime.fromMillis(1_000);
        FileContentLoader.FileState original = new FileContentLoader.FileState(10, time, "key");

        assertFalse(FileContentLoader.changedDuringRead(original,
                new FileContentLoader.FileState(10, time, "key"), 10));
        assertTrue(FileContentLoader.changedDuringRead(original,
                new FileContentLoader.FileState(11, time, "key"), 11));
        assertTrue(FileContentLoader.changedDuringRead(original,
                new FileContentLoader.FileState(10, FileTime.fromMillis(2_000), "key"), 10));
        assertTrue(FileContentLoader.changedDuringRead(original,
                new FileContentLoader.FileState(10, time, "replacement"), 10));
        assertTrue(FileContentLoader.changedDuringRead(original,
                new FileContentLoader.FileState(10, time, "key"), 9));
    }

    @Test
    void rejectsSymbolicLinkPathsWhenSupported() throws IOException {
        Path file = Files.writeString(root.resolve("actual.txt"), "content");
        Path link = root.resolve("linked.txt");

        try {
            Files.createSymbolicLink(link, file);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symlink creation unavailable: " + unavailable.getMessage());
        }

        ExtractionException failure = assertThrows(ExtractionException.class,
                () -> new FileContentLoader(64).load(link));

        assertEquals(NOT_REGULAR_FILE, failure.reason());
    }

    @Test
    void reportsUnreadableFilesWhenPermissionsAreEnforced() throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Path file = Files.writeString(root.resolve("private.txt"), "content");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);

        try {
            Files.setPosixFilePermissions(file, Set.of());
            assumeTrue(!Files.isReadable(file), "Current account bypasses permissions");

            ExtractionException failure = assertThrows(ExtractionException.class,
                    () -> new FileContentLoader(64).load(file));

            assertEquals(READ_FAILED, failure.reason());
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }
}
