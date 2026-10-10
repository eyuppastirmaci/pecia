package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.util.List;

/**
 * A successful query's ranked hits with the configuration warnings found while resolving the project.
 *
 * @param hits ranked hits; empty when nothing matches
 * @param warnings warnings in source order; they never affect the hits
 */
public record QueryResult(List<SearchHit> hits, List<Warning> warnings) {

    /**
     * Copies both lists.
     *
     * @throws NullPointerException if either list or any element is null
     */
    public QueryResult {
        hits = List.copyOf(hits);
        warnings = List.copyOf(warnings);
    }
}
