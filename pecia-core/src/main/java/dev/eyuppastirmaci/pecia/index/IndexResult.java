package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An indexing outcome with unchanged files and committed replacements and deletions. Deleted files
 * are independent of the current scan's candidate count.
 *
 * @param writtenChunks chunks written by committed replacements, excluding unchanged files
 */
public record IndexResult(
        ProjectContext context,
        Status status,
        int candidateCount,
        int indexedFiles,
        int unchangedFiles,
        int deletedFiles,
        long writtenChunks,
        List<FileIssue> issues) {

    /** Copies issues and rejects negative counters or more processed files than candidates. */
    public IndexResult {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);

        if (candidateCount < 0
                || indexedFiles < 0
                || unchangedFiles < 0
                || deletedFiles < 0
                || writtenChunks < 0
                || (long) indexedFiles + unchangedFiles + issues.size() > candidateCount) {
            throw new IllegalArgumentException("Invalid indexing counters");
        }
    }

    /** Creates an outcome with no unchanged files or deletions for existing indexing callers. */
    public IndexResult(
            ProjectContext context,
            Status status,
            int candidateCount,
            int indexedFiles,
            long writtenChunks,
            List<FileIssue> issues) {
        this(context, status, candidateCount, indexedFiles, 0, 0, writtenChunks, issues);
    }

    /** Returns the number of candidates rejected for content or format restrictions. */
    public long rejectedFiles() {
        return issues.stream().filter(FileIssue::rejected).count();
    }

    /** Returns the number of candidates whose extraction failed for filesystem-related reasons. */
    public long failedFiles() {
        return issues.size() - rejectedFiles();
    }

    /** Describes whether indexing and cleanup completed, encountered file issues, or aborted. */
    public enum Status {

        /** All admitted candidates were indexed or unchanged, and any cleanup completed. */
        COMPLETE,
        /** Discovery completed, but one or more files could not be extracted. */
        PARTIAL,
        /** Discovery was incomplete, so no indexing was attempted. */
        INCOMPLETE_SCAN,
        /** A storage operation failed after discovery completed. */
        FAILED
    }

    /** A file extraction issue identified by its normalized project-relative source path. */
    public record FileIssue(Path sourcePath, ExtractionException.Reason reason, String message) {

        /** Validates the source path and requires a reason and diagnostic message. */
        public FileIssue {
            ContentPath.requireProjectRelative(sourcePath);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
        }

        /** Reports a content/format restriction rather than a filesystem extraction failure. */
        public boolean rejected() {
            return switch (reason) {
                case TOO_LARGE, INVALID_UTF8, BINARY_CONTENT, UNSUPPORTED_TYPE -> true;
                case READ_FAILED, FILE_CHANGED, NOT_REGULAR_FILE -> false;
            };
        }
    }
}
