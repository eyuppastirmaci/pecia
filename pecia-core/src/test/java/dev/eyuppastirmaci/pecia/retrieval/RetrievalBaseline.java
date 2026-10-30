package dev.eyuppastirmaci.pecia.retrieval;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.retrieval.RetrievalQuery.Kind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The committed thresholds: for each measured mode, the lowest value each metric of each kind may reach. A mode
 * without a table has not been measured yet and is only reported.
 */
public record RetrievalBaseline(Map<SearchMode, Map<Kind, Map<String, Double>>> thresholds) {

    private static final List<SearchMode> MODES = List.of(SearchMode.LEXICAL, SearchMode.SEMANTIC, SearchMode.HYBRID);

    public RetrievalBaseline {
        thresholds = Map.copyOf(thresholds);
    }

    /** Loads {@code baseline.toml} beside the set's corpus. */
    public static RetrievalBaseline committed(RetrievalSet set) throws IOException {
        return load(set.corpus().resolveSibling("baseline.toml"));
    }

    /**
     * Loads and validates a baseline file.
     *
     * @throws IllegalArgumentException if a mode lacks a kind, a kind lacks a metric, a name is unknown, or a value
     *     is not between 0 and 1
     */
    public static RetrievalBaseline load(Path file) throws IOException {
        TomlParseResult parsed = Toml.parse(file);

        if (parsed.hasErrors()) {
            throw new IllegalArgumentException(file.getFileName() + ": " + parsed.errors());
        }

        Map<SearchMode, Map<Kind, Map<String, Double>>> thresholds = new EnumMap<>(SearchMode.class);
        Set<String> modeNames = new HashSet<>(parsed.keySet());

        for (SearchMode mode : MODES) {
            String modeName = mode.name().toLowerCase(Locale.ROOT);

            if (modeNames.remove(modeName)) {
                thresholds.put(mode, kinds(parsed.getTable(modeName), modeName));
            }
        }

        if (!modeNames.isEmpty()) {
            throw new IllegalArgumentException("Unknown modes in the baseline: " + modeNames);
        }

        return new RetrievalBaseline(thresholds);
    }

    /** Returns the thresholds of a mode, or empty when the mode has not been measured yet. */
    public Optional<Map<Kind, Map<String, Double>>> of(SearchMode mode) {
        return Optional.ofNullable(thresholds.get(mode));
    }

    /** Returns every metric of the evaluation that fell below its threshold, or an empty list when all hold. */
    public List<String> shortfalls(RetrievalEvaluation evaluation) {
        Map<Kind, Map<String, Double>> limits = of(evaluation.mode())
                .orElseThrow(() -> new IllegalStateException("No thresholds for " + evaluation.modeName()));
        List<String> shortfalls = new ArrayList<>();

        evaluation.perKind().forEach((kind, metrics) -> {
            for (String metric : RetrievalEvaluation.METRIC_ORDER) {
                double value = RetrievalEvaluation.METRICS.get(metric).applyAsDouble(metrics);
                double limit = limits.get(kind).get(metric);

                if (value < limit) {
                    shortfalls.add(String.format(
                            Locale.ROOT,
                            "%s %s %s: %.3f is below %.2f",
                            evaluation.modeName(),
                            RetrievalEvaluation.name(kind),
                            metric,
                            value,
                            limit));
                }
            }
        });

        return shortfalls;
    }

    private static Map<Kind, Map<String, Double>> kinds(TomlTable table, String mode) {
        if (table == null) {
            throw new IllegalArgumentException(mode + " is not a table");
        }

        Map<Kind, Map<String, Double>> kinds = new EnumMap<>(Kind.class);
        Set<String> names = new HashSet<>(table.keySet());

        for (Kind kind : Kind.values()) {
            String name = RetrievalEvaluation.name(kind);

            if (!names.remove(name)) {
                throw new IllegalArgumentException(mode + " has no thresholds for " + name);
            }

            kinds.put(kind, metrics(table.getTable(name), mode + "." + name));
        }

        if (!names.isEmpty()) {
            throw new IllegalArgumentException(mode + " has unknown kinds " + names);
        }

        return kinds;
    }

    private static Map<String, Double> metrics(TomlTable table, String where) {
        if (table == null) {
            throw new IllegalArgumentException(where + " is not a table");
        }

        if (!table.keySet().equals(Set.copyOf(RetrievalEvaluation.METRIC_ORDER))) {
            throw new IllegalArgumentException(
                    where + " must hold exactly " + RetrievalEvaluation.METRIC_ORDER + ", not " + table.keySet());
        }

        Map<String, Double> metrics = new HashMap<>();

        for (String metric : RetrievalEvaluation.METRIC_ORDER) {
            Object value = table.get(metric);
            // TOML writes 0 and 1 as integers, so both kinds of number are accepted.
            double limit = value instanceof Number number ? number.doubleValue() : Double.NaN;

            if (!(limit >= 0 && limit <= 1)) {
                throw new IllegalArgumentException(where + "." + metric + " must be a number from 0 to 1");
            }

            metrics.put(metric, limit);
        }

        return Map.copyOf(metrics);
    }
}
