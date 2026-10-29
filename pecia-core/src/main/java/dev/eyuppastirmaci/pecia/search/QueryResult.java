package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import java.util.List;

/**
 * A successful query's ranked hits with the configuration warnings found while resolving the project.
 *
 * @param hits ranked hits; empty when nothing matches
 * @param warnings warnings in source order; they never affect the hits
 * @param embeddingCoverage for a semantic query, how many stored chunks had an embedding that could be compared with
 *     the query; chunks without one are never returned. Null for a lexical query
 */
public record QueryResult(List<SearchHit> hits, List<Warning> warnings, EmbeddingCoverage embeddingCoverage) {

    /**
     * Copies both lists.
     *
     * @throws NullPointerException if either list or any element is null
     */
    public QueryResult {
        hits = List.copyOf(hits);
        warnings = List.copyOf(warnings);
    }

    /** Creates a lexical query's result, which has no embedding coverage. */
    public QueryResult(List<SearchHit> hits, List<Warning> warnings) {
        this(hits, warnings, null);
    }
}
