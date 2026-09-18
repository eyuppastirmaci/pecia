package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class IndexResultTest {

    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();
    private static final ProjectContext CONTEXT = new ProjectContext(
            ROOT, new LoadedConfig(PeciaConfig.defaults(), ROOT, false), ROOT.resolve(".pecia/index.db"));

    @Test
    void reportsMixedIndexingOutcomesWithoutCountingDeletionsAsCandidates() {
        List<IndexResult.FileIssue> issues = List.of(
                issue("binary.txt", ExtractionException.Reason.BINARY_CONTENT),
                issue("unreadable.txt", ExtractionException.Reason.READ_FAILED));
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 6, 2, 2, 3, 7, issues);

        assertEquals(6, result.candidateCount());
        assertEquals(2, result.indexedFiles());
        assertEquals(2, result.unchangedFiles());
        assertEquals(3, result.deletedFiles());
        assertEquals(7, result.writtenChunks());
        assertEquals(1, result.rejectedFiles());
        assertEquals(1, result.failedFiles());
    }

    @ParameterizedTest
    @CsvSource({
        "-1, 0, 0, 0, 0",
        "0, -1, 0, 0, 0",
        "0, 0, -1, 0, 0",
        "0, 0, 0, -1, 0",
        "0, 0, 0, 0, -1",
        "0, 0, 0, 0, -9223372036854775808"
    })
    void rejectsNegativeCounters(int candidates, int indexed, int unchanged, int deleted, long chunks) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(
                        CONTEXT,
                        IndexResult.Status.FAILED,
                        candidates,
                        indexed,
                        unchanged,
                        deleted,
                        chunks,
                        List.of()));
    }

    @Test
    void rejectsProcessedCountsAboveDiscoveredCandidatesWithoutIntegerOverflow() {
        List<IndexResult.FileIssue> issues = List.of(issue("unreadable.txt", ExtractionException.Reason.READ_FAILED));

        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(CONTEXT, IndexResult.Status.COMPLETE, 1, 1, 1, 0, 1, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 1, 1, 0, 0, 1, issues));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 1, 0, 1, 0, 0, issues));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(
                        CONTEXT,
                        IndexResult.Status.FAILED,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        0,
                        0,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(
                        CONTEXT, IndexResult.Status.FAILED, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0, issues));
    }

    @Test
    void permitsCleanupWhenNoCurrentCandidatesRemain() {
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.COMPLETE, 0, 0, 0, 4, 0, List.of());

        assertEquals(0, result.candidateCount());
        assertEquals(4, result.deletedFiles());
        assertEquals(0, result.writtenChunks());
    }

    @Test
    void permitsIndexedEmptyFilesWithoutWrittenChunks() {
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.COMPLETE, 2, 1, 1, 0, 0, List.of());

        assertEquals(1, result.indexedFiles());
        assertEquals(1, result.unchangedFiles());
        assertEquals(0, result.writtenChunks());
    }

    @Test
    void retainsCompletedWorkWhenSomeCandidatesWereNotProcessedBeforeAbort() {
        List<IndexResult.FileIssue> issues = List.of(issue("unreadable.txt", ExtractionException.Reason.READ_FAILED));
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.FAILED, 8, 2, 1, 1, 5, issues);

        assertEquals(IndexResult.Status.FAILED, result.status());
        assertEquals(2, result.indexedFiles());
        assertEquals(1, result.unchangedFiles());
        assertEquals(1, result.deletedFiles());
        assertEquals(5, result.writtenChunks());
        assertEquals(issues, result.issues());
    }

    @Test
    void defensivelyCopiesFileIssues() {
        IndexResult.FileIssue issue = issue("binary.txt", ExtractionException.Reason.BINARY_CONTENT);
        List<IndexResult.FileIssue> issues = new ArrayList<>(List.of(issue));
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 1, 0, 0, 0, 0, issues);

        issues.clear();

        assertEquals(List.of(issue), result.issues());
        assertThrows(UnsupportedOperationException.class, () -> result.issues().clear());
    }

    @ParameterizedTest
    @EnumSource(
            value = ExtractionException.Reason.class,
            names = {"TOO_LARGE", "INVALID_UTF8", "BINARY_CONTENT", "UNSUPPORTED_TYPE"})
    void classifiesContentRestrictionsAsRejections(ExtractionException.Reason reason) {
        IndexResult.FileIssue issue = issue("rejected.txt", reason);
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 1, 0, 0, 0, 0, List.of(issue));

        assertTrue(issue.rejected());
        assertEquals(1, result.rejectedFiles());
        assertEquals(0, result.failedFiles());
    }

    @ParameterizedTest
    @EnumSource(
            value = ExtractionException.Reason.class,
            names = {"READ_FAILED", "FILE_CHANGED", "NOT_REGULAR_FILE"})
    void classifiesFilesystemProblemsAsFailures(ExtractionException.Reason reason) {
        IndexResult.FileIssue issue = issue("failed.txt", reason);
        IndexResult result = new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 1, 0, 0, 0, 0, List.of(issue));

        assertFalse(issue.rejected());
        assertEquals(0, result.rejectedFiles());
        assertEquals(1, result.failedFiles());
    }

    @Test
    void existingConstructorDefaultsIncrementalCountersToZero() {
        List<IndexResult.FileIssue> issues = List.of(issue("binary.txt", ExtractionException.Reason.BINARY_CONTENT));
        IndexResult legacy = new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 3, 2, 4, issues);
        IndexResult explicit = new IndexResult(CONTEXT, IndexResult.Status.PARTIAL, 3, 2, 0, 0, 4, issues);

        assertEquals(explicit, legacy);
    }

    @Test
    void requiresContextStatusAndNonNullIssues() {
        assertThrows(
                NullPointerException.class,
                () -> new IndexResult(null, IndexResult.Status.COMPLETE, 0, 0, 0, 0, 0, List.of()));
        assertThrows(NullPointerException.class, () -> new IndexResult(CONTEXT, null, 0, 0, 0, 0, 0, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new IndexResult(CONTEXT, IndexResult.Status.COMPLETE, 0, 0, 0, 0, 0, null));
        assertThrows(
                NullPointerException.class,
                () -> new IndexResult(
                        CONTEXT, IndexResult.Status.PARTIAL, 1, 0, 0, 0, 0, Collections.singletonList(null)));
    }

    private static IndexResult.FileIssue issue(String source, ExtractionException.Reason reason) {
        return new IndexResult.FileIssue(Path.of(source), reason, "Could not extract " + source);
    }
}
