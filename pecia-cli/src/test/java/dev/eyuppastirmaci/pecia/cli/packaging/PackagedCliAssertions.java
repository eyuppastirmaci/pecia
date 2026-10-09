package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Assertions on packaged CLI process results shared by the scenario suites. */
final class PackagedCliAssertions {

    private PackagedCliAssertions() {}

    static void assertSuccess(Result result) {
        assertEquals(0, result.exitCode(), result.err());
        assertEquals("", result.err());
    }

    static void assertIndexSummary(Result result, Path database, int files, int chunks) {
        assertIndexSummary(result, database, files, files, chunks);
    }

    static void assertIndexSummary(Result result, Path database, int candidates, int indexed, int chunks) {
        assertIndexSummary(result, database, candidates, indexed, 0, chunks);
    }

    static void assertIndexSummary(Result result, Path database, int candidates, int indexed, int deleted, int chunks) {
        assertSuccess(result);
        assertEquals(
                "index: "
                        + database
                        + "\ncandidates: "
                        + candidates
                        + "\nindexed: "
                        + indexed
                        + "\nunchanged: "
                        + (candidates - indexed)
                        + "\ndeleted: "
                        + deleted
                        + "\nchunks: "
                        + chunks
                        + "\nrejected: 0\nfailed: 0\n",
                result.out());
    }

    static boolean initialized(Path log, String className) throws IOException {
        return Files.readString(log).contains("Initializing '" + className + "'");
    }

    static void assertConciseFailure(Result result, String expectedMessage) {
        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        assertTrue(result.err().startsWith("pecia index: "), result.err());
        assertTrue(result.err().contains(expectedMessage), result.err());
        assertNoStackTrace(result.err());
    }

    static void assertNoStackTrace(String error) {
        assertFalse(error.contains("\tat "), error);
        assertFalse(error.contains("Exception in thread"), error);
        assertFalse(error.contains("Caused by:"), error);
    }
}
