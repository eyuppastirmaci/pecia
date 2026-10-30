package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
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
 * @param discardedEmbeddings embeddings of another profile removed before this run's, so the index never holds two
 *     profiles; zero when the run did not embed
 * @param embeddingCoverage how many chunks have this run's embeddings when the run completed its embedding; null
 *     when the run did not embed or stopped before
 * @param rechunkedFiles indexed files whose content was unchanged but whose chunks were rebuilt for other extraction,
 *     tokenizer, or chunking settings; a subset of {@code indexedFiles}
 * @param scanIssues paths discovery could not read; the run indexed the files it found and deleted none
 * @param semanticIssues files whose chunks could not be embedded; their chunks are stored without embeddings, which
 *     a later semantic run makes
 * @param timings how long the run and each phase it reached took; {@link IndexTimings#NONE} when not measured
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
        long backfilledChunks,
        long discardedEmbeddings,
        EmbeddingCoverage embeddingCoverage,
        int rechunkedFiles,
        List<WalkResult.Issue> scanIssues,
        List<SemanticIssue> semanticIssues,
        IndexTimings timings) {

    /**
     * Copies the issue lists and rejects negative counters, more processed files than candidates, and embeddings beyond the
     * written chunks or without a semantic model.
     */
    public IndexResult {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);
        scanIssues = List.copyOf(scanIssues);
        semanticIssues = List.copyOf(semanticIssues);
        Objects.requireNonNull(timings, "timings");

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

        if (discardedEmbeddings < 0
                || (semanticModel == null && (discardedEmbeddings > 0 || embeddingCoverage != null))) {
            throw new IllegalArgumentException("Invalid embedding compatibility counters");
        }

        if (rechunkedFiles < 0 || rechunkedFiles > indexedFiles) {
            throw new IllegalArgumentException("rechunkedFiles must be between zero and indexedFiles");
        }
    }

    /** Creates an outcome whose timing was not measured. */
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
            long embeddedChunks,
            long backfilledChunks,
            long discardedEmbeddings,
            EmbeddingCoverage embeddingCoverage,
            int rechunkedFiles,
            List<WalkResult.Issue> scanIssues,
            List<SemanticIssue> semanticIssues) {
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
                backfilledChunks,
                discardedEmbeddings,
                embeddingCoverage,
                rechunkedFiles,
                scanIssues,
                semanticIssues,
                IndexTimings.NONE);
    }

    /** Creates an outcome of a run that embedded every chunk it meant to. */
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
            long embeddedChunks,
            long backfilledChunks,
            long discardedEmbeddings,
            EmbeddingCoverage embeddingCoverage,
            int rechunkedFiles,
            List<WalkResult.Issue> scanIssues) {
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
                backfilledChunks,
                discardedEmbeddings,
                embeddingCoverage,
                rechunkedFiles,
                scanIssues,
                List.of());
    }

    /** Creates an outcome of a run whose discovery read every path. */
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
            long embeddedChunks,
            long backfilledChunks,
            long discardedEmbeddings,
            EmbeddingCoverage embeddingCoverage,
            int rechunkedFiles) {
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
                backfilledChunks,
                discardedEmbeddings,
                embeddingCoverage,
                rechunkedFiles,
                List.of());
    }

    /** Creates an outcome that rebuilt no file's chunks for new settings. */
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
            long embeddedChunks,
            long backfilledChunks,
            long discardedEmbeddings,
            EmbeddingCoverage embeddingCoverage) {
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
                backfilledChunks,
                discardedEmbeddings,
                embeddingCoverage,
                0);
    }

    /** Creates an outcome that discarded no embeddings and reports no coverage. */
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
            long embeddedChunks,
            long backfilledChunks) {
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
                backfilledChunks,
                0,
                null);
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
        /**
         * Some files could not be extracted or embedded, or discovery could not read some paths; every other file was
         * indexed. When files could not be read or discovered, no file was deleted, since such a file is not known to
         * be gone.
         */
        PARTIAL,
        /** Semantic indexing was requested, but its model could not be made available; nothing was indexed. */
        MODEL_UNAVAILABLE,
        /** The semantic model was available, but its embedder could not be opened; nothing was indexed. */
        EMBEDDER_UNAVAILABLE,
        /**
         * Embedding stopped the run: the runtime or model failed, or several files or pages in a row could not be
         * embedded. Everything committed earlier stays, including files written without embeddings.
         */
        EMBEDDING_FAILED,
        /** A storage operation failed after discovery completed. */
        FAILED,
        /**
         * The caller cancelled the run; files and backfill pages committed before it stay, no file is half written,
         * and no deletions ran. The next run continues from there.
         */
        CANCELLED
    }

    /**
     * A file whose chunks could not be embedded, identified by its normalized project-relative source path.
     *
     * @param reason why embedding failed
     */
    public record SemanticIssue(Path sourcePath, EmbeddingException.Reason reason, String message) {

        /** Validates the source path and requires a reason and diagnostic message. */
        public SemanticIssue {
            ContentPath.requireProjectRelative(sourcePath);
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
        }
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
