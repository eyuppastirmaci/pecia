package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertIndexSummary(result, database, candidates, indexed, 0, deleted, chunks);
    }

    /** Checks a summary in which {@code rechunked} of the indexed files were rebuilt for new chunking settings. */
    static void assertIndexSummary(
            Result result, Path database, int candidates, int indexed, int rechunked, int deleted, int chunks) {
        assertSuccess(result);
        assertEquals(
                "index: " + database + "\n" + files(indexed, rechunked, candidates - indexed, deleted, 0, candidates)
                        + "\nchunks: " + chunks + " written\n",
                withoutTime(result.out()));
    }

    /** Returns a summary's {@code files:} line for a run without failed files. */
    static String files(int indexed, int rechunked, int unchanged, int deleted, int rejected, int found) {
        return "files: " + indexed + " indexed, " + rechunked + " rechunked, " + unchanged + " unchanged, " + deleted
                + " deleted, " + rejected + " rejected, 0 failed (" + found + " found)";
    }

    /** Removes a summary's last line, its duration, which varies from run to run. */
    static String withoutTime(String summary) {
        assertTrue(summary.matches("(?s).*\ntime: [^\n]+ s( \\([^\n]+/s\\))?\n"), summary);
        return summary.substring(0, summary.lastIndexOf("time: "));
    }

    /** Returns the location header of each query hit, in rank order. */
    static List<String> hitLocations(Result result) {
        return result.out()
                .lines()
                .filter(line -> !line.isBlank() && !line.startsWith(" "))
                .toList();
    }

    /** Reports whether a JVM class-initialization log, which uses internal slash-separated names, includes type. */
    static boolean initialized(Path log, Class<?> type) throws IOException {
        String internalName = type.getName().replace('.', '/');
        return Files.readString(log).contains("Initializing '" + internalName + "'");
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
