package dev.eyuppastirmaci.pecia.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.retrieval.RetrievalQuery.Kind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Each metric matches values computed by hand. */
class RankingMetricsTest {

    private static final double DELTA = 1e-9;
    private static final double LOG2_3 = Math.log(3) / Math.log(2);

    @Test
    void hitsCollapseToFilesRankedByTheirFirstChunk() {
        assertEquals(List.of("a", "b", "c"), RankingMetrics.files(List.of("a", "a", "b", "a", "c", "b")));
        assertEquals(List.of(), RankingMetrics.files(List.of()));
    }

    @Test
    void recallCountsTheAnswersWithinTheCutOff() {
        RetrievalQuery query = query(List.of("a", "b"), List.of("r"));
        List<String> ranking = List.of("x", "a", "r", "y", "z", "b", "w");

        // The related file does not count, and the second answer is sixth.
        assertEquals(0.5, RankingMetrics.recallAt(query, ranking, 5), DELTA);
        assertEquals(1.0, RankingMetrics.recallAt(query, ranking, 6), DELTA);
        assertEquals(1.0, RankingMetrics.recallAt(query, ranking, 10), DELTA);
    }

    @Test
    void theReciprocalRankIsThatOfTheFirstAnswer() {
        RetrievalQuery query = query(List.of("a", "b"), List.of("r"));

        assertEquals(1.0 / 3, RankingMetrics.reciprocalRank(query, List.of("r", "x", "b", "a")), DELTA);
        assertEquals(1.0, RankingMetrics.reciprocalRank(query, List.of("a")), DELTA);
        // The first answer is counted wherever it appears, even beyond rank ten.
        List<String> late = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "b");
        assertEquals(1.0 / 12, RankingMetrics.reciprocalRank(query, late), DELTA);
    }

    @Test
    void ndcgWeighsAnswersAboveRelatedFilesAndDiscountsLowerRanks() {
        RetrievalQuery query = query(List.of("a"), List.of("r"));
        // Gains: answer 3, related 1; ideal order is the answer, then the related file.
        double ideal = 3 + 1 / LOG2_3;

        assertEquals(1.0, RankingMetrics.ndcgAt(query, List.of("a", "r", "x"), 10), DELTA);
        assertEquals((1 + 3 / LOG2_3) / ideal, RankingMetrics.ndcgAt(query, List.of("r", "a"), 10), DELTA);
        assertEquals((3 / LOG2_3) / ideal, RankingMetrics.ndcgAt(query, List.of("x", "a"), 10), DELTA);
        assertEquals(0.0, RankingMetrics.ndcgAt(query, List.of("x", "y"), 10), DELTA);
    }

    @Test
    void ndcgOnlyCountsTheFirstKFilesAndTheBestKLabels() {
        RetrievalQuery query = query(List.of("a", "b"), List.of("r"));

        // At k = 1 the ideal is one answer, which the ranking matches, so the files beyond rank one do not matter.
        assertEquals(1.0, RankingMetrics.ndcgAt(query, List.of("b", "x", "a"), 1), DELTA);
        // At k = 2 the second answer is missing; the ideal holds both answers.
        assertEquals(3 / (3 + 3 / LOG2_3), RankingMetrics.ndcgAt(query, List.of("a", "x", "b"), 2), DELTA);
    }

    @Test
    void anEmptyRankingScoresZero() {
        RetrievalQuery query = query(List.of("a"), List.of());

        assertMetrics(new RankingMetrics(0, 0, 0, 0), RankingMetrics.of(query, List.of()));
    }

    @Test
    void scoresCombineEveryMetricAndAverageAcrossQueries() {
        RetrievalQuery query = query(List.of("a"), List.of());
        RankingMetrics first = RankingMetrics.of(query, List.of("a"));
        RankingMetrics second = RankingMetrics.of(query, List.of("1", "2", "3", "4", "5", "a"));

        assertMetrics(new RankingMetrics(1, 1, 1, 1), first);
        // The answer is sixth: outside the first five, inside the first ten.
        assertMetrics(new RankingMetrics(0, 1, 1.0 / 6, 1 / (Math.log(7) / Math.log(2))), second);
        assertMetrics(
                new RankingMetrics(0.5, 1, (1 + 1.0 / 6) / 2, (1 + 1 / (Math.log(7) / Math.log(2))) / 2),
                RankingMetrics.mean(List.of(first, second)));
        assertThrows(IllegalArgumentException.class, () -> RankingMetrics.mean(List.of()));
    }

    private static void assertMetrics(RankingMetrics expected, RankingMetrics actual) {
        assertEquals(expected.recallAt5(), actual.recallAt5(), DELTA, "Recall@5");
        assertEquals(expected.recallAt10(), actual.recallAt10(), DELTA, "Recall@10");
        assertEquals(expected.reciprocalRank(), actual.reciprocalRank(), DELTA, "reciprocal rank");
        assertEquals(expected.ndcgAt10(), actual.ndcgAt10(), DELTA, "nDCG@10");
    }

    private static RetrievalQuery query(List<String> answers, List<String> related) {
        return new RetrievalQuery("q", Kind.KEYWORD, "text", answers, related, Set.of());
    }
}
