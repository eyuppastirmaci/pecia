package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Query text limits apply to the search that runs, and queries tell callers when their text was only partly used. */
class QueryValidationTest {

    // 65 parts: one more than lexical search accepts, and far fewer tokens than the model takes.
    private static final SearchRequest TOO_MANY_PARTS =
            new SearchRequest("refunds ".repeat(65).strip());
    private static final Map<String, String> FILES = Map.of(
            "payments.md", "Payment validation runs before the order is confirmed.\n",
            "refunds.md", "Refunds are issued to the original card within five days.\n");

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final AtomicBoolean opened = new AtomicBoolean();
    private Path root;
    private QueryService service;

    @BeforeEach
    void prepareProject() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();

        for (var file : FILES.entrySet()) {
            Files.writeString(root.resolve(file.getKey()), file.getValue());
        }

        Path cache = sandbox.resolve("cache");
        service = new QueryService(
                loader,
                context -> {
                    opened.set(true);
                    return SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot());
                },
                () -> Optional.of(TinyModel.cached()),
                () -> VectorExtensionAccess.library(cache),
                () -> EmbeddingProfile.minilm());
    }

    @Test
    void explicitLexicalSearchRefusesTooManyPartsBeforeTheIndexOpens() throws Exception {
        indexSemantically();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.query(root, TOO_MANY_PARTS, QueryOptions.mode(SearchMode.LEXICAL)));
        assertFalse(opened.get());
    }

    @Test
    void autoRefusesTooManyPartsWhenItSearchesLexically() throws Exception {
        new IndexService(loader).index(root);

        assertThrows(IllegalArgumentException.class, () -> service.query(root, TOO_MANY_PARTS));
    }

    @Test
    void semanticSearchAcceptsTooManyPartsWithoutANotice() throws Exception {
        indexSemantically();

        QueryResult result = service.query(root, TOO_MANY_PARTS, QueryOptions.mode(SearchMode.SEMANTIC));

        assertEquals(FILES.size(), result.hits().size());
        assertEquals(List.of(), result.notices());
    }

    @Test
    void hybridSearchWithTooManyPartsUsesOnlyTheSemanticSideAndSaysSo() throws Exception {
        indexSemantically();
        List<Long> semantic = ids(service.query(root, TOO_MANY_PARTS, QueryOptions.mode(SearchMode.SEMANTIC)));

        for (QueryOptions options : List.of(QueryOptions.mode(SearchMode.HYBRID), QueryOptions.mode(SearchMode.AUTO))) {
            QueryResult result = service.query(root, TOO_MANY_PARTS, options);

            assertEquals(SearchMode.HYBRID, result.effectiveMode());
            assertEquals(semantic, ids(result));
            assertTrue(result.hits().stream()
                    .allMatch(hit -> hit.fusedRanks().orElseThrow().lexical().isEmpty()));
            assertEquals(
                    List.of(QueryNotice.Code.LEXICAL_SIDE_SKIPPED),
                    result.notices().stream().map(QueryNotice::code).toList());
        }

        QueryResult within = service.query(
                root, new SearchRequest("refunds ".repeat(64).strip()), QueryOptions.mode(SearchMode.HYBRID));
        assertEquals(List.of(), within.notices());
    }

    @Test
    void textLongerThanTheModelInputIsNotedOnlyWhenItWasEmbedded() throws Exception {
        indexSemantically();
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        int limit = tokenizer.identity().maxInputTokens() - tokenizer.identity().specialTokenCount();
        // One whitespace part of single-character tokens, so lexical search accepts it however many tokens it has.
        String fits = "a.".repeat(limit / 2);
        String cut = fits + "a";
        assertEquals(limit, tokenizer.count(fits));
        assertEquals(limit + 1, tokenizer.count(cut));

        assertEquals(List.of(), notices(fits, SearchMode.SEMANTIC));
        assertEquals(List.of(QueryNotice.Code.QUERY_CUT_TO_MODEL_INPUT), notices(cut, SearchMode.SEMANTIC));
        assertEquals(List.of(QueryNotice.Code.QUERY_CUT_TO_MODEL_INPUT), notices(cut, SearchMode.HYBRID));
        assertEquals(List.of(), notices(cut, SearchMode.LEXICAL));
        assertTrue(service.query(root, new SearchRequest(cut), QueryOptions.mode(SearchMode.SEMANTIC))
                .notices()
                .getFirst()
                .message()
                .contains(limit + " of the query's " + (limit + 1) + " tokens"));
    }

    @Test
    void everyModeKeepsTheRequestsCodePointLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchRequest("a".repeat(SearchRequest.MAX_QUERY_CODE_POINTS + 1)));
    }

    private List<QueryNotice.Code> notices(String text, SearchMode mode) throws QueryException {
        return service.query(root, new SearchRequest(text), QueryOptions.mode(mode)).notices().stream()
                .map(QueryNotice::code)
                .toList();
    }

    private void indexSemantically() throws Exception {
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
    }

    private static List<Long> ids(QueryResult result) {
        return result.hits().stream().map(SearchHit::chunkId).toList();
    }
}
