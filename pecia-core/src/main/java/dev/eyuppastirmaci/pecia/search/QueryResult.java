package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A successful query's ranked hits with the configuration warnings found while resolving the project, and the search
 * mode that produced them.
 *
 * @param hits ranked hits; empty when nothing matches
 * @param warnings warnings in source order; they never affect the hits
 * @param embeddingCoverage when the query compared embeddings, how many stored chunks had one that could be compared;
 *     chunks without one never come from the semantic side. Null when the query searched only lexically
 * @param requestedMode the caller's mode, or {@code search.mode} when the caller left it to the configuration
 * @param effectiveMode the mode that produced the hits; never {@link SearchMode#AUTO}
 * @param fallback why an {@code auto} query searched lexically; present exactly then
 * @param notices what the caller should know about how the text was searched; only queries that compared embeddings
 *     have any
 */
public record QueryResult(
        List<SearchHit> hits,
        List<Warning> warnings,
        EmbeddingCoverage embeddingCoverage,
        SearchMode requestedMode,
        SearchMode effectiveMode,
        Optional<ModeFallback> fallback,
        List<QueryNotice> notices) {

    /**
     * Copies the lists and checks that the modes, fallback, coverage, and notices agree.
     *
     * @throws NullPointerException if a list, any element, a mode, or the fallback is null
     * @throws IllegalArgumentException if the effective mode is {@code AUTO}; an explicit mode differs from the
     *     effective one; {@code auto} produced a mode other than lexical or hybrid; a fallback is present other than
     *     for {@code auto} searching lexically, or missing then; coverage is present exactly when it is not expected;
     *     or a notice does not fit the effective mode
     */
    public QueryResult {
        hits = List.copyOf(hits);
        warnings = List.copyOf(warnings);
        notices = List.copyOf(notices);
        Objects.requireNonNull(requestedMode, "requestedMode");
        Objects.requireNonNull(effectiveMode, "effectiveMode");
        Objects.requireNonNull(fallback, "fallback");

        if (effectiveMode == SearchMode.AUTO) {
            throw new IllegalArgumentException("The effective mode is never auto");
        }

        if (requestedMode == SearchMode.AUTO ? effectiveMode == SearchMode.SEMANTIC : effectiveMode != requestedMode) {
            throw new IllegalArgumentException(requestedMode + " cannot produce " + effectiveMode + " results");
        }

        if (fallback.isPresent() != (requestedMode == SearchMode.AUTO && effectiveMode == SearchMode.LEXICAL)) {
            throw new IllegalArgumentException("Only an auto query that searched lexically has a fallback");
        }

        if ((embeddingCoverage == null) != (effectiveMode == SearchMode.LEXICAL)) {
            throw new IllegalArgumentException("Coverage is reported exactly when embeddings were compared");
        }

        for (QueryNotice notice : notices) {
            boolean fits =
                    switch (notice.code()) {
                        case QUERY_CUT_TO_MODEL_INPUT -> effectiveMode != SearchMode.LEXICAL;
                        case LEXICAL_SIDE_SKIPPED -> effectiveMode == SearchMode.HYBRID;
                    };

            if (!fits) {
                throw new IllegalArgumentException(notice.code() + " does not apply to " + effectiveMode + " results");
            }
        }
    }

    /** Creates an explicitly lexical query's result. */
    public QueryResult(List<SearchHit> hits, List<Warning> warnings) {
        this(hits, warnings, null, SearchMode.LEXICAL, SearchMode.LEXICAL, Optional.empty(), List.of());
    }
}
