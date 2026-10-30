package dev.eyuppastirmaci.pecia.chunking.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tiny Markdown groups join a neighbour under the same parent heading, within the token budget. */
class MarkdownTinyGroupTest {

    // Long enough to stay a chunk of its own: well over the 24-token threshold.
    private static final String PARAGRAPH = "The pattern lets a client choose one of several interchangeable algorithms"
            + " at run time, keeping each algorithm in its own class so that new ones can be added without changing"
            + " the code that uses them.\n";

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void aTinySectionJoinsItsNextSiblingWithTheSharedParentPath() {
        String text = "# Strategy\n\n## Also known as\n\n* Policy\n\n## Intent\n\n" + PARAGRAPH;

        List<Chunk> chunks = chunk(text, 256);

        // The tiny title section joins "Also known as", which is still tiny and joins "Intent".
        assertEquals(1, chunks.size());
        assertEquals(List.of("Strategy"), chunks.getFirst().metadata().headingPath());
        assertEquals(text, chunks.getFirst().content());
        assertEquals(new LineRange(1, 9), chunks.getFirst().sourceLocation());
        assertEquals(2, unmerged(text, 256).size() - 1, "Without joining, the tiny sections stay apart");
    }

    @Test
    void joinedSiblingsCarryTheirParentsPathRatherThanTheFirstSiblings() {
        String text = "# Strategy\n\n" + PARAGRAPH + "\n## Also known as\n\n* Policy\n\n## Intent\n\n" + PARAGRAPH;

        List<Chunk> chunks = chunk(text, 256);

        assertEquals(2, chunks.size());
        assertEquals(List.of("Strategy"), chunks.get(1).metadata().headingPath());
        assertTrue(
                chunks.get(1).content().startsWith("## Also known as"),
                chunks.get(1).content());
    }

    @Test
    void aTinyLastSectionJoinsThePreviousOne() {
        String text = "# Guide\n\n## Intent\n\n" + PARAGRAPH + "\n## Credits\n\nThanks.\n";

        List<Chunk> chunks = chunk(text, 256);

        assertEquals(1, chunks.size());
        assertEquals(List.of("Guide"), chunks.getFirst().metadata().headingPath());
        assertEquals(text, chunks.getFirst().content());
    }

    @Test
    void groupsUnderDifferentParentsStayApart() {
        // "Details" is tiny, but its next group belongs to another top-level section and its previous group is too
        // large to take it within the budget.
        String text = "# First\n\n## Usage\n\n" + PARAGRAPH + PARAGRAPH + "\n### Details\n\nShort.\n\n# Second\n\n"
                + PARAGRAPH;
        int budget = tokenizer.countModelInput("## Usage\n\n" + PARAGRAPH + PARAGRAPH) + 4;

        List<Chunk> chunks = chunk(text, budget);

        assertTrue(chunks.stream()
                .anyMatch(chunk -> chunk.metadata().headingPath().equals(List.of("First", "Usage", "Details"))));
        assertTrue(chunks.stream()
                .noneMatch(chunk ->
                        chunk.content().contains("Short.") && chunk.content().contains("# Second")));
    }

    @Test
    void eachTopLevelSectionsChunkCarriesItsOwnHeading() {
        String text = "# A\n\n" + PARAGRAPH + "\n# B\n\n" + PARAGRAPH;

        List<Chunk> chunks = chunk(text, 256);

        // The second group starts exactly where section A ends, so it already belongs to section B.
        assertEquals(2, chunks.size());
        assertEquals(List.of("A"), chunks.get(0).metadata().headingPath());
        assertEquals(List.of("B"), chunks.get(1).metadata().headingPath());
    }

    @Test
    void aTinySectionThatCannotJoinTheNextOneJoinsThePreviousOneMidDocument() {
        String text = "# Guide\n\n## Intent\n\n" + PARAGRAPH + "\n## Credits\n\nThanks.\n\n# Next\n\n" + PARAGRAPH;

        List<Chunk> chunks = chunk(text, 256);

        // "Credits" cannot join "Next", another top-level section, so it joins its sibling before "Next" is added.
        assertEquals(2, chunks.size());
        assertEquals(List.of("Guide"), chunks.get(0).metadata().headingPath());
        assertTrue(
                chunks.get(0).content().endsWith("Thanks.\n\n"), chunks.get(0).content());
        assertEquals(List.of("Next"), chunks.get(1).metadata().headingPath());
    }

    @Test
    void aGroupOfExactlyTheThresholdIsNotTiny() {
        String tail = "# B\n\n" + wordsFilling(MarkdownGroupMerger.MIN_CONTENT_TOKENS, "# B\n\n");
        String text = "# A\n\n" + PARAGRAPH + "\n" + tail;

        List<Chunk> chunks = chunk(text, 256);

        assertEquals(MarkdownGroupMerger.MIN_CONTENT_TOKENS, tokenizer.count(tail));
        assertEquals(2, chunks.size(), "A group of exactly 24 tokens stays on its own");
    }

    @Test
    void aJoinThatFillsTheBudgetExactlyIsAllowed() {
        String text = "# A\n\n" + PARAGRAPH + "\n# B\n\nTiny.\n";

        List<Chunk> chunks = chunk(text, tokenizer.countModelInput(text));

        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().content());
    }

    @Test
    void itemsOfASplitListJoinUntilTheyAreNoLongerTiny() {
        StringBuilder list = new StringBuilder("# Features\n\n");

        for (int item = 1; item <= 60; item++) {
            list.append("* Feature number ").append(item).append(" is supported.\n");
        }

        String text = list.toString();
        List<Chunk> merged = chunk(text, 256);
        List<Chunk> unmerged = unmerged(text, 256);

        assertTrue(merged.size() < unmerged.size(), merged.size() + " >= " + unmerged.size());
        assertEquals(text, String.join("", merged.stream().map(Chunk::content).toList()));

        for (Chunk chunk : merged) {
            assertTrue(tokenizer.countModelInput(chunk.content()) <= 256);
            assertEquals(List.of("Features"), chunk.metadata().headingPath());
        }
    }

    @Test
    void joiningNeverExceedsTheBudget() {
        String text = "# A\n\n" + PARAGRAPH + "\n# B\n\nTiny.\n";
        int budget = tokenizer.countModelInput("# A\n\n" + PARAGRAPH + "\n");

        List<Chunk> chunks = chunk(text, budget);

        assertEquals(2, chunks.size());

        for (Chunk chunk : chunks) {
            assertTrue(tokenizer.countModelInput(chunk.content()) <= budget);
        }
    }

    @Test
    void smallBudgetsJoinNothingBecauseTheThresholdShrinksWithThem() {
        String text = "# Root\nintro\n## Child\n- one\n- two";

        // An eight-token budget leaves six content tokens, whose eighth rounds down to zero.
        assertEquals(unmerged(text, 8), chunk(text, 8));
        assertEquals(24, MarkdownGroupMerger.MIN_CONTENT_TOKENS);
    }

    /** Returns words that bring {@code prefix} plus them, ending in a newline, to exactly {@code tokens} tokens. */
    private String wordsFilling(int tokens, String prefix) {
        StringBuilder words = new StringBuilder();

        while (tokenizer.count(prefix + words + "word\n") <= tokens) {
            words.append("word ");
        }

        String filled = words.toString().strip() + "\n";
        assertEquals(tokens, tokenizer.count(prefix + filled), "Each word adds one token");
        return filled;
    }

    private List<Chunk> chunk(String text, int maxTokens) {
        return new MarkdownChunker(tokenizer, maxTokens, 0).chunk(document(text));
    }

    private List<Chunk> unmerged(String text, int maxTokens) {
        return new MarkdownChunker(tokenizer, maxTokens, 0, false).chunk(document(text));
    }

    private static Document document(String text) {
        return new Document(
                Path.of("guide.md"),
                DocumentType.MARKDOWN,
                text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
