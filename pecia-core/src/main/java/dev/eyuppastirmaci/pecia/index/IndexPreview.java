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
     * unchanged, and rejected lists.
     *
     * @param newFiles candidates the index does not hold yet
     * @param changedFiles candidates whose content differs from the stored file
     * @param rechunkedFiles candidates whose content is stored but was chunked under other settings
     * @param unchangedFiles candidates the run would skip
     * @param rejectedFiles candidates the run could not read or would reject, with their reasons; they keep what the
     *     index holds for them
     * @param removedFiles indexed files the run would delete, which it does only after reading every candidate and
     *     path; empty otherwise
     * @param storageFailure why the index cannot be read, for {@link IndexState#UNREADABLE}; null otherwise
     * @param storageMessage the failure's detail, for {@link IndexState#UNREADABLE}; null otherwise
     */
    public record Changes(
            IndexState index,
            List<Path> newFiles,
            List<Path> changedFiles,
            List<Path> rechunkedFiles,
            List<Path> unchangedFiles,
            List<IndexResult.FileIssue> rejectedFiles,
            List<Path> removedFiles,
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
                    Objects.requireNonNull(failure, "failure"),
                    Objects.requireNonNull(message, "message"));
        }
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
