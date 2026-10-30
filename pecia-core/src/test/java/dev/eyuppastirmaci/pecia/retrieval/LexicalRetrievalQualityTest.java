package dev.eyuppastirmaci.pecia.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lexical search over the labelled corpus stays at or above its committed thresholds for every kind of query, and
 * exact identifiers rank their answer first. It needs no model, so it runs with the unit tests.
 */
class LexicalRetrievalQualityTest {

    @TempDir
    static Path sandbox;

    private static RetrievalBaseline baseline;
    private static RetrievalEvaluation lexical;

    @BeforeAll
    static void indexAndQueryTheCorpus() throws Exception {
        RetrievalSet set = RetrievalSet.committed();
        baseline = RetrievalBaseline.committed(set);
        Path root = set.copyCorpus(sandbox.resolve("project"));
        PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

        IndexResult indexed = new IndexService(loader).index(root, IndexOptions.semantic(false));

        assertEquals(set.files().size(), indexed.indexedFiles(), "Every corpus file is indexed");
        lexical = RetrievalEvaluation.run(set, SearchMode.LEXICAL, new QueryService(loader), root);
        lexical.writeReport();
    }

    @Test
    void everyKindOfQueryMeetsItsThresholds() {
        assertEquals(List.of(), baseline.shortfalls(lexical), lexical::report);
    }

    @Test
    void exactIdentifiersRankTheirAnswerFirst() {
        assertEquals(List.of(), lexical.brokenConstraints(), lexical::report);
    }
}
