package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.testing.PropertySeeds;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Fusion agrees with a small model that scores the union of both sides' first 100 candidates from their positions and
 * sorts it by score, binary path, chunk position, and chunk ID. Chunk IDs fix each chunk's path and position, as one
 * index snapshot does, and the few paths and positions make ties common.
 */
class RankFusionPropertiesTest {

    @Property(seed = PropertySeeds.FIXED, tries = 500)
    void fusionMatchesTheModelAndSmallerLimitsReturnPrefixes(
            @ForAll("candidateIds") List<Long> lexicalIds,
            @ForAll("candidateIds") List<Long> semanticIds,
            @ForAll @IntRange(min = 1, max = SearchRequest.MAX_LIMIT) int limit) {
        List<SearchHit> lexical = lexicalIds.stream()
                .map(id -> hit(id, SearchScore.Kind.SQLITE_BM25))
                .toList();
        List<SearchHit> semantic = semanticIds.stream()
                .map(id -> hit(id, SearchScore.Kind.COSINE_SIMILARITY))
                .toList();

        List<SearchHit> full = RankFusion.fuse(lexical, semantic, SearchRequest.MAX_LIMIT);
        List<SearchHit> limited = RankFusion.fuse(lexical, semantic, limit);

        assertEquals(model(lexicalIds, semanticIds, SearchRequest.MAX_LIMIT), RankFusionTest.ids(full));
        assertEquals(full.subList(0, Math.min(limit, full.size())), limited);

        for (SearchHit hit : full) {
            FusedRanks ranks = hit.fusedRanks().orElseThrow();

            assertEquals(
                    score(rankOf(lexicalIds, hit.chunkId()), rankOf(semanticIds, hit.chunkId())),
                    hit.score().value());
            assertEquals(rankOf(lexicalIds, hit.chunkId()), ranks.lexical().orElse(0));
            assertEquals(rankOf(semanticIds, hit.chunkId()), ranks.semantic().orElse(0));
        }
    }

    @Provide
    Arbitrary<List<Long>> candidateIds() {
        return Arbitraries.longs().between(1, 260).list().uniqueElements().ofMaxSize(130);
    }

    private static List<Long> model(List<Long> lexical, List<Long> semantic, int limit) {
        Set<Long> union = new LinkedHashSet<>();
        union.addAll(lexical.subList(0, Math.min(lexical.size(), RankFusion.CANDIDATE_DEPTH)));
        union.addAll(semantic.subList(0, Math.min(semantic.size(), RankFusion.CANDIDATE_DEPTH)));
        Map<Long, Double> scores = new HashMap<>();

        for (long id : union) {
            scores.put(id, score(rankOf(lexical, id), rankOf(semantic, id)));
        }

        return union.stream()
                .sorted(Comparator.comparing((Long id) -> scores.get(id))
                        .reversed()
                        .thenComparing((first, second) -> Arrays.compareUnsigned(
                                path(first).getBytes(StandardCharsets.UTF_8),
                                path(second).getBytes(StandardCharsets.UTF_8)))
                        .thenComparingInt(RankFusionPropertiesTest::position)
                        .thenComparingLong(id -> id))
                .limit(limit)
                .toList();
    }

    /** Returns the 1-based rank among the side's first 100 candidates, or 0 when the side did not rank the chunk. */
    private static int rankOf(List<Long> ids, long id) {
        int index = ids.indexOf(id);

        return index < 0 || index >= RankFusion.CANDIDATE_DEPTH ? 0 : index + 1;
    }

    private static double score(int lexicalRank, int semanticRank) {
        return (lexicalRank == 0 ? 0 : 1.0 / (60 + lexicalRank)) + (semanticRank == 0 ? 0 : 1.0 / (60 + semanticRank));
    }

    private static SearchHit hit(long id, SearchScore.Kind kind) {
        return RankFusionTest.hit(id, path(id), position(id), kind, "chunk " + id);
    }

    private static String path(long id) {
        // Non-ASCII names, whose UTF-8 order differs from UTF-16 order for "～" and the emoji, exercise the
        // binary path order.
        return List.of("a.md", "b.md", "Z.md", "é.md", "～.md", "😀.md").get((int) (id % 6));
    }

    private static int position(long id) {
        return (int) (id / 6 % 3);
    }
}
