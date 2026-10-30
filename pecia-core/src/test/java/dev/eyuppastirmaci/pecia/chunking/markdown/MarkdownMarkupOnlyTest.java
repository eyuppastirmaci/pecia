package dev.eyuppastirmaci.pecia.chunking.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A Markdown group with no visible text, such as an anchor kept for old links, never becomes a chunk of its own: it
 * would be found only through its headings and show an empty result.
 */
class MarkdownMarkupOnlyTest {

    private static final String PARAGRAPH = "The pattern lets a client choose one of several interchangeable algorithms"
            + " at run time, keeping each algorithm in its own class so that new ones can be added without changing"
            + " the code that uses them.\n";
    private static final Pattern VISIBLE = Pattern.compile("[\\p{L}\\p{N}]");

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void anAnchorBetweenTwoFullSectionsBecomesNoChunk() {
        // As in the Rust book: old headings kept as anchors between sections that each fill the budget.
        List<Chunk> chunks = betweenFullSections(
                "<!-- Old headings. Do not remove or links may break. -->\n\n<a id=\"old-heading\"></a>\n\n");

        assertEquals(2, chunks.size(), contents(chunks));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("old-heading")), contents(chunks));
        assertEveryChunkHasVisibleText(chunks);
    }

    @Test
    void aBlockQuoteHoldingOnlyACommentBecomesNoChunk() {
        List<Chunk> chunks = betweenFullSections("> <!-- CAN'T EXTRACT, SEE THE ISSUE -->\n>\n\n");

        assertEquals(2, chunks.size(), contents(chunks));
        assertEveryChunkHasVisibleText(chunks);
    }

    @Test
    void punctuationIsNotMarkupSoAThematicBreakAloneStays() {
        // Punctuation can be content, such as the closing brace of a split code block, so only HTML markup is left out.
        List<Chunk> chunks = betweenFullSections("---\n\n");

        assertEquals(3, chunks.size(), contents(chunks));
    }

    @Test
    void aDocumentOfOnlyMarkupHasNoChunks() {
        assertEquals(List.of(), chunk("<!-- generated -->\n<a id=\"top\"></a>\n", 256));
    }

    @Test
    void aTinyAnchorThatFitsBesideItsNeighbourStillJoinsIt() {
        String text = "# Guide\n\n<a id=\"intro\"></a>\n\n## Intent\n\n" + PARAGRAPH;

        List<Chunk> chunks = chunk(text, 256);

        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().content());
    }

    @Test
    void lettersOfAnyScriptAndDigitsAreVisibleText() {
        for (String visible : List.of("漢字\n\n", "<a id=\"answer\"></a> 42\n\n", "İstanbul\n\n")) {
            List<Chunk> chunks = betweenFullSections(visible);

            assertEquals(3, chunks.size(), visible + ": " + contents(chunks));
        }
    }

    @Test
    void aMarkdownImageKeepsItsAltTextAsVisibleText() {
        List<Chunk> chunks = betweenFullSections("![Architecture diagram](diagram.png)\n\n");

        assertEquals(3, chunks.size(), contents(chunks));
        assertTrue(chunks.get(1).content().contains("Architecture diagram"));
    }

    @Test
    void markupInsideAChunkWithTextIsKeptExactly() {
        String text = "# Shutdown\n\nPress <kbd>ctrl</kbd>-<kbd>C</kbd> to stop. <!-- reviewed -->\n";

        List<Chunk> chunks = chunk(text, 256);

        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().content());
    }

    /**
     * Chunks a document whose two sections each fill the budget, with {@code middle} between them, so the middle group
     * cannot join either neighbour.
     */
    private List<Chunk> betweenFullSections(String middle) {
        String first = "## First\n\n" + PARAGRAPH + PARAGRAPH + "\n";
        String second = "## Second\n\n" + PARAGRAPH + PARAGRAPH;
        int budget = tokenizer.countModelInput(first) + 2;

        return chunk("# Guide\n\n" + first + middle + second, budget);
    }

    private void assertEveryChunkHasVisibleText(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            String withoutMarkup =
                    chunk.content().replaceAll("(?s)<!--.*?-->", "").replaceAll("</?[A-Za-z][^>]*>", "");
            assertTrue(VISIBLE.matcher(withoutMarkup).find(), "No visible text: " + chunk.content());
        }
    }

    private List<Chunk> chunk(String text, int maxTokens) {
        return new MarkdownChunker(tokenizer, maxTokens, 0).chunk(document(text));
    }

    private static String contents(List<Chunk> chunks) {
        return chunks.stream().map(Chunk::content).toList().toString();
    }

    private static Document document(String text) {
        return new Document(
                Path.of("guide.md"),
                DocumentType.MARKDOWN,
                text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
