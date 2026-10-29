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
        return new QueryResult(List.of(), List.of(), coverage, requested, effective, fallback);
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
