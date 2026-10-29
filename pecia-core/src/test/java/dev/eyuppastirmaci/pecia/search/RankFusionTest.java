package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Reciprocal rank fusion of fixed lexical and semantic candidate lists. */
class RankFusionTest {

    @Test
    void scoresAreTheSumOfReciprocalRanksAndAMissingRankContributesZero() {
        List<SearchHit> lexical = List.of(lexical(1, "a.md"), lexical(2, "b.md"), lexical(3, "c.md"));
        List<SearchHit> semantic = List.of(semantic(3, "c.md"), semantic(4, "d.md"), semantic(1, "a.md"));

        List<SearchHit> fused = RankFusion.fuse(lexical, semantic, 10);

        assertEquals(List.of(1L, 3L, 2L, 4L), ids(fused));
        assertEquals(1.0 / 61 + 1.0 / 63, fused.get(0).score().value());
        assertEquals(1.0 / 63 + 1.0 / 61, fused.get(1).score().value());
        assertEquals(1.0 / 62, fused.get(2).score().value());
        assertEquals(1.0 / 62, fused.get(3).score().value());
        assertEquals(
                new FusedRanks(OptionalInt.of(1), OptionalInt.of(3)),
                fused.get(0).fusedRanks().orElseThrow());
        assertEquals(
                new FusedRanks(OptionalInt.of(2), OptionalInt.empty()),
                fused.get(2).fusedRanks().orElseThrow());
        assertEquals(
                new FusedRanks(OptionalInt.empty(), OptionalInt.of(2)),
                fused.get(3).fusedRanks().orElseThrow());

        for (SearchHit hit : fused) {
            assertEquals(SearchScore.Kind.RECIPROCAL_RANK_FUSION, hit.score().kind());
        }
    }

    @Test
    void equalScoresBreakByBinaryPathThenPositionThenChunkId() {
        // Each chunk is ranked once at rank 1 or once at rank 2 on one side, so equal ranks give equal scores.
        List<SearchHit> lexical = List.of(lexical(10, "b.md", 0), lexical(11, "é.md", 0));
        List<SearchHit> semantic = List.of(semantic(12, "a.md", 1), semantic(13, "z.md", 0));
        List<SearchHit> samePosition = List.of(lexical(21, "same.md", 2), lexical(20, "same.md", 1));
        List<SearchHit> other = List.of(semantic(22, "same.md", 1), semantic(23, "same.md", 3));

        assertEquals(List.of(12L, 10L, 13L, 11L), ids(RankFusion.fuse(lexical, semantic, 10)));
        assertEquals(List.of(22L, 21L, 20L, 23L), ids(RankFusion.fuse(samePosition, other, 10)));
        assertEquals(
                List.of(30L, 31L),
                ids(RankFusion.fuse(List.of(lexical(31, "same.md", 0)), List.of(semantic(30, "same.md", 0)), 10)));
    }

    @Test
    void aFusedHitKeepsTheLexicalFieldsWhenThereAreAnyAndTheSemanticOnesOtherwise() {
        SearchHit lexicalHit = hit(1, "a.md", 0, SearchScore.Kind.SQLITE_BM25, "the matched window");
        SearchHit semanticHit = hit(1, "a.md", 0, SearchScore.Kind.COSINE_SIMILARITY, "the chunk's start");
        SearchHit semanticOnly = hit(2, "b.md", 0, SearchScore.Kind.COSINE_SIMILARITY, "another start");

        List<SearchHit> fused = RankFusion.fuse(List.of(lexicalHit), List.of(semanticHit, semanticOnly), 10);

        assertEquals("the matched window", fused.get(0).snippet());
        assertEquals(lexicalHit.metadata(), fused.get(0).metadata());
        assertEquals("another start", fused.get(1).snippet());
        assertEquals(semanticOnly.sourceLocation(), fused.get(1).sourceLocation());
    }

    @Test
    void aSmallerLimitReturnsAPrefixAndCandidatesBeyondTheDepthAreIgnored() {
        List<SearchHit> lexical = LongStream.rangeClosed(1, 150)
                .mapToObj(id -> lexical(id, "l" + id + ".md"))
                .toList();
        List<SearchHit> semantic = LongStream.rangeClosed(1001, 1150)
                .mapToObj(id -> semantic(id, "s" + id + ".md"))
                .toList();

        List<SearchHit> all = RankFusion.fuse(lexical, semantic, SearchRequest.MAX_LIMIT);

        assertEquals(all.subList(0, 7), RankFusion.fuse(lexical, semantic, 7));
        assertEquals(all.subList(0, 1), RankFusion.fuse(lexical, semantic, 1));
        // A chunk at lexical rank 101 and semantic rank 1 keeps only its semantic contribution, so it ties with the
        // lexical rank-1 chunk and sorts after it by path, instead of beating it with a second contribution.
        List<SearchHit> deep = new ArrayList<>(lexical.subList(0, 100));
        deep.add(lexical(500, "x.md"));
        List<SearchHit> fused = RankFusion.fuse(deep, List.of(semantic(500, "x.md")), 2);

        assertEquals(List.of(1L, 500L), ids(fused));
        assertEquals(
                new FusedRanks(OptionalInt.empty(), OptionalInt.of(1)),
                fused.get(1).fusedRanks().orElseThrow());
    }

    @Test
    void anEmptySideLeavesTheOtherSidesOrder() {
        List<SearchHit> semantic = List.of(semantic(3, "c.md"), semantic(1, "z.md"), semantic(2, "a.md"));

        assertEquals(List.of(3L, 1L, 2L), ids(RankFusion.fuse(List.of(), semantic, 10)));
        assertEquals(List.of(), RankFusion.fuse(List.of(), List.of(), 10));
    }

    @Test
    void invalidCandidatesAndLimitsAreRefused() {
        List<SearchHit> lexical = List.of(lexical(1, "a.md"));

        assertThrows(IllegalArgumentException.class, () -> RankFusion.fuse(lexical, List.of(), 0));
        assertThrows(
                IllegalArgumentException.class, () -> RankFusion.fuse(lexical, List.of(), SearchRequest.MAX_LIMIT + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> RankFusion.fuse(List.of(lexical(1, "a.md"), lexical(1, "a.md")), List.of(), 10));
        assertThrows(
                IllegalArgumentException.class, () -> RankFusion.fuse(List.of(semantic(1, "a.md")), List.of(), 10));
        assertThrows(IllegalArgumentException.class, () -> RankFusion.fuse(List.of(), lexical, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> RankFusion.fuse(lexical, List.of(semantic(1, "other.md")), 10),
                "One chunk ID at two positions means the lists came from different index states");
        assertThrows(NullPointerException.class, () -> RankFusion.fuse(null, List.of(), 10));
        assertThrows(NullPointerException.class, () -> RankFusion.fuse(List.of(), null, 10));

        List<SearchHit> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> RankFusion.fuse(withNull, List.of(), 10));
    }

    @Test
    void fusedRanksAndHitsKeepTheirInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new FusedRanks(OptionalInt.empty(), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FusedRanks(OptionalInt.of(0), OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FusedRanks(OptionalInt.of(1), OptionalInt.of(0)));
        assertThrows(NullPointerException.class, () -> new FusedRanks(null, OptionalInt.of(1)));

        SearchHit lexicalHit = lexical(1, "a.md");
        FusedRanks ranks = new FusedRanks(OptionalInt.of(1), OptionalInt.empty());

        assertEquals(Optional.empty(), lexicalHit.fusedRanks());
        assertThrows(
                IllegalArgumentException.class, () -> withRanks(lexicalHit, lexicalHit.score(), Optional.of(ranks)));
        assertThrows(
                IllegalArgumentException.class,
                () -> withRanks(
                        lexicalHit, new SearchScore(0.5, SearchScore.Kind.RECIPROCAL_RANK_FUSION), Optional.empty()));
    }

    static SearchHit lexical(long id, String path) {
        return lexical(id, path, 0);
    }

    static SearchHit lexical(long id, String path, int index) {
        return hit(id, path, index, SearchScore.Kind.SQLITE_BM25, "lexical " + id);
    }

    static SearchHit semantic(long id, String path) {
        return semantic(id, path, 0);
    }

    static SearchHit semantic(long id, String path, int index) {
        return hit(id, path, index, SearchScore.Kind.COSINE_SIMILARITY, "semantic " + id);
    }

    static SearchHit hit(long id, String path, int index, SearchScore.Kind kind, String snippet) {
        return new SearchHit(
                id,
                index,
                Path.of(path),
                DocumentType.MARKDOWN,
                new LineRange(index + 1, index + 1),
                new ChunkMetadata(List.of(snippet), Map.of()),
                new SearchScore(kind == SearchScore.Kind.SQLITE_BM25 ? -id : 1.0 / id, kind),
                snippet);
    }

    static List<Long> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::chunkId).toList();
    }

    private static SearchHit withRanks(SearchHit hit, SearchScore score, Optional<FusedRanks> ranks) {
        return new SearchHit(
                hit.chunkId(),
                hit.chunkIndex(),
                hit.sourcePath(),
                hit.documentType(),
                hit.sourceLocation(),
                hit.metadata(),
                score,
                hit.snippet(),
                hit.stableId(),
                ranks);
    }
}
