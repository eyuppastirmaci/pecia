package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueryResultTest {

    private static final Warning WARNING = new Warning(
            Warning.Code.UNKNOWN_CONFIG_KEY,
            Path.of("").toAbsolutePath().resolve(".pecia.toml"),
            null,
            "unknown key x");

    @Test
    void resultCopiesItsListsAndRejectsNull() {
        List<Warning> warnings = new ArrayList<>(List.of(WARNING));

        QueryResult result = new QueryResult(List.of(), warnings);
        warnings.clear();

        assertEquals(List.of(WARNING), result.warnings());
        assertThrows(
                UnsupportedOperationException.class, () -> result.warnings().clear());
        assertThrows(NullPointerException.class, () -> new QueryResult(null, List.of()));
        assertThrows(NullPointerException.class, () -> new QueryResult(List.of(), null));
    }

    @Test
    void modesFallbacksAndCoverageMustAgree() {
        EmbeddingCoverage coverage = new EmbeddingCoverage(3, 0, 1);
        Optional<ModeFallback> fallback = Optional.of(new ModeFallback(QueryException.Reason.MODEL_NOT_CACHED, "x"));
        Optional<ModeFallback> none = Optional.empty();
        QueryResult lexical = new QueryResult(List.of(), List.of());

        assertNull(lexical.embeddingCoverage());
        assertEquals(SearchMode.LEXICAL, lexical.requestedMode());
        assertEquals(SearchMode.LEXICAL, lexical.effectiveMode());
        assertEquals(none, lexical.fallback());

        // Valid combinations.
        result(coverage, SearchMode.SEMANTIC, SearchMode.SEMANTIC, none);
        result(coverage, SearchMode.HYBRID, SearchMode.HYBRID, none);
        result(coverage, SearchMode.AUTO, SearchMode.HYBRID, none);
        result(null, SearchMode.AUTO, SearchMode.LEXICAL, fallback);

        // The effective mode is never auto, and explicit modes are never changed.
        assertThrows(IllegalArgumentException.class, () -> result(coverage, SearchMode.AUTO, SearchMode.AUTO, none));
        assertThrows(
                IllegalArgumentException.class, () -> result(coverage, SearchMode.AUTO, SearchMode.SEMANTIC, none));
        assertThrows(IllegalArgumentException.class, () -> result(null, SearchMode.HYBRID, SearchMode.LEXICAL, none));
        assertThrows(
                IllegalArgumentException.class, () -> result(coverage, SearchMode.SEMANTIC, SearchMode.HYBRID, none));
        // A fallback exactly when auto searched lexically.
        assertThrows(IllegalArgumentException.class, () -> result(null, SearchMode.AUTO, SearchMode.LEXICAL, none));
        assertThrows(
                IllegalArgumentException.class, () -> result(coverage, SearchMode.AUTO, SearchMode.HYBRID, fallback));
        assertThrows(
                IllegalArgumentException.class, () -> result(null, SearchMode.LEXICAL, SearchMode.LEXICAL, fallback));
        // Coverage exactly when embeddings were compared.
        assertThrows(
                IllegalArgumentException.class, () -> result(null, SearchMode.SEMANTIC, SearchMode.SEMANTIC, none));
        assertThrows(
                IllegalArgumentException.class, () -> result(coverage, SearchMode.LEXICAL, SearchMode.LEXICAL, none));
        assertThrows(NullPointerException.class, () -> result(null, null, SearchMode.LEXICAL, none));
        assertThrows(NullPointerException.class, () -> result(null, SearchMode.LEXICAL, null, none));
        assertThrows(NullPointerException.class, () -> result(null, SearchMode.LEXICAL, SearchMode.LEXICAL, null));
    }

    @Test
    void noticesFitTheModeThatProducedTheHits() {
        assertEquals(
                QueryNotice.Code.QUERY_CUT_TO_MODEL_INPUT,
                withNotice(SearchMode.SEMANTIC, QueryNotice.Code.QUERY_CUT_TO_MODEL_INPUT)
                        .notices()
                        .getFirst()
                        .code());
        withNotice(SearchMode.HYBRID, QueryNotice.Code.QUERY_CUT_TO_MODEL_INPUT);
        withNotice(SearchMode.HYBRID, QueryNotice.Code.LEXICAL_SIDE_SKIPPED);

        assertThrows(
                IllegalArgumentException.class,
                () -> withNotice(SearchMode.LEXICAL, QueryNotice.Code.QUERY_CUT_TO_MODEL_INPUT));
        assertThrows(
                IllegalArgumentException.class,
                () -> withNotice(SearchMode.SEMANTIC, QueryNotice.Code.LEXICAL_SIDE_SKIPPED));
        assertThrows(IllegalArgumentException.class, () -> new QueryNotice(QueryNotice.Code.LEXICAL_SIDE_SKIPPED, " "));
        assertThrows(NullPointerException.class, () -> new QueryNotice(null, "notice"));
        assertEquals(List.of(), new QueryResult(List.of(), List.of()).notices());
        assertThrows(
                IllegalArgumentException.class,
                () -> new QueryResult(
                        List.of(),
                        List.of(),
                        null,
                        SearchMode.LEXICAL,
                        SearchMode.LEXICAL,
                        Optional.empty(),
                        List.of(),
                        Optional.of(QueryShape.IDENTIFIER)),
                "Only hybrid results report the query shape");
        assertThrows(
                IllegalArgumentException.class,
                () -> new QueryResult(
                        List.of(),
                        List.of(),
                        new EmbeddingCoverage(1, 0, 0),
                        SearchMode.HYBRID,
                        SearchMode.HYBRID,
                        Optional.empty(),
                        List.of(),
                        Optional.empty()),
                "Hybrid results must report it");
    }

    @Test
    void aFallbackNamesAMissingSemanticPrerequisite() {
        for (QueryException.Reason reason : QueryException.Reason.values()) {
            if (ModeFallback.REASONS.contains(reason)) {
                assertEquals(reason, new ModeFallback(reason, "message").reason());
            } else {
                assertThrows(IllegalArgumentException.class, () -> new ModeFallback(reason, "message"), reason.name());
            }
        }

        assertEquals(6, ModeFallback.REASONS.size());
        assertThrows(
                IllegalArgumentException.class, () -> new ModeFallback(QueryException.Reason.MODEL_NOT_CACHED, " "));
        assertThrows(NullPointerException.class, () -> new ModeFallback(null, "message"));
        assertThrows(NullPointerException.class, () -> new ModeFallback(QueryException.Reason.MODEL_NOT_CACHED, null));
    }

    private static QueryResult result(
            EmbeddingCoverage coverage, SearchMode requested, SearchMode effective, Optional<ModeFallback> fallback) {
        return new QueryResult(
                List.of(), List.of(), coverage, requested, effective, fallback, List.of(), shapeFor(effective));
    }

    private static QueryResult withNotice(SearchMode effective, QueryNotice.Code code) {
        EmbeddingCoverage coverage = effective == SearchMode.LEXICAL ? null : new EmbeddingCoverage(1, 0, 0);
        Optional<ModeFallback> fallback = Optional.empty();

        return new QueryResult(
                List.of(),
                List.of(),
                coverage,
                effective,
                effective,
                fallback,
                List.of(new QueryNotice(code, "notice")),
                shapeFor(effective));
    }

    private static Optional<QueryShape> shapeFor(SearchMode effective) {
        return effective == SearchMode.HYBRID ? Optional.of(QueryShape.NATURAL_LANGUAGE) : Optional.empty();
    }

    @Test
    void exceptionCarriesCopiedWarningsOrNone() {
        List<Warning> warnings = new ArrayList<>(List.of(WARNING));

        QueryException withWarnings =
                new QueryException(QueryException.Reason.INDEX_NOT_FOUND, "missing", null, warnings);
        warnings.clear();

        assertEquals(List.of(WARNING), withWarnings.warnings());
        assertEquals(List.of(), new QueryException(QueryException.Reason.READ_FAILED, "failed", null).warnings());
        assertThrows(
                NullPointerException.class,
                () -> new QueryException(QueryException.Reason.READ_FAILED, "failed", null, null));
    }
}
