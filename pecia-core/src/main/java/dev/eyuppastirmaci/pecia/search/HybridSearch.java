package dev.eyuppastirmaci.pecia.search;

import java.util.List;
import java.util.Optional;

/**
 * Hybrid search over the lexical index and the stored embeddings of one embedding profile, obtained from {@link
 * dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage#hybridSearch}. Does not own or close storage, and adds no
 * concurrent-use guarantee.
 */
public abstract class HybridSearch {

    /** Creates a search service that validates requests and query embeddings before retrieval. */
    protected HybridSearch() {}

    /**
     * Returns chunks ranked by reciprocal rank fusion of their lexical and semantic ranks, best first. Each side
     * contributes its first 100 candidates whatever the limit, both read from one index snapshot, so a smaller limit
     * always returns a prefix of a larger one. Scores are of kind {@link SearchScore.Kind#RECIPROCAL_RANK_FUSION} and
     * each hit carries its {@link FusedRanks}. Text without searchable characters has no lexical candidates, which
     * leaves the semantic order; chunks without an embedding of the profile can still come from the lexical side.
     *
     * @param request the query text, compiled as for lexical search, and the limit
     * @param queryEmbedding the same text embedded under the profile, with only finite values
     * @return up to {@code request.limit()} hits, empty when neither side has a candidate
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the request has too many parts, or the embedding has non-finite values or
     *     the wrong length
     * @throws SearchException if storage is closed or reading fails, preserving the cause
     */
    public final List<SearchHit> search(SearchRequest request, float[] queryEmbedding) throws SearchException {
        Optional<String> matchExpression = new LexicalQueryCompiler().compile(request);
        SemanticSearch.requireFinite(queryEmbedding);
        Candidates candidates = retrieve(matchExpression, queryEmbedding.clone(), RankFusion.CANDIDATE_DEPTH);

        return RankFusion.fuse(candidates.lexical(), candidates.semantic(), request.limit());
    }

    /**
     * Reads both sides' candidates from one index snapshot.
     *
     * @param matchExpression the compiled lexical expression, empty when the text has no searchable characters
     * @param queryEmbedding the validated query embedding
     * @param depth how many candidates each side returns at most
     */
    protected abstract Candidates retrieve(Optional<String> matchExpression, float[] queryEmbedding, int depth)
            throws SearchException;

    /** Both sides' candidates in their own ranked orders, read from one index snapshot. */
    protected record Candidates(List<SearchHit> lexical, List<SearchHit> semantic) {

        /** Copies both lists. */
        public Candidates {
            lexical = List.copyOf(lexical);
            semantic = List.copyOf(semantic);
        }
    }
}
