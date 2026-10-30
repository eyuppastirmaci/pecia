package dev.eyuppastirmaci.pecia.retrieval;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.retrieval.RetrievalQuery.Kind;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryOptions;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Every query of the set run in one mode, with each query's ranking of files and the metrics per kind.
 *
 * @param outcomes one per query, in the order of the set
 */
public record RetrievalEvaluation(SearchMode mode, List<Outcome> outcomes) {

    private static final String REPORT_DIRECTORY = "pecia.retrieval.reports";
    // Column names and getters of the metrics, in report and baseline order.
    static final Map<String, ToDoubleFunction<RankingMetrics>> METRICS = Map.of(
            "recall_at_5", RankingMetrics::recallAt5,
            "recall_at_10", RankingMetrics::recallAt10,
            "mrr", RankingMetrics::reciprocalRank,
            "ndcg_at_10", RankingMetrics::ndcgAt10);
    static final List<String> METRIC_ORDER = List.of("recall_at_5", "recall_at_10", "mrr", "ndcg_at_10");

    public RetrievalEvaluation {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * Runs every query in {@code mode} against the index of {@code root}, asking for the most hits a request allows so
     * that files beyond the first chunks are ranked too.
     */
    public static RetrievalEvaluation run(RetrievalSet set, SearchMode mode, QueryService service, Path root)
            throws QueryException {
        List<Outcome> outcomes = new ArrayList<>();

        for (RetrievalQuery query : set.queries()) {
            List<SearchHit> hits = service.query(
                            root, new SearchRequest(query.text(), SearchRequest.MAX_LIMIT), QueryOptions.mode(mode))
                    .hits();
            List<String> ranking = RankingMetrics.files(hits.stream()
                    .map(hit -> FileWalker.portablePath(hit.sourcePath()))
                    .toList());
            outcomes.add(new Outcome(query, ranking, RankingMetrics.of(query, ranking)));
        }

        return new RetrievalEvaluation(mode, outcomes);
    }

    /** Returns the mean metrics of each kind, in the order of {@link Kind}. */
    public Map<Kind, RankingMetrics> perKind() {
        Map<Kind, RankingMetrics> perKind = new EnumMap<>(Kind.class);

        for (Kind kind : Kind.values()) {
            perKind.put(kind, RankingMetrics.mean(metricsOf(kind)));
        }

        return perKind;
    }

    /** Returns the queries that require an answer first in this mode but rank another file first, with that file. */
    public List<String> brokenConstraints() {
        List<String> broken = new ArrayList<>();

        for (Outcome outcome : outcomes) {
            RetrievalQuery query = outcome.query();

            if (query.answerFirstIn().contains(mode) && outcome.metrics().reciprocalRank() < 1) {
                String first = outcome.ranking().isEmpty()
                        ? "nothing"
                        : outcome.ranking().getFirst();
                broken.add(query.id() + " ranks " + first + " first");
            }
        }

        return broken;
    }

    /**
     * Returns a Markdown report: the metrics per kind and over all queries, the thresholds these values would give
     * (rounded down to two decimals), and every query's first answer and leading files.
     */
    public String report() {
        StringBuilder report =
                new StringBuilder("# Retrieval quality: ").append(modeName()).append("\n\n");
        report.append("| Kind | Queries | Recall@5 | Recall@10 | MRR | nDCG@10 |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");

        for (Kind kind : Kind.values()) {
            appendRow(report, name(kind), metricsOf(kind));
        }

        appendRow(report, "all", outcomes.stream().map(Outcome::metrics).toList());
        report.append("\n## Thresholds these values give\n\n```toml\n");

        perKind().forEach((kind, metrics) -> {
            report.append('[').append(modeName()).append('.').append(name(kind)).append("]\n");
            METRIC_ORDER.forEach(metric -> report.append(metric)
                    .append(" = ")
                    .append(threshold(METRICS.get(metric).applyAsDouble(metrics)))
                    .append('\n'));
        });

        report.append(
                "```\n\n## Queries\n\n| Query | Kind | First answer | Leading files |\n| --- | --- | --- | --- |\n");

        for (Outcome outcome : outcomes) {
            double reciprocal = outcome.metrics().reciprocalRank();
            report.append("| `")
                    .append(outcome.query().text())
                    .append("` | ")
                    .append(name(outcome.query().kind()))
                    .append(" | ")
                    .append(reciprocal == 0 ? "none" : "#" + Math.round(1 / reciprocal))
                    .append(" | ")
                    .append(String.join(
                            ", ",
                            outcome.ranking()
                                    .subList(0, Math.min(3, outcome.ranking().size()))))
                    .append(" |\n");
        }

        return report.toString();
    }

    /**
     * Writes the report to {@code <mode>.md} in the directory the build names, or in {@code build/reports/retrieval}
     * when run from an IDE, and returns its path.
     */
    public Path writeReport() throws IOException {
        Path directory = Path.of(System.getProperty(REPORT_DIRECTORY, "build/reports/retrieval"));
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(modeName() + ".md"), report());
    }

    /** Rounds a value down to two decimals, the precision thresholds are kept at. */
    static BigDecimal threshold(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.FLOOR);
    }

    static String name(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    String modeName() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private List<RankingMetrics> metricsOf(Kind kind) {
        return outcomes.stream()
                .filter(outcome -> outcome.query().kind() == kind)
                .map(Outcome::metrics)
                .toList();
    }

    private static void appendRow(StringBuilder report, String label, List<RankingMetrics> scores) {
        RankingMetrics mean = RankingMetrics.mean(scores);
        report.append("| ").append(label).append(" | ").append(scores.size());
        METRIC_ORDER.forEach(metric -> report.append(" | ")
                .append(String.format(Locale.ROOT, "%.3f", METRICS.get(metric).applyAsDouble(mean))));
        report.append(" |\n");
    }

    /** One query's files in rank order and its metrics. */
    public record Outcome(RetrievalQuery query, List<String> ranking, RankingMetrics metrics) {

        public Outcome {
            ranking = List.copyOf(ranking);
        }
    }
}
