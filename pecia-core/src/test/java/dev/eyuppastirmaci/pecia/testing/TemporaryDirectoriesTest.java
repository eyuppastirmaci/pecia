package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Contents are deleted, and a deletion that fails while files are locked is tried again until the patience ends. */
class TemporaryDirectoriesTest {

    @TempDir
    Path directory;

    @Test
    void deletesEverythingBelowTheDirectoryButKeepsIt() throws Exception {
        Files.createDirectories(directory.resolve("native/win-x64"));
        Files.writeString(directory.resolve("native/win-x64/runtime.dll"), "library");
        Files.writeString(directory.resolve("stdout.txt"), "output");

        TemporaryDirectories.deleteContentsOnceReleased(directory);

        assertTrue(Files.isDirectory(directory));
        try (Stream<Path> left = Files.list(directory)) {
            assertEquals(List.of(), left.toList());
        }
    }

    @Test
    void aLockThatIsReleasedInTimeIsRetriedUntilTheStepSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        TemporaryDirectories.retryWhileLocked(Duration.ofSeconds(10), () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AccessDeniedException("runtime.dll");
            }
        });

        assertEquals(3, attempts.get());
    }

    @Test
    @Timeout(10)
    void aLockThatOutlastsThePatienceFailsWithTheLastError() {
        AtomicInteger attempts = new AtomicInteger();
        AccessDeniedException last = new AccessDeniedException("runtime.dll");

        IOException failure = assertThrows(
                IOException.class,
                () -> TemporaryDirectories.retryWhileLocked(Duration.ofMillis(250), () -> {
                    attempts.incrementAndGet();
                    throw last;
                }));

        assertSame(last, failure);
        assertTrue(attempts.get() >= 2, attempts.get() + " attempts");
    }
}
