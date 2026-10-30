package dev.eyuppastirmaci.pecia.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.ReferenceQueryServices;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Semantic and hybrid search over the labelled corpus with the real model stay at or above their committed thresholds,
 * and exact identifiers rank their answer first in hybrid mode. Only {@code referenceTest} runs it, with the model in
 * the cache that {@code PECIA_CACHE_DIR} names; it never downloads, and it places the vector extension in its own
 * directory.
 */
@Tag("reference")
class SemanticRetrievalQualityTest {

    @TempDir
    static Path sandbox;

    private static RetrievalBaseline baseline;
    private static final Map<SearchMode, RetrievalEvaluation> EVALUATIONS = new EnumMap<>(SearchMode.class);

    @BeforeAll
    static void indexAndQueryTheCorpusWithTheCachedModel() throws Exception {
        ModelOutcome cached = new ModelCache()
                .find()
                .orElseThrow(() -> new AssertionError(
                        "The retrieval check needs the cached model; set PECIA_CACHE_DIR to a cache that holds it"));
        RetrievalSet set = RetrievalSet.committed();
        baseline = RetrievalBaseline.committed(set);
        Path root = set.copyCorpus(sandbox.resolve("project"));
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

        IndexResult indexed =
                IndexService.withModelProvider(loader, listener -> cached).index(root, IndexOptions.semantic(true));

        assertEquals(set.files().size(), indexed.indexedFiles(), "Every corpus file is indexed");
        assertEquals(indexed.writtenChunks(), indexed.embeddedChunks(), "Every chunk is embedded");
        QueryService service = ReferenceQueryServices.withVectorExtensionIn(loader, sandbox.resolve("cache"));

        for (SearchMode mode : List.of(SearchMode.SEMANTIC, SearchMode.HYBRID)) {
            RetrievalEvaluation evaluation = RetrievalEvaluation.run(set, mode, service, root);
            evaluation.writeReport();
            EVALUATIONS.put(mode, evaluation);
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"SEMANTIC", "HYBRID"})
    void everyKindOfQueryMeetsItsThresholds(SearchMode mode) {
        RetrievalEvaluation evaluation = EVALUATIONS.get(mode);
        assertEquals(List.of(), baseline.shortfalls(evaluation), evaluation::report);
    }

    @ParameterizedTest
    @EnumSource(names = {"SEMANTIC", "HYBRID"})
    void exactIdentifiersRankTheirAnswerFirstWhereRequired(SearchMode mode) {
        RetrievalEvaluation evaluation = EVALUATIONS.get(mode);

        assertEquals(List.of(), evaluation.brokenConstraints(), evaluation::report);
    }
}
