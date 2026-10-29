package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An indexing outcome with unchanged files and committed replacements and deletions. Deleted files
 * are independent of the current scan's candidate count.
 *
 * @param writtenChunks chunks written by committed replacements, excluding unchanged files
 * @param semanticModel how the semantic model became available; null when the run did not need it
 * @param embeddedChunks embeddings stored with committed replacements; zero when the run did not embed
 * @param backfilledChunks embeddings stored for chunks of unchanged files that had none; zero when the run did not
 *     embed
 */
public record IndexResult(
        ProjectContext context,
        Status status,
        int candidateCount,
        int indexedFiles,
        int unchangedFiles,
        int deletedFiles,
        long writtenChunks,
        List<FileIssue> issues,
        ModelOutcome semanticModel,
        long embeddedChunks,
        long backfilledChunks) {

    /**
     * Copies issues and rejects negative counters, more processed files than candidates, and embeddings beyond the
     * written chunks or without a semantic model.
     */
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

        if (embeddedChunks < 0 || embeddedChunks > writtenChunks || (embeddedChunks > 0 && semanticModel == null)) {
            throw new IllegalArgumentException("Invalid embedding counter");
        }

        if (backfilledChunks < 0 || (backfilledChunks > 0 && semanticModel == null)) {
            throw new IllegalArgumentException("Invalid backfill counter");
        }
    }

    /** Creates an outcome that embedded no stored chunks. */
    public IndexResult(
            ProjectContext context,
            Status status,
            int candidateCount,
            int indexedFiles,
            int unchangedFiles,
            int deletedFiles,
            long writtenChunks,
            List<FileIssue> issues,
            ModelOutcome semanticModel,
            long embeddedChunks) {
        this(
                context,
                status,
                candidateCount,
                indexedFiles,
                unchangedFiles,
                deletedFiles,
                writtenChunks,
                issues,
                semanticModel,
                embeddedChunks,
                0);
    }

    /** Creates an outcome that stored no embeddings. */
    public IndexResult(
            ProjectContext context,
            Status status,
            int candidateCount,
            int indexedFiles,
            int unchangedFiles,
            int deletedFiles,
            long writtenChunks,
            List<FileIssue> issues,
            ModelOutcome semanticModel) {
        this(
                context,
                status,
                candidateCount,
                indexedFiles,
                unchangedFiles,
                deletedFiles,
                writtenChunks,
                issues,
                semanticModel,
                0);
    }

    /** Creates an outcome that did not need the semantic model. */
    public IndexResult(
            ProjectContext context,
            Status status,
            int candidateCount,
            int indexedFiles,
            int unchangedFiles,
            int deletedFiles,
            long writtenChunks,
            List<FileIssue> issues) {
        this(context, status, candidateCount, indexedFiles, unchangedFiles, deletedFiles, writtenChunks, issues, null);
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

    /** Returns the configuration warnings found while resolving the project, in source order. */
    public List<Warning> warnings() {
        return context.loadedConfig().warnings();
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
        /** Semantic indexing was requested, but its model could not be made available; nothing was indexed. */
        MODEL_UNAVAILABLE,
        /** The semantic model was available, but its embedder could not be opened; nothing was indexed. */
        EMBEDDER_UNAVAILABLE,
        /**
         * Embedding failed, either for a changed file, which is left as it was, or for stored chunks; everything
         * committed earlier stays.
         */
        EMBEDDING_FAILED,
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
