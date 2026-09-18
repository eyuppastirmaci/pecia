package dev.eyuppastirmaci.pecia.chunking.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void preservesFittingMarkdownAsOneOriginalSlice() {
        String text = " \t\n# Title\n\nintro\n\n- one\n- two\n\n```java\ncode\n```\n";
        List<Chunk> chunks = verify(text, 256, 32);

        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().content());
        assertThrows(UnsupportedOperationException.class, () -> chunks.clear());
    }

    @Test
    void returnsNoChunksForEmptyOrWhitespaceOnlyInput() {
        MarkdownChunker chunker = new MarkdownChunker(tokenizer, 32, 0);

        for (String text : List.of("", " \t\r\n", "\u00a0\u2003")) {
            assertEquals(List.of(), chunker.chunk(document(text)));
        }
    }

    @Test
    void splitsLargeSectionsAtWholeBlockBoundaries() {
        String first = "# Root\n\none two three\n\n";
        String list = "- four\n- five\n\n";
        String last = "six seven eight";
        List<Chunk> chunks = verify(first + list + last, tokenizer.countModelInput(first), 2);

        assertEquals(
                List.of(first, list, last), chunks.stream().map(Chunk::content).toList());
    }

    @Test
    void splitsOversizedListsAtItemBoundariesBeforeUsingTextFallback() {
        for (String list : List.of("- one\n- two\n- three\n- four\n", "1. one\n2. two\n3. three\n4. four\n")) {
            List<Chunk> chunks = verify(list, 6, 2);

            assertEquals(4, chunks.size());

            for (Chunk chunk : chunks) {
                assertEquals(1, chunk.content().lines().count());
            }
        }
    }

    @Test
    void preservesFittingNestedListsWhileSplittingTheirOversizedParent() {
        String nested = "  - child one\n  - child two\n\n";
        String text = "- " + "intro ".repeat(40) + "\n\n" + nested + "- " + "tail ".repeat(40) + "\n";
        List<Chunk> chunks = verify(text, 16, 3);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().equals(nested)));
    }

    @Test
    void preservesFittingFencesInsideAnOversizedListItem() {
        String code = "  ```java\n  int value = 1;\n  ```\n\n";
        String text = "- " + "intro ".repeat(60) + "\n\n" + code + "  " + "tail ".repeat(60);
        List<Chunk> chunks = verify(text, 24, 3);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().equals(code)));
    }

    @Test
    void preservesFittingFencesInsideAnOversizedQuote() {
        String code = "> ```\n> code\n> ```\n>\n";
        String text = "> " + "intro ".repeat(60) + "\n>\n" + code + "> " + "tail ".repeat(60);
        List<Chunk> chunks = verify(text, 24, 3);

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().equals(code)));
    }

    @Test
    void usesTextFallbackInsideAnOversizedListItem() {
        String text = "- " + "paymentValidation ".repeat(60) + "\n- end\n";
        List<Chunk> chunks = verify(text, 12, 2);

        assertTrue(chunks.size() > 2);
        assertEquals("- end\n", chunks.getLast().content());
    }

    @Test
    void splitsLongFencesWithoutSynthesizingOrDroppingFenceMarkers() {
        for (String fence : List.of("```", "~~~", "````")) {
            String text = "# Root\n\n" + fence + "java\n" + "int value = 1;\n".repeat(80) + fence + "\n";
            List<Chunk> chunks = verify(text, 24, 0);

            assertTrue(chunks.size() > 3);
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains(fence + "java")));
            assertTrue(chunks.getLast().content().endsWith(fence + "\n"));
        }
    }

    @Test
    void splitsIncompleteFencesWithoutExposingFakeHeadingsOrClosingThem() {
        String text = "~~~python\n" + "# still code\n- still code\n".repeat(50) + "print(1)";
        List<Chunk> chunks = verify(text, 18, 0);

        assertTrue(chunks.size() > 2);
        assertTrue(chunks.getLast().content().endsWith("print(1)"));
    }

    @Test
    void safelySplitsOversizedLinesWordsAndSupplementaryCharacters() {
        for (String content : List.of("paymentValidation", "世😀世😀世😀", "hello ".repeat(80))) {
            String text = "```\n" + content + "\n```";

            assertTrue(verify(text, 4, 0).size() > 1);
        }
    }

    @Test
    void maintainsExactGlobalOffsetsAndLineLocationsAcrossFallback() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String prefix = "😀 Önsöz" + newline + newline;
            String text = prefix
                    + "# İçerik"
                    + newline
                    + "```"
                    + newline
                    + ("cafe\u0301 ve içerik 😀" + newline).repeat(30)
                    + "```"
                    + newline
                    + "# Son";
            List<Chunk> chunks = verify(text, 16, 3);

            assertEquals(prefix, chunks.getFirst().content());
            assertEquals(text.lastIndexOf("# Son"), startOf(chunks.getLast()));
        }
    }

    @Test
    void appliesBestEffortOverlapOnlyWithinAnOversizedLeaf() {
        String prefix = "# First\n\n";
        String oversized = "one two three four five six seven eight ".repeat(12) + "\n\n";
        String suffix = "# Second\n\n- end\n";
        List<Chunk> chunks = verify(prefix + oversized + suffix, 12, 3);
        boolean hasOverlap = false;

        for (int index = 1; index < chunks.size(); index++) {
            hasOverlap |= startOf(chunks.get(index)) < endOf(chunks.get(index - 1));
        }

        assertTrue(hasOverlap);
        assertEquals(prefix.length(), startOf(chunks.get(1)));
        assertEquals(prefix.length() + oversized.length(), startOf(chunks.getLast()));
        assertEquals(suffix, chunks.getLast().content());
    }

    @Test
    void doesNotUseOverlapToSplitOrDuplicateFittingBlocks() {
        String text = "# First\n- one\n\n# Second\n- two\n\n# Third\n- three\n";
        List<Chunk> chunks = verify(text, 16, 10);
        int offset = 0;

        for (Chunk chunk : chunks) {
            assertEquals(offset, startOf(chunk));
            offset = endOf(chunk);
        }
    }

    @Test
    void reservesSpecialTokensAndRechecksNonMonotonicWordPieceCounts() {
        String full = "hello ".repeat(254);
        String oversized = "hello ".repeat(255);

        assertEquals(1, verify(full, 256, 0).size());
        assertEquals(2, verify(oversized, 256, 0).size());
        assertEquals(1, verify("a".repeat(101), 3, 0).size());
        verify("\u200b ".repeat(5000) + "hello ".repeat(300) + "\u200b ".repeat(5000), 256, 32);
    }

    @Test
    void preservesWhitespaceReferencesAndHtmlAroundOversizedBlocks() {
        String text =
                " \t\n[ref]: https://example.com\n\n<!-- hidden -->\n\n" + "- [label][ref]\n".repeat(50) + "\n \t\r\n";

        verify(text, 8, 2);
    }

    @Test
    void preservesEmptyItemsNestedQuotesAndDefinitionsInsideContainers() {
        for (String text : List.of(
                "-\n-\n-\n",
                "> - item\n>   - nested\n>\n> tail\n",
                "- [ref]: https://example.com\n\n  text\n\n- next\n",
                "- > quote\n  >\n  > body\n\n- end\n",
                "> ".repeat(40) + "text\n")) {
            verify(text, 4, 1);
        }
    }

    @Test
    void attachesNestedPathsAndResetsSiblingsAncestorsAndSkippedLevels() {
        String text = "Preamble\n\n# Installation\nintro\n## Windows\n### Terminal\n"
                + "## Linux\n##### Advanced\n# Usage\nbody";
        List<Chunk> chunks = verify(text, 256, 0);

        assertEquals(
                List.of(
                        List.of(),
                        List.of("Installation"),
                        List.of("Installation", "Windows"),
                        List.of("Installation", "Windows", "Terminal"),
                        List.of("Installation", "Linux"),
                        List.of("Installation", "Linux", "Advanced"),
                        List.of("Usage")),
                headingPaths(chunks));
    }

    @Test
    void attachesTheFirstHeadingDespiteLeadingWhitespaceAndIndentation() {
        String text = " \t\r\n\r\n   # İçerik\r\nbody";
        List<Chunk> chunks = verify(text, 256, 0);

        assertEquals(1, chunks.size());
        assertEquals(text, chunks.getFirst().content());
        assertEquals(List.of("İçerik"), chunks.getFirst().metadata().headingPath());
    }

    @Test
    void carriesHeadingContextThroughFallbackOverlapAndNestedBlockSplits() {
        String first =
                "# Root\n## Windows\n" + "one two three four ".repeat(30) + "\n\n" + "- item\n".repeat(40) + "\n";
        String second = "## Linux\n```\n" + "code\n".repeat(40) + "```\n";
        List<Chunk> chunks = verify(first + second, 12, 3);

        for (Chunk chunk : chunks) {
            List<String> expected = startOf(chunk) == 0
                    ? List.of("Root")
                    : startOf(chunk) < first.length() ? List.of("Root", "Windows") : List.of("Root", "Linux");
            assertEquals(expected, chunk.metadata().headingPath());
        }
    }

    @Test
    void preservesTheFullPathWhenTheHeadingItselfRequiresSplitting() {
        String title = "installation details ".repeat(30).strip();
        String text = " \n# " + title + "\nbody";
        List<Chunk> chunks = verify(text, 8, 2);

        assertTrue(chunks.size() > 2);
        assertTrue(tokenizer.countModelInput(title) > 8);

        for (Chunk chunk : chunks) {
            assertEquals(List.of(title), chunk.metadata().headingPath());
        }

        assertEquals("body", chunks.getLast().content());
    }

    @Test
    void ignoresFakeHeadingsInContainersAndClosedOrIncompleteFences() {
        for (String body : List.of(
                "```\n# Fake\n" + "code\n".repeat(30) + "```\n",
                "~~~\n## Fake\n" + "code\n".repeat(30),
                "- ## Item\n  - ### Nested\n\n> # Quoted\n> " + "text ".repeat(30),
                "<!--\n# Hidden\n-->\n\n<div>\n## Hidden\n</div>\n")) {
            List<Chunk> chunks = verify("# Root\n\n" + body, 12, 2);

            for (Chunk chunk : chunks) {
                assertEquals(List.of("Root"), chunk.metadata().headingPath());
            }
        }
    }

    @Test
    void attachesSetextPathsWithOriginalUnicodeAndLineLocations() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String root = "İçerik 😀" + newline + "====" + newline + newline;
            String child = "cafe\u0301" + newline + "ayrıntı" + newline + "---" + newline + "body";
            List<Chunk> chunks = verify(root + child, 256, 0);

            assertEquals(
                    List.of(List.of("İçerik 😀"), List.of("İçerik 😀", "cafe\u0301 ayrıntı")), headingPaths(chunks));
            assertEquals(new LineRange(1, 3), chunks.getFirst().sourceLocation());
            assertEquals(new LineRange(4, 7), chunks.getLast().sourceLocation());
        }
    }

    @Test
    void storesReadableTitlesWithoutChangingSourceText() {
        String text = "# **Install** [Windows](https://example.com) `CLI` &amp; <b>tools</b>\nbody";
        List<Chunk> chunks = verify(text, 256, 0);

        assertEquals(text, chunks.getFirst().content());
        assertEquals(
                List.of("Install Windows CLI & tools"),
                chunks.getFirst().metadata().headingPath());
    }

    @Test
    void emptyHeadingsClearDescendantsWithoutBlankMetadataEntries() {
        String text = "# Root\n## Old\n##\n### New\n#\nbody";
        List<Chunk> chunks = verify(text, 256, 0);

        assertEquals(
                List.of(List.of("Root"), List.of("Root", "Old"), List.of("Root"), List.of("Root", "New"), List.of()),
                headingPaths(chunks));
    }

    @Test
    void leavesHeadingFreeDocumentsAndPreambleWithoutContext() {
        for (String text : List.of("intro\n\n- item\n", "```\n# Fake\n```\n", "#not-a-heading\n")) {
            for (Chunk chunk : verify(text, 8, 2)) {
                assertEquals(List.of(), chunk.metadata().headingPath());
            }
        }

        assertEquals(
                List.of(),
                verify("Preamble\n\n# Root\nbody", 256, 0).getFirst().metadata().headingPath());
    }

    @Test
    void repeatedHeadingsKeepDistinctLocationsAndDoNotLeakAcrossDocuments() {
        MarkdownChunker chunker = new MarkdownChunker(tokenizer, 256, 0);
        String text = "# Same\n## Child\n# Same\n## Child\n";
        List<Chunk> first = chunker.chunk(document(text));

        assertEquals(
                List.of(List.of("Same"), List.of("Same", "Child"), List.of("Same"), List.of("Same", "Child")),
                headingPaths(first));
        assertTrue(startOf(first.get(3)) > startOf(first.get(1)));
        assertEquals(List.of(List.of("Other")), headingPaths(chunker.chunk(document("# Other\n"))));
        assertEquals(List.of(List.of()), headingPaths(chunker.chunk(document("No heading"))));
        assertEquals(first, chunker.chunk(document(text)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.getFirst().metadata().headingPath().add("changed"));
    }

    @Test
    void rejectsInvalidBudgetsAndNullInputs() {
        assertThrows(NullPointerException.class, () -> new MarkdownChunker(null, 256, 32));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownChunker(tokenizer, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownChunker(tokenizer, 257, 0));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownChunker(tokenizer, 8, -1));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownChunker(tokenizer, 8, 6));
        assertThrows(NullPointerException.class, () -> new MarkdownChunker(tokenizer, 256, 32).chunk(null));
    }

    @Test
    void maintainsSourceCoverageAcrossSeededMixedOversizedBlocks() {
        Random random = new Random(93);
        String[] blocks = {
            "# Heading\n\n",
            "## Child\n\n",
            "word ".repeat(40) + "\n\n",
            "- one\n  - nested\n- two\n\n",
            "- item\n".repeat(50) + "\n",
            "```java\n" + "int x = 1;\n".repeat(30) + "```\n\n",
            "> quoted\n>\n> - item\n\n",
            "[ref]: https://example.com\n\n",
            "😀 İçerik\r\n\r\n",
            "世😀".repeat(20) + "\n\n"
        };

        for (int example = 0; example < 60; example++) {
            StringBuilder text = new StringBuilder();

            for (int index = 0; index < 12; index++) {
                text.append(blocks[random.nextInt(blocks.length)]);
            }

            int limit = 4 + random.nextInt(28);
            verify(text.toString(), limit, random.nextInt(limit - 2));
        }
    }

    /**
     * Checks global coverage, exact source slices, Unicode boundaries, overlap, and line ranges independently of
     * chunking helpers.
     */
    private List<Chunk> verify(String text, int limit, int overlap) {
        Document document = document(text);
        DocumentChunker chunker = new MarkdownChunker(tokenizer, limit, overlap);
        List<Chunk> chunks = chunker.chunk(document);
        assertEquals(chunks, chunker.chunk(document));
        StringBuilder reconstructed = new StringBuilder();
        List<MarkdownSection> sections = new MarkdownSectionParser().parse(text);
        int coveredEnd = 0;
        int previousStart = -1;
        int sectionIndex = 0;

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = startOf(chunk);
            int end = endOf(chunk);
            assertTrue(start > previousStart);
            assertTrue(start <= coveredEnd);
            assertTrue(end > coveredEnd);
            assertEquals(text.substring(start, end), chunk.content());
            assertFalse(chunk.content().isBlank());
            assertEquals(index, chunk.index());
            assertEquals(document.sourcePath(), chunk.sourcePath());
            assertEquals(DocumentType.MARKDOWN, chunk.documentType());
            assertTrue(chunk.metadata().headingPath().stream().noneMatch(String::isBlank));
            int contextOffset = start;

            while (contextOffset < text.length()
                    && (Character.isWhitespace(text.codePointAt(contextOffset))
                            || Character.isSpaceChar(text.codePointAt(contextOffset)))) {
                contextOffset += Character.charCount(text.codePointAt(contextOffset));
            }

            while (sectionIndex + 1 < sections.size()
                    && sections.get(sectionIndex).endOffset() <= contextOffset) {
                sectionIndex++;
            }

            assertEquals(
                    sections.get(sectionIndex).headingPath(), chunk.metadata().headingPath());
            assertTrue(tokenizer.countModelInput(chunk.content()) <= limit);
            assertTrue(tokenizer.count(text.substring(start, coveredEnd)) <= overlap);
            assertEquals(new LineRange(lineAt(text, start), lineAt(text, end - 1)), chunk.sourceLocation());
            assertSafeBoundary(text, start);
            assertSafeBoundary(text, end);
            reconstructed.append(text, coveredEnd, end);
            coveredEnd = end;
            previousStart = start;
        }

        assertEquals(text.length(), coveredEnd);
        assertEquals(text, reconstructed.toString());

        return chunks;
    }

    private static int startOf(Chunk chunk) {
        return Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
    }

    private static List<List<String>> headingPaths(List<Chunk> chunks) {
        return chunks.stream().map(chunk -> chunk.metadata().headingPath()).toList();
    }

    private static int endOf(Chunk chunk) {
        return Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
    }

    private static void assertSafeBoundary(String text, int offset) {
        if (offset > 0 && offset < text.length()) {
            assertFalse(Character.isHighSurrogate(text.charAt(offset - 1))
                    && Character.isLowSurrogate(text.charAt(offset)));
            assertFalse(text.charAt(offset - 1) == '\r' && text.charAt(offset) == '\n');
        }
    }

    private static int lineAt(String text, int offset) {
        int line = 1;

        for (int index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n'
                    || text.charAt(index) == '\r' && (index + 1 == text.length() || text.charAt(index + 1) != '\n')) {
                line++;
            }
        }

        return line;
    }

    private static Document document(String text) {
        return new Document(
                Path.of("docs/example.md"),
                DocumentType.MARKDOWN,
                text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
