package dev.eyuppastirmaci.pecia.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.retrieval.RetrievalEvaluation.Outcome;
import dev.eyuppastirmaci.pecia.retrieval.RetrievalQuery.Kind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-kind means, broken constraints, the report, and the thresholds the baseline checks. */
class RetrievalEvaluationTest {

    @TempDir
    Path directory;

    @Test
    void metricsAreAveragedWithinEachKind() {
        RetrievalEvaluation evaluation = evaluation(SearchMode.LEXICAL, List.of("a"), List.of("x", "a"));

        Map<Kind, RankingMetrics> perKind = evaluation.perKind();

        // Identifiers: one query with its answer first and one with it second.
        assertEquals(0.75, perKind.get(Kind.IDENTIFIER).reciprocalRank(), 1e-9);
        assertEquals(1.0, perKind.get(Kind.KEYWORD).reciprocalRank(), 1e-9);
        assertEquals(List.of(Kind.values()), List.copyOf(perKind.keySet()));
    }

    @Test
    void aConstraintBreaksOnlyInTheModesItNames() {
        List<String> secondFirst = List.of("x", "a");

        assertEquals(
                List.of("pinned ranks x first"),
                evaluation(SearchMode.LEXICAL, secondFirst, secondFirst).brokenConstraints());
        assertEquals(
                List.of(),
                evaluation(SearchMode.SEMANTIC, secondFirst, secondFirst).brokenConstraints());
        assertEquals(
                List.of("pinned ranks nothing first"),
                evaluation(SearchMode.LEXICAL, List.of(), List.of()).brokenConstraints());
        assertEquals(
                List.of(),
                evaluation(SearchMode.LEXICAL, List.of("a"), List.of()).brokenConstraints());
    }

    @Test
    void theReportListsKindsThresholdsAndQueries() {
        String report =
                evaluation(SearchMode.HYBRID, List.of("a"), List.of("x", "a")).report();

        assertTrue(report.startsWith("# Retrieval quality: hybrid\n"), report);
        assertTrue(report.contains("| identifier | 2 | 1.000 | 1.000 | 0.750 | 0.815 |\n"), report);
        assertTrue(
                report.contains("[hybrid.identifier]\nrecall_at_5 = 1.00\nrecall_at_10 = 1.00\nmrr = 0.75\n"), report);
        assertTrue(report.contains("| `pinned` | identifier | #1 | a |\n"), report);
        assertTrue(report.contains("| `second` | identifier | #2 | x, a |\n"), report);
    }

    @Test
    void thresholdsAreRoundedDown() {
        assertEquals(new BigDecimal("0.68"), RetrievalEvaluation.threshold(0.6875));
        assertEquals(new BigDecimal("0.70"), RetrievalEvaluation.threshold(0.7));
        assertEquals(new BigDecimal("1.00"), RetrievalEvaluation.threshold(1));
        assertEquals(new BigDecimal("0.00"), RetrievalEvaluation.threshold(0.009));
    }

    @Test
    void theBaselineReportsEveryMetricBelowItsThreshold() throws IOException {
        RetrievalEvaluation evaluation = evaluation(SearchMode.LEXICAL, List.of("a"), List.of("x", "a"));
        StringBuilder toml = new StringBuilder();

        for (Kind kind : Kind.values()) {
            // Identifiers reach a reciprocal rank of 0.75; ask for 0.76 there and for exactly what is reached
            // elsewhere.
            String mrr = kind == Kind.IDENTIFIER ? "0.76" : "1.00";
            toml.append("[lexical.")
                    .append(RetrievalEvaluation.name(kind))
                    .append("]\nrecall_at_5 = 1.0\nrecall_at_10 = 1\nmrr = ")
                    .append(mrr)
                    .append("\nndcg_at_10 = 0\n");
        }

        RetrievalBaseline baseline = RetrievalBaseline.load(Files.writeString(directory.resolve("b.toml"), toml));

        assertEquals(List.of("lexical identifier mrr: 0.750 is below 0.76"), baseline.shortfalls(evaluation));
        assertTrue(baseline.of(SearchMode.SEMANTIC).isEmpty());
        assertThrows(
                IllegalStateException.class,
                () -> baseline.shortfalls(evaluation(SearchMode.HYBRID, List.of("a"), List.of("a"))));
    }

    @Test
    void baselinesThatBreakTheFormatAreRejected() throws IOException {
        String metrics = "recall_at_5 = 0.5\nrecall_at_10 = 0.5\nmrr = 0.5\nndcg_at_10 = 0.5\n";
        StringBuilder complete = new StringBuilder();

        for (Kind kind : Kind.values()) {
            complete.append("[semantic.")
                    .append(RetrievalEvaluation.name(kind))
                    .append("]\n")
                    .append(metrics);
        }

        assertTrue(load(complete.toString()).of(SearchMode.SEMANTIC).isPresent());
        assertRejected(complete.toString().replace("[semantic.turkish]", "[semantic.turkce]"), "no thresholds for");
        assertRejected(complete + "[semantic.extra]\n" + metrics, "unknown kinds");
        assertRejected(complete.toString().replace("[semantic.", "[auto."), "Unknown modes");
        assertRejected(complete.toString().replaceFirst("mrr = 0.5\n", ""), "must hold exactly");
        assertRejected(complete.toString().replaceFirst("mrr = 0.5", "mrr = 1.5"), "from 0 to 1");
        assertRejected(complete.toString().replaceFirst("mrr = 0.5", "mrr = \"0.5\""), "from 0 to 1");
    }

    private void assertRejected(String toml, String problem) throws IOException {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(toml));
        assertTrue(error.getMessage().contains(problem), error.getMessage());
    }

    private RetrievalBaseline load(String toml) throws IOException {
        return RetrievalBaseline.load(Files.writeString(directory.resolve("baseline.toml"), toml));
    }

    /**
     * Builds an evaluation with two identifier queries, {@code pinned} (which must rank its answer first in lexical
     * and hybrid mode) and {@code second}, and one query of every other kind that ranks its answer first.
     */
    private static RetrievalEvaluation evaluation(SearchMode mode, List<String> pinned, List<String> second) {
        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(outcome(query("pinned", Kind.IDENTIFIER, Set.of(SearchMode.LEXICAL, SearchMode.HYBRID)), pinned));
        outcomes.add(outcome(query("second", Kind.IDENTIFIER, Set.of()), second));

        for (Kind kind : List.of(Kind.KEYWORD, Kind.PARAPHRASE, Kind.MIXED, Kind.TURKISH)) {
            outcomes.add(outcome(query(kind.name(), kind, Set.of()), List.of("a")));
        }

        return new RetrievalEvaluation(mode, outcomes);
    }

    private static RetrievalQuery query(String id, Kind kind, Set<SearchMode> answerFirstIn) {
        return new RetrievalQuery(id, kind, id, List.of("a"), List.of(), answerFirstIn);
    }

    private static Outcome outcome(RetrievalQuery query, List<String> ranking) {
        return new Outcome(query, ranking, RankingMetrics.of(query, ranking));
    }
}
