package dev.eyuppastirmaci.pecia.chunking;

import static org.junit.jupiter.api.Assertions.assertNull;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.testing.ChunkInvariants;
import dev.eyuppastirmaci.pecia.testing.ChunkingArbitraries;
import dev.eyuppastirmaci.pecia.testing.ChunkingArbitraries.Budget;
import dev.eyuppastirmaci.pecia.testing.PropertySeeds;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Generated inputs for every chunking strategy must satisfy the shared coverage invariant: exact source slices and
 * line ranges, ordered coverage that reconstructs the source once overlap is removed, token and overlap bounds, and
 * safe boundaries. These properties complement the golden corpus and the example-based chunker tests.
 */
class ChunkingPropertiesTest {

    private static final MiniLmTokenizer TOKENIZER = MiniLmTokenizer.bundled();
    // Short documents are cheap, so each strategy runs many tries; long documents trade tries for size.
    private static final int SHORT_DOCUMENT_TRIES = 1000;
    private static final int LONG_DOCUMENT_TRIES = 100;

    @Property(seed = PropertySeeds.FIXED, tries = SHORT_DOCUMENT_TRIES)
    void plainTextChunksCoverTheSourceWithinTheirBudgets(
            @ForAll("plainText") String text, @ForAll("budgets") Budget budget) {
        assertInvariant(DocumentType.PLAIN_TEXT, text, budget);
    }

    @Property(seed = PropertySeeds.FIXED, tries = SHORT_DOCUMENT_TRIES)
    void markdownChunksCoverTheSourceWithinTheirBudgets(
            @ForAll("markdown") String text, @ForAll("budgets") Budget budget) {
        assertInvariant(DocumentType.MARKDOWN, text, budget);
    }

    @Property(seed = PropertySeeds.FIXED, tries = SHORT_DOCUMENT_TRIES)
    void sourceCodeChunksCoverTheSourceWithinTheirBudgets(
            @ForAll("sourceCode") String text, @ForAll("budgets") Budget budget) {
        assertInvariant(DocumentType.SOURCE_CODE, text, budget);
    }

    @Property(seed = PropertySeeds.FIXED, tries = LONG_DOCUMENT_TRIES)
    void longDocumentsSplitIntoManyChunksWithinLargeBudgets(
            @ForAll("longDocuments") Document document, @ForAll("largeBudgets") Budget budget) {
        List<Chunk> chunks = DocumentChunkerFactory.create(TOKENIZER, budget.maxTokens(), budget.overlapTokens())
                .getChunker(document.type())
                .chunk(document);

        assertNull(ChunkInvariants.violation(document, chunks, TOKENIZER, budget.maxTokens(), budget.overlapTokens()));
        Statistics.label("at least five chunks").collect(chunks.size() >= 5);
        Statistics.label("at least five chunks")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 80));
    }

    @Provide
    Arbitrary<String> plainText() {
        return ChunkingArbitraries.plainText();
    }

    @Provide
    Arbitrary<String> markdown() {
        return ChunkingArbitraries.markdown();
    }

    @Provide
    Arbitrary<String> sourceCode() {
        return ChunkingArbitraries.sourceCode();
    }

    @Provide
    Arbitrary<Budget> budgets() {
        return ChunkingArbitraries.budgets();
    }

    @Provide
    Arbitrary<Document> longDocuments() {
        return ChunkingArbitraries.longDocuments();
    }

    @Provide
    Arbitrary<Budget> largeBudgets() {
        return ChunkingArbitraries.largeBudgets();
    }

    private static void assertInvariant(DocumentType type, String text, Budget budget) {
        Document document = ChunkingArbitraries.document(type, text);
        DocumentChunker chunker = DocumentChunkerFactory.create(TOKENIZER, budget.maxTokens(), budget.overlapTokens())
                .getChunker(type);
        List<Chunk> chunks = chunker.chunk(document);

        assertNull(ChunkInvariants.violation(document, chunks, TOKENIZER, budget.maxTokens(), budget.overlapTokens()));

        // Require splitting and real overlap, so the invariant is not satisfied only by single-chunk documents.
        Statistics.label("several chunks").collect(chunks.size() > 1);
        Statistics.label("overlapping chunks").collect(overlaps(chunks));
        Statistics.label("several chunks")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 30));
        Statistics.label("overlapping chunks")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 10));
    }

    private static boolean overlaps(List<Chunk> chunks) {
        for (int index = 1; index < chunks.size(); index++) {
            int start =
                    Integer.parseInt(chunks.get(index).metadata().attributes().get("startOffset"));
            int previousEnd = Integer.parseInt(
                    chunks.get(index - 1).metadata().attributes().get("endOffset"));

            if (start < previousEnd) {
                return true;
            }
        }

        return false;
    }
}
