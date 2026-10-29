package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What an indexing run would do, found without writing anything: the absolute target, project configuration, scan
 * outcome, whether the run would build embeddings, and what it would do with the index and each candidate.
 *
 * @param semanticEnabled whether the run would build embeddings for semantic search, with the caller's choice
 *     overriding the configuration
 */
public record IndexPreview(
        Path target, LoadedConfig loadedConfig, WalkResult walkResult, boolean semanticEnabled, Changes changes) {

    /** Requires every component. */
    public IndexPreview {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(loadedConfig, "loadedConfig");
        Objects.requireNonNull(walkResult, "walkResult");
        Objects.requireNonNull(changes, "changes");
    }

    /** Returns the configuration warnings found while resolving the project, in source order. */
    public List<Warning> warnings() {
        return loadedConfig.warnings();
    }

    /**
     * What a run would do with the index and with each discovered candidate. Paths are normalized and
     * project-relative, in discovery order. Candidates fall into exactly one of the new, changed, rechunked,
     * unchanged, and rejected lists. Chunk counts of files the run would write are estimated from their size; counts
     * of stored files are known.
     *
     * @param newFiles candidates the index does not hold yet
     * @param changedFiles candidates whose content differs from the stored file
     * @param rechunkedFiles candidates whose content is stored but was chunked under other settings
     * @param unchangedFiles candidates the run would skip, with their stored chunk counts
     * @param rejectedFiles candidates the run could not read or would reject, with their reasons; they keep what the
     *     index holds for them
     * @param removedFiles indexed files the run would delete, which it does only after reading every candidate and
     *     path; empty otherwise
     * @param semantic what a semantic run would embed and download; null for a lexical run or an unreadable index
     * @param storageFailure why the index cannot be read, for {@link IndexState#UNREADABLE}; null otherwise
     * @param storageMessage the failure's detail, for {@link IndexState#UNREADABLE}; null otherwise
     */
    public record Changes(
            IndexState index,
            List<PlannedFile> newFiles,
            List<PlannedFile> changedFiles,
            List<PlannedFile> rechunkedFiles,
            List<PlannedFile> unchangedFiles,
            List<IndexResult.FileIssue> rejectedFiles,
            List<PlannedFile> removedFiles,
            SemanticChanges semantic,
            StorageFailure storageFailure,
            String storageMessage) {

        /** Copies the lists and requires a failure exactly for an unreadable index. */
        public Changes {
            Objects.requireNonNull(index, "index");
            newFiles = List.copyOf(newFiles);
            changedFiles = List.copyOf(changedFiles);
            rechunkedFiles = List.copyOf(rechunkedFiles);
            unchangedFiles = List.copyOf(unchangedFiles);
            rejectedFiles = List.copyOf(rejectedFiles);
            removedFiles = List.copyOf(removedFiles);

            if ((index == IndexState.UNREADABLE) != (storageFailure != null && storageMessage != null)) {
                throw new IllegalArgumentException("A storage failure and its message come with an unreadable index");
            }
        }

        /** Returns the outcome for an index that cannot be read, which leaves every candidate unclassified. */
        public static Changes unreadable(StorageFailure failure, String message) {
            return new Changes(
                    IndexState.UNREADABLE,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    Objects.requireNonNull(failure, "failure"),
                    Objects.requireNonNull(message, "message"));
        }

        /** Returns the chunks the run would write for new, changed, and rechunked files. */
        public Count chunksToWrite() {
            return total(newFiles).plus(total(changedFiles)).plus(total(rechunkedFiles));
        }

        /** Returns the stored chunks of unchanged files, which the run keeps. */
        public Count chunksKept() {
            return total(unchangedFiles);
        }

        /** Returns the stored chunks of removed files, which the run deletes. */
        public Count chunksRemoved() {
            return total(removedFiles);
        }

        private static Count total(List<PlannedFile> files) {
            Count total = Count.known(0);

            for (PlannedFile file : files) {
                total = total.plus(file.chunks());
            }

            return total;
        }
    }

    /**
     * A file the run would write, keep, or delete, with its chunks.
     *
     * @param sourcePath the normalized project-relative path
     */
    public record PlannedFile(Path sourcePath, Count chunks) {

        /** Requires a path and a count. */
        public PlannedFile {
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(chunks, "chunks");
        }
    }

    /**
     * A non-negative count that says whether it is estimated.
     *
     * @param estimated true when the value is estimated from file sizes rather than read from the index
     */
    public record Count(long value, boolean estimated) {

        /** Rejects negative values. */
        public Count {
            if (value < 0) {
                throw new IllegalArgumentException("A count cannot be negative: " + value);
            }
        }

        /** Returns a count read from the index or otherwise exact. */
        public static Count known(long value) {
            return new Count(value, false);
        }

        /** Returns a count estimated from file sizes. */
        public static Count estimate(long value) {
            return new Count(value, true);
        }

        /** Returns the sum, estimated when either part is. */
        public Count plus(Count other) {
            return new Count(value + other.value, estimated || other.estimated);
        }
    }

    /**
     * What a semantic run would embed and whether it would download the model; no runtime is loaded to find it out.
     *
     * @param model the semantic model's name
     * @param downloadBytes the model's size when it would be downloaded; zero otherwise
     * @param chunksToEmbed chunks of written files, estimated, plus stored chunks of unchanged files without an
     *     embedding of the current model, known
     * @param embeddingsToDiscard stored embeddings of another model version, which the run deletes and makes again
     */
    public record SemanticChanges(
            String model, ModelState modelState, long downloadBytes, Count chunksToEmbed, long embeddingsToDiscard) {

        /** Requires the model, its state, and a count, and rejects negative sizes. */
        public SemanticChanges {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(modelState, "modelState");
            Objects.requireNonNull(chunksToEmbed, "chunksToEmbed");

            if (downloadBytes < 0
                    || embeddingsToDiscard < 0
                    || (modelState != ModelState.DOWNLOAD && downloadBytes != 0)) {
                throw new IllegalArgumentException("Invalid download size or discarded embeddings");
            }
        }
    }

    /** Whether the semantic model is ready for a run. */
    public enum ModelState {

        /** The verified model is in the cache. */
        CACHED,
        /** The run would download the model first. */
        DOWNLOAD,
        /** The model cache cannot be read; the run would fail to get the model. */
        UNAVAILABLE
    }

    /** The state of the index a run would write to. */
    public enum IndexState {

        /** No index exists yet; the run would create it. */
        NONE,
        /** The index is current. */
        CURRENT,
        /** The index has an older format; the run would upgrade it first, and candidates are compared after that. */
        UPGRADE,
        /** The index cannot be read; the run would fail as well. */
        UNREADABLE
    }
}
