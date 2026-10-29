package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Combines lexical and semantic rankings with reciprocal rank fusion, using only ranks: raw BM25 scores and cosine
 * similarities have different scales and directions, so they are never added.
 */
final class RankFusion {

    /** The RRF constant of the original method; larger values flatten the difference between ranks. */
    static final int K = 60;

    /**
     * How many candidates each side contributes, whatever the caller's limit, so a smaller limit always returns a
     * prefix of a larger one.
     */
    static final int CANDIDATE_DEPTH = SearchRequest.MAX_LIMIT;

    private static final Comparator<SearchHit> ORDER = Comparator.comparingDouble(
                    (SearchHit hit) -> hit.score().value())
            .reversed()
            .thenComparing(SearchHit::sourcePath, RankFusion::compareBinaryPaths)
            .thenComparingInt(SearchHit::chunkIndex)
            .thenComparingLong(SearchHit::chunkId);

    private RankFusion() {}

    /**
     * Ranks the union of both candidate lists by {@code 1 / (K + lexicalRank) + 1 / (K + semanticRank)} with 1-based
     * ranks, where a side that did not rank a chunk contributes zero. Candidates beyond {@link #CANDIDATE_DEPTH} on
     * either side are ignored. Ties use binary source path, chunk position, then chunk ID. A fused hit keeps the
     * lexical candidate's fields when there is one, so its snippet shows the matched terms, and the semantic
     * candidate's otherwise.
     *
     * @param lexical lexical candidates in their ranked order, all scored {@link SearchScore.Kind#SQLITE_BM25}
     * @param semantic semantic candidates in their ranked order, all scored {@link SearchScore.Kind#COSINE_SIMILARITY}
     * @param limit the maximum number of fused hits, between 1 and {@link SearchRequest#MAX_LIMIT}
     * @return up to {@code limit} hits scored {@link SearchScore.Kind#RECIPROCAL_RANK_FUSION}, best first
     * @throws NullPointerException if a list or a candidate is null
     * @throws IllegalArgumentException if limit is out of bounds, a list holds a chunk twice or another score kind, or
     *     the lists place one chunk at different positions
     */
    static List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> semantic, int limit) {
        if (limit < 1 || limit > SearchRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + SearchRequest.MAX_LIMIT);
        }

        List<SearchHit> lexicalCandidates = candidates(lexical, SearchScore.Kind.SQLITE_BM25);
        List<SearchHit> semanticCandidates = candidates(semantic, SearchScore.Kind.COSINE_SIMILARITY);
        Map<Long, Integer> semanticRanks = ranks(semanticCandidates);
        Map<Long, Integer> lexicalRanks = ranks(lexicalCandidates);
        List<SearchHit> fused = new ArrayList<>();

        for (SearchHit hit : lexicalCandidates) {
            Integer semanticRank = semanticRanks.get(hit.chunkId());

            if (semanticRank != null) {
                requireSamePosition(hit, semanticCandidates.get(semanticRank - 1));
            }

            fused.add(fused(hit, rank(lexicalRanks.get(hit.chunkId())), rank(semanticRank)));
        }

        for (SearchHit hit : semanticCandidates) {
            if (!lexicalRanks.containsKey(hit.chunkId())) {
                fused.add(fused(hit, OptionalInt.empty(), rank(semanticRanks.get(hit.chunkId()))));
            }
        }

        fused.sort(ORDER);

        return List.copyOf(fused.subList(0, Math.min(limit, fused.size())));
    }

    /** Returns the side's first {@link #CANDIDATE_DEPTH} candidates after checking their score kind. */
    private static List<SearchHit> candidates(List<SearchHit> hits, SearchScore.Kind kind) {
        List<SearchHit> candidates = List.copyOf(hits);

        for (SearchHit hit : candidates) {
            if (hit.score().kind() != kind) {
                throw new IllegalArgumentException(
                        "Expected " + kind + " candidates, not " + hit.score().kind());
            }
        }

        return candidates.subList(0, Math.min(candidates.size(), CANDIDATE_DEPTH));
    }

    /** Maps each candidate's chunk ID to its 1-based rank. */
    private static Map<Long, Integer> ranks(List<SearchHit> candidates) {
        Map<Long, Integer> ranks = new HashMap<>();

        for (int index = 0; index < candidates.size(); index++) {
            if (ranks.put(candidates.get(index).chunkId(), index + 1) != null) {
                throw new IllegalArgumentException(
                        "Chunk " + candidates.get(index).chunkId() + " appears twice among the candidates");
            }
        }

        return ranks;
    }

    private static void requireSamePosition(SearchHit lexical, SearchHit semantic) {
        if (!lexical.sourcePath().equals(semantic.sourcePath()) || lexical.chunkIndex() != semantic.chunkIndex()) {
            throw new IllegalArgumentException("Chunk " + lexical.chunkId() + " has different positions on both sides");
        }
    }

    private static OptionalInt rank(Integer rank) {
        return rank == null ? OptionalInt.empty() : OptionalInt.of(rank);
    }

    private static SearchHit fused(SearchHit base, OptionalInt lexicalRank, OptionalInt semanticRank) {
        // Lexical first, then semantic, so equal rank pairs always sum to the same double.
        double score = contribution(lexicalRank) + contribution(semanticRank);

        return new SearchHit(
                base.chunkId(),
                base.chunkIndex(),
                base.sourcePath(),
                base.documentType(),
                base.sourceLocation(),
                base.metadata(),
                new SearchScore(score, SearchScore.Kind.RECIPROCAL_RANK_FUSION),
                base.snippet(),
                base.stableId(),
                Optional.of(new FusedRanks(lexicalRank, semanticRank)));
    }

    private static double contribution(OptionalInt rank) {
        return rank.isPresent() ? 1.0 / (K + rank.getAsInt()) : 0;
    }

    /** Orders portable paths by their UTF-8 bytes, as SQLite's BINARY collation orders the stored paths. */
    private static int compareBinaryPaths(Path first, Path second) {
        return Arrays.compareUnsigned(
                ContentPath.encode(first).getBytes(StandardCharsets.UTF_8),
                ContentPath.encode(second).getBytes(StandardCharsets.UTF_8));
    }
}
