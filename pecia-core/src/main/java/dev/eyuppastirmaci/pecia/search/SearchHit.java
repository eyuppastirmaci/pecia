package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.SourceLocation;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A ranked chunk match with a project-relative path, zero-based chunk index, and bounded preview.
 *
 * @param chunkId the SQLite row locator, which may change when the file is replaced
 * @param stableId the project-local identity, absent for legacy or invalidated processing state;
 *     identifies a position and chunking profile, not content freshness
 * @param fusedRanks for a hybrid hit, the lexical and semantic ranks its score fuses; empty for every other hit
 * @param matches where the snippet matches the query, in order and without overlaps; empty for hits found only by
 *     semantic search and for lexical hits that match only in headings or the path
 */
public record SearchHit(
        long chunkId,
        int chunkIndex,
        Path sourcePath,
        DocumentType documentType,
        SourceLocation sourceLocation,
        ChunkMetadata metadata,
        SearchScore score,
        String snippet,
        Optional<ChunkId> stableId,
        Optional<FusedRanks> fusedRanks,
        List<MatchRange> matches) {
    /**
     * Caps previews at 400 Unicode code points to balance useful context and concise terminal output.
     */
    public static final int MAX_SNIPPET_CODE_POINTS = 400;

    /**
     * Validates the match identity, required values, and preview length.
     *
     * @throws NullPointerException if a reference component is null
     * @throws IllegalArgumentException if the ID, position, path or snippet length is invalid, fused ranks are
     *     present without a reciprocal rank fusion score or missing with one, or match ranges overlap, are out of
     *     order, or reach past the snippet
     */
    public SearchHit {
        if (chunkId <= 0) {
            throw new IllegalArgumentException("chunkId must be positive");
        }

        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }

        ContentPath.requireProjectRelative(sourcePath);
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(sourceLocation, "sourceLocation");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(fusedRanks, "fusedRanks");
        matches = List.copyOf(matches);

        if (fusedRanks.isPresent() != (score.kind() == SearchScore.Kind.RECIPROCAL_RANK_FUSION)) {
            throw new IllegalArgumentException("Only a reciprocal rank fusion score carries fused ranks");
        }

        int codePoints = snippet.codePointCount(0, snippet.length());

        if (codePoints > MAX_SNIPPET_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "snippet must contain at most " + MAX_SNIPPET_CODE_POINTS + " code points");
        }

        int previousEnd = 0;

        for (MatchRange match : matches) {
            if (match.start() < previousEnd || match.end() > codePoints) {
                throw new IllegalArgumentException("Match ranges must be ordered, apart, and within the snippet");
            }

            previousEnd = match.end();
        }
    }

    /** Creates a search hit whose snippet has no known match ranges. */
    public SearchHit(
            long chunkId,
            int chunkIndex,
            Path sourcePath,
            DocumentType documentType,
            SourceLocation sourceLocation,
            ChunkMetadata metadata,
            SearchScore score,
            String snippet,
            Optional<ChunkId> stableId,
            Optional<FusedRanks> fusedRanks) {
        this(
                chunkId,
                chunkIndex,
                sourcePath,
                documentType,
                sourceLocation,
                metadata,
                score,
                snippet,
                stableId,
                fusedRanks,
                List.of());
    }

    /** Creates a lexical or semantic search hit, which has no fused ranks. */
    public SearchHit(
            long chunkId,
            int chunkIndex,
            Path sourcePath,
            DocumentType documentType,
            SourceLocation sourceLocation,
            ChunkMetadata metadata,
            SearchScore score,
            String snippet,
            Optional<ChunkId> stableId) {
        this(
                chunkId,
                chunkIndex,
                sourcePath,
                documentType,
                sourceLocation,
                metadata,
                score,
                snippet,
                stableId,
                Optional.empty());
    }

    /** Creates a search hit whose deterministic identity is unknown. */
    public SearchHit(
            long chunkId,
            int chunkIndex,
            Path sourcePath,
            DocumentType documentType,
            SourceLocation sourceLocation,
            ChunkMetadata metadata,
            SearchScore score,
            String snippet) {
        this(chunkId, chunkIndex, sourcePath, documentType, sourceLocation, metadata, score, snippet, Optional.empty());
    }
}
