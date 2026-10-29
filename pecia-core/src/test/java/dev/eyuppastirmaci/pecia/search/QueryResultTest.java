package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    void onlySemanticResultsCarryEmbeddingCoverage() {
        EmbeddingCoverage coverage = new EmbeddingCoverage(3, 0, 1);

        assertNull(new QueryResult(List.of(), List.of()).embeddingCoverage());
        assertEquals(coverage, new QueryResult(List.of(), List.of(), coverage).embeddingCoverage());
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
