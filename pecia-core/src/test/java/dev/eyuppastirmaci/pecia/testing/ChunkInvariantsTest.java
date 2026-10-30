package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Negative controls proving that the shared chunk invariant reports each kind of violation. */
class ChunkInvariantsTest {

    private static final MiniLmTokenizer TOKENIZER = MiniLmTokenizer.bundled();
    private static final String TEXT =
            "Payment validation for İstanbul 😀.\r\nÖdeme adımı incelenir.\n\nA retry follows every failed attempt.\r"
                    + "Another paragraph closes the sample text with several more words.\n";
    private static final int MAX_TOKENS = 12;
    private static final int OVERLAP_TOKENS = 3;

    private final Document document = ChunkingArbitraries.document(DocumentType.PLAIN_TEXT, TEXT);
    private final List<Chunk> chunks = DocumentChunkerFactory.create(TOKENIZER, MAX_TOKENS, OVERLAP_TOKENS)
            .getChunker(DocumentType.PLAIN_TEXT)
            .chunk(document);

    @Test
    void acceptsRealChunkerOutputWithSeveralOverlappingChunks() {
        assertTrue(chunks.size() > 2, "The sample must split: " + chunks.size());
        assertNull(ChunkInvariants.violation(document, chunks, TOKENIZER, MAX_TOKENS, OVERLAP_TOKENS));
    }

    @Test
    void reportsMissingTailCoverage() {
        assertViolation("Chunks end at", chunks.subList(0, chunks.size() - 1), MAX_TOKENS, OVERLAP_TOKENS);
    }

    @Test
    void reportsSkippedOrReorderedChunks() {
        List<Chunk> reordered = new ArrayList<>(chunks);
        reordered.set(1, chunks.get(2));
        reordered.set(2, chunks.get(1));

        assertViolation("has index", reordered, MAX_TOKENS, OVERLAP_TOKENS);
    }

    @Test
    void reportsChunksAboveTheTokenLimitOrOverlapBudget() {
        assertViolation("model input tokens", chunks, MAX_TOKENS - 3, OVERLAP_TOKENS);
        assertViolation("overlaps", chunks, MAX_TOKENS, 0);
    }

    @Test
    void reportsContentThatNoLongerMatchesTheSource() {
        Document changed = ChunkingArbitraries.document(DocumentType.PLAIN_TEXT, TEXT.replace("Payment", "Pazment"));

        assertTrue(ChunkInvariants.violation(changed, chunks, TOKENIZER, MAX_TOKENS, OVERLAP_TOKENS)
                .contains("differs from its source slice"));
    }

    @Test
    void reportsChunksForAWhitespaceOnlySource() {
        Document blank = ChunkingArbitraries.document(DocumentType.PLAIN_TEXT, "  　\r\n\t");

        assertTrue(ChunkInvariants.violation(blank, chunks, TOKENIZER, MAX_TOKENS, OVERLAP_TOKENS)
                .startsWith("A whitespace-only source produced"));
        assertNull(ChunkInvariants.violation(blank, List.of(), TOKENIZER, MAX_TOKENS, OVERLAP_TOKENS));
    }

    @Test
    void allowsAMarkdownGapOnlyWhenItHoldsNothingButMarkupOutsideCode() {
        String anchor = "<!-- old -->\n\n> <a id=\"old\"></a>\n\n";
        String intro = "# Intro\n\n" + "First part of the guide, long enough to stand alone. ".repeat(3) + "\n\n";
        String next = "# Next\n\n" + "Second part of the guide, long enough as well. ".repeat(3) + "\n\n";
        String text = intro + anchor + next + "# Last\n\n" + "Third part of the guide, long enough too. ".repeat(3);
        // A budget that the intro fills, so the anchor can join neither neighbour and is left out.
        int budget = TOKENIZER.countModelInput(intro) + 2;
        Document markdown = ChunkingArbitraries.document(DocumentType.MARKDOWN, text);
        List<Chunk> chunks = DocumentChunkerFactory.create(TOKENIZER, budget, 0)
                .getChunker(DocumentType.MARKDOWN)
                .chunk(markdown);
        Chunk first = chunks.getFirst();

        assertEquals(3, chunks.size(), chunks.toString());
        assertNull(ChunkInvariants.violation(markdown, chunks, TOKENIZER, budget, 0));
        assertTrue(ChunkInvariants.onlyMarkup(anchor));
        assertTrue(ChunkInvariants.onlyMarkup("<!-- generated -->\n<br/>\n"));
        assertFalse(ChunkInvariants.onlyMarkup("<a id=\"x\"></a> Text\n"));
        assertFalse(ChunkInvariants.onlyMarkup("---\n"));
        assertFalse(ChunkInvariants.onlyMarkup("    <div></div>\n"), "Indented code");
        assertFalse(ChunkInvariants.onlyMarkup("```\n<div></div>\n```\n"), "Fenced code");
        assertFalse(ChunkInvariants.onlyMarkup("<https://example.com>\n"), "An autolink");
        Chunk last = chunks.get(2);
        // The last chunk renumbered to follow the first, so only the gap left by the missing middle one is wrong.
        Chunk afterAGap = new Chunk(
                last.sourcePath(), last.documentType(), 1, last.content(), last.sourceLocation(), last.metadata());
        assertNotNull(
                ChunkInvariants.violation(markdown, List.of(first, afterAGap), TOKENIZER, budget, 0),
                "Text between chunks must still be covered");
    }

    @Test
    void neverAllowsGapsOutsideMarkdown() {
        List<Chunk> withoutSecond = new ArrayList<>(chunks);
        withoutSecond.remove(1);

        assertViolation("has index", withoutSecond, MAX_TOKENS, OVERLAP_TOKENS);
    }

    private void assertViolation(String expected, List<Chunk> candidate, int maxTokens, int overlapTokens) {
        String violation = ChunkInvariants.violation(document, candidate, TOKENIZER, maxTokens, overlapTokens);

        assertTrue(
                violation != null && violation.contains(expected), "Expected '" + expected + "' but got " + violation);
    }
}
