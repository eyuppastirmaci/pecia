package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.nio.file.Path;
import java.util.Objects;

/** Classifies one admitted file using its loaded content and previously committed indexing state. */
public enum IndexDecision {
    /** No manifest entry exists for the source path. */
    NEW,
    /** Existing content, type, or processing settings differ, or the stored profile is unknown. */
    CHANGED,
    /** Content, document type, and a known processing profile all match the stored state. */
    UNCHANGED;

    /**
     * Classifies a candidate as new when the caller found no stored file for its source path.
     *
     * @throws NullPointerException if an argument is null
     */
    public static IndexDecision evaluate(
            ExtractionRequest request, ContentHash contentHash, ChunkingIdentity identity) {
        validateCandidate(request, contentHash, identity);

        return NEW;
    }

    /**
     * Requires reprocessing when a stored file has no known complete chunking identity, including
     * files with only a legacy indexing profile.
     *
     * @throws IllegalArgumentException if the stored file belongs to another source path
     * @throws NullPointerException if an argument is null
     */
    public static IndexDecision evaluate(
            ExtractionRequest request, ContentHash contentHash, ChunkingIdentity identity, StoredFile storedFile) {
        validateCandidate(request, contentHash, identity);
        validateStoredPath(request.sourcePath(), storedFile);

        return CHANGED;
    }

    /**
     * Compares raw content, document type, and complete extraction and chunking behavior without
     * I/O. Model names and revisions alone do not affect chunking identity. The caller must enforce
     * current file admission and stable-read checks before using an unchanged decision.
     *
     * @throws IllegalArgumentException if the stored file belongs to another source path
     * @throws NullPointerException if an argument is null
     */
    public static IndexDecision evaluate(
            ExtractionRequest request,
            ContentHash contentHash,
            ChunkingIdentity identity,
            StoredFile storedFile,
            ChunkingIdentity storedIdentity) {
        validateCandidate(request, contentHash, identity);
        validateStoredPath(request.sourcePath(), storedFile);
        Objects.requireNonNull(storedIdentity, "storedIdentity");

        if (request.type() != storedFile.documentType()
                || !contentHash.equals(storedFile.contentHash())
                || !identity.equals(storedIdentity)) {
            return CHANGED;
        }

        return UNCHANGED;
    }

    /**
     * Classifies a candidate as new when the caller found no stored file for its source path.
     *
     * @throws NullPointerException if an argument is null
     */
    public static IndexDecision evaluate(ExtractionRequest request, ContentHash contentHash, IndexingProfile profile) {
        validateCandidate(request, contentHash, profile);

        return NEW;
    }

    /**
     * Requires reprocessing of a stored file whose legacy indexing profile is unknown.
     *
     * @throws IllegalArgumentException if the stored file belongs to another source path
     * @throws NullPointerException if an argument is null
     */
    public static IndexDecision evaluate(
            ExtractionRequest request, ContentHash contentHash, IndexingProfile profile, StoredFile storedFile) {
        validateCandidate(request, contentHash, profile);
        validateStoredPath(request.sourcePath(), storedFile);

        return CHANGED;
    }

    /**
     * Compares a candidate with a stored file and its known indexing profile without I/O. The caller
     * must enforce current file admission and stable-read checks before using an unchanged decision.
     *
     * @throws IllegalArgumentException if the stored file belongs to another source path
     * @throws NullPointerException if an argument is null
     */
    public static IndexDecision evaluate(
            ExtractionRequest request,
            ContentHash contentHash,
            IndexingProfile profile,
            StoredFile storedFile,
            IndexingProfile storedProfile) {
        validateCandidate(request, contentHash, profile);
        validateStoredPath(request.sourcePath(), storedFile);
        Objects.requireNonNull(storedProfile, "storedProfile");

        if (request.type() != storedFile.documentType()
                || !contentHash.equals(storedFile.contentHash())
                || !profile.equals(storedProfile)) {
            return CHANGED;
        }

        return UNCHANGED;
    }

    private static void validateCandidate(
            ExtractionRequest request, ContentHash contentHash, ChunkingIdentity identity) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(identity, "identity");
    }

    private static void validateCandidate(ExtractionRequest request, ContentHash contentHash, IndexingProfile profile) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(profile, "profile");
    }

    private static void validateStoredPath(Path sourcePath, StoredFile storedFile) {
        if (!sourcePath.equals(storedFile.sourcePath())) {
            throw new IllegalArgumentException("Stored file must match the candidate source path");
        }
    }
}
