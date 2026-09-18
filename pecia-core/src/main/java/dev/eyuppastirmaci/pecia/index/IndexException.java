package dev.eyuppastirmaci.pecia.index;

import java.util.List;
import java.util.Objects;

/** An aborted indexing run with its completed work and, for incomplete discovery, scan issues. */
public final class IndexException extends Exception {

    private final IndexResult result;
    private final List<WalkResult.Issue> scanIssues;

    IndexException(String message, Throwable cause, IndexResult result, List<WalkResult.Issue> scanIssues) {
        super(message, cause);

        this.result = Objects.requireNonNull(result, "result");
        this.scanIssues = List.copyOf(scanIssues);
    }

    public IndexResult result() {
        return result;
    }

    public List<WalkResult.Issue> scanIssues() {
        return scanIssues;
    }
}
