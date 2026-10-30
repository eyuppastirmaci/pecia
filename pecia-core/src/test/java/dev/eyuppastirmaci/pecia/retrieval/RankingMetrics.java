package dev.eyuppastirmaci.pecia.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scores one query's ranking of files against its labels. Recall and the reciprocal rank count answers only; nDCG
 * gives answers a gain of 3 and related files a gain of 1 (2^grade - 1).
 *
 * @param recallAt5 the share of answers among the first five files
 * @param recallAt10 the share of answers among the first ten files
 * @param reciprocalRank one over the rank of the first answer anywhere in the ranking, or 0 when none appears
 * @param ndcgAt10 the discounted gain of the first ten files over the best gain the labels allow
 */
public record RankingMetrics(double recallAt5, double recallAt10, double reciprocalRank, double ndcgAt10) {

    /** Collapses hits, given as their files' paths in rank order, to files ranked by their first hit. */
    public static List<String> files(List<String> hitPaths) {
        return List.copyOf(new LinkedHashSet<>(hitPaths));
    }

    /** Scores a ranking of distinct files. */
    public static RankingMetrics of(RetrievalQuery query, List<String> ranking) {
        return new RankingMetrics(
                recallAt(query, ranking, 5),
                recallAt(query, ranking, 10),
                reciprocalRank(query, ranking),
                ndcgAt(query, ranking, 10));
    }

    /** Returns the mean of each metric; an empty list has no mean. */
    public static RankingMetrics mean(List<RankingMetrics> scores) {
        if (scores.isEmpty()) {
            throw new IllegalArgumentException("No scores to average");
        }

        return new RankingMetrics(
                average(scores.stream().map(RankingMetrics::recallAt5)),
                average(scores.stream().map(RankingMetrics::recallAt10)),
                average(scores.stream().map(RankingMetrics::reciprocalRank)),
                average(scores.stream().map(RankingMetrics::ndcgAt10)));
    }

    static double recallAt(RetrievalQuery query, List<String> ranking, int k) {
        long found = top(ranking, k).stream().filter(query.answers()::contains).count();
        return (double) found / query.answers().size();
    }

    static double reciprocalRank(RetrievalQuery query, List<String> ranking) {
        for (int index = 0; index < ranking.size(); index++) {
            if (query.answers().contains(ranking.get(index))) {
                return 1.0 / (index + 1);
            }
        }

        return 0;
    }

    static double ndcgAt(RetrievalQuery query, List<String> ranking, int k) {
        List<Integer> ideal = new ArrayList<>();
        query.answers().forEach(answer -> ideal.add(2));
        query.related().forEach(related -> ideal.add(1));
        ideal.sort(Comparator.reverseOrder());

        return discountedGain(top(ranking, k).stream().map(query::grade).toList()) / discountedGain(top(ideal, k));
    }

    private static double discountedGain(List<Integer> grades) {
        double gain = 0;

        for (int index = 0; index < grades.size(); index++) {
            // Rank r is discounted by log2(r + 1), and ranks start at 1.
            gain += (Math.pow(2, grades.get(index)) - 1) / (Math.log(index + 2) / Math.log(2));
        }

        return gain;
    }

    private static <T> List<T> top(List<T> list, int k) {
        return list.subList(0, Math.min(k, list.size()));
    }

    private static double average(Stream<Double> values) {
        return values.mapToDouble(Double::doubleValue).average().orElseThrow();
    }
}
