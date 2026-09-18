package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record IndexResult(ProjectContext context, Status status, int candidateCount, int indexedFiles,
                          long writtenChunks, List<FileIssue> issues) {

    public IndexResult {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);

        if (candidateCount < 0 || indexedFiles < 0 || writtenChunks < 0
                || (long) indexedFiles + issues.size() > candidateCount) {

            throw new IllegalArgumentException("Invalid indexing counters");
        }
    }

    public long rejectedFiles() {

        return issues.stream().filter(FileIssue::rejected).count();
    }

    public long failedFiles() {

        return issues.size() - rejectedFiles();
    }

    public enum Status {

        COMPLETE,
        PARTIAL,
        INCOMPLETE_SCAN,
        FAILED
    }

    public record FileIssue(Path sourcePath, ExtractionException.Reason reason, String message) {

        public FileIssue {
            ContentPath.requireProjectRelative(sourcePath);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
        }

        public boolean rejected() {

            return switch (reason) {
                case TOO_LARGE, INVALID_UTF8, BINARY_CONTENT, UNSUPPORTED_TYPE -> true;
                case READ_FAILED, FILE_CHANGED, NOT_REGULAR_FILE -> false;
            };
        }
    }
}
