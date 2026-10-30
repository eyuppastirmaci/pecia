package dev.eyuppastirmaci.pecia.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/** Cleans temporary directories that other processes have written to. */
public final class TemporaryDirectories {

    /** How long a test waits for Windows to release the files of a process that has exited. */
    public static final Duration RELEASE_PATIENCE = Duration.ofSeconds(10);

    private static final long PAUSE_MILLIS = 100;

    private TemporaryDirectories() {}

    /**
     * Deletes the directory's contents, retrying every 100 ms for up to {@link #RELEASE_PATIENCE}. Windows can keep the
     * files of a process locked for a moment after it has exited, such as its classpath JARs, its redirected output,
     * or native libraries it loaded, which antivirus scanning may also hold. JUnit's cleanup of the temporary
     * directory would then fail although the test passed.
     *
     * @throws IOException the last failure, when the files are still locked after the patience has run out
     */
    public static void deleteContentsOnceReleased(Path directory) throws IOException, InterruptedException {
        retryWhileLocked(RELEASE_PATIENCE, () -> deleteContents(directory));
    }

    /** Runs the action until it completes without an {@link IOException} or {@code patience} has run out. */
    static void retryWhileLocked(Duration patience, Action action) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + patience.toNanos();

        while (true) {
            try {
                action.run();
                return;
            } catch (IOException stillLocked) {
                if (System.nanoTime() >= deadline) {
                    throw stillLocked;
                }

                Thread.sleep(PAUSE_MILLIS);
            }
        }
    }

    private static void deleteContents(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** A step that fails with an {@link IOException} while files are locked. */
    interface Action {
        void run() throws IOException;
    }
}
