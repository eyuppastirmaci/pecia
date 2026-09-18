package dev.eyuppastirmaci.pecia.chunking.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceCodeChunkerTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void preservesFittingSourceAsOneImmutableChunk() {
        Document document = document(" \tclass Example {\r\n    int value = 1;\r\n}\r\n");
        DocumentChunker chunker = new SourceCodeChunker(tokenizer, 64);
        List<Chunk> chunks = chunker.chunk(document);

        assertEquals(1, chunks.size());
        assertEquals(document.content(), chunks.getFirst().content());
        assertEquals(new LineRange(1, 3), chunks.getFirst().sourceLocation());
        assertThrows(UnsupportedOperationException.class, () -> chunks.add(chunks.getFirst()));
        verify(document, chunks, tokenizer, 64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\r\n\n", "\u00a0\u2003"})
    void returnsNoChunksForEmptyOrWhitespaceOnlySource(String text) {
        assertEquals(List.of(), new SourceCodeChunker(tokenizer, 32).chunk(document(text)));
    }

    @Test
    void prefersBlankLinesOverLaterDedentsAndLineEnds() {
        Document document = document("first\n\nsecond\n    nested\nend\nlast");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6).chunk(document);

        assertEquals("first\n\n", chunks.getFirst().content());
        verify(document, chunks, tokenizer, 6);
    }

    @Test
    void prefersDedentsOverLaterLineEnds() {
        Document document = document("first\n    nested\nend\nlast\nextra");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6).chunk(document);

        assertEquals("first\n    nested\n", chunks.getFirst().content());
        verify(document, chunks, tokenizer, 6);
    }

    @Test
    void usesTheLatestFittingLineEndWithoutSentenceSplitting() {
        Document document = document("one!\ntwo?\nthree.\nfour;");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6).chunk(document);

        assertEquals("one!\ntwo?\n", chunks.getFirst().content());
        verify(document, chunks, tokenizer, 6);
    }

    @Test
    void keepsPunctuationInsideCommentsOnTheSamePhysicalLine() {
        String first = "// Hello! World? Still here.\n";
        String second = "// Another sentence. More words.\n";
        Document document = document(first + second);
        int limit = Math.max(tokenizer.countModelInput(first), tokenizer.countModelInput(second));
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, limit).chunk(document);

        assertEquals(List.of(first, second), chunks.stream().map(Chunk::content).toList());
        verify(document, chunks, tokenizer, limit);
    }

    @Test
    void keepsMarkdownLikeSourceRawWithoutHeadingMetadata() {
        Document document =
                document("# shell comment\nconst node = <Panel title=\"# heading\">{value}</Panel>;\n```\n");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 64).chunk(document);

        assertEquals(1, chunks.size());
        verify(document, chunks, tokenizer, 64);
    }

    @Test
    void reservesSpecialTokensAndAcceptsAnExactlyFullInput() {
        Document document = document("one\ntwo");
        List<Chunk> exact = new SourceCodeChunker(tokenizer, 4).chunk(document);
        List<Chunk> smaller = new SourceCodeChunker(tokenizer, 3).chunk(document);

        assertEquals(1, exact.size());
        assertEquals(4, tokenizer.countModelInput(exact.getFirst().content()));
        assertEquals(
                List.of("one\n", "two"), smaller.stream().map(Chunk::content).toList());
        verify(document, exact, tokenizer, 4);
        verify(document, smaller, tokenizer, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void preservesWhitespaceUnicodeAndPhysicalLineLocations(String newline) {
        Document document = document(newline
                + " \t"
                + newline
                + "😀 İ ç"
                + newline
                + "\tsecond"
                + newline
                + "  third"
                + newline
                + "\u00a0\u2003"
                + newline);
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6).chunk(document);

        assertTrue(chunks.size() > 1);
        verify(document, chunks, tokenizer, 6);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Main.java",
                "main.py",
                "main.go",
                "view.jsx",
                "view.tsx",
                "run.sh",
                "query.sql",
                "style.css",
                "Dockerfile",
                "Makefile"
            })
    void usesTheSameStrategyAcrossSourceFileNames(String filename) {
        Path path = Path.of("src", filename);
        String text = "first\n    nested\nend\nlast\nextra";
        Document document = new Document(
                path,
                new FileTypeDetector().detect(path).orElseThrow(),
                text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6).chunk(document);

        assertEquals(DocumentType.SOURCE_CODE, document.type());
        assertEquals("first\n    nested\n", chunks.getFirst().content());
        verify(document, chunks, tokenizer, 6);
    }

    @Test
    void rechecksPreferredSlicesWhenTokenCountsAreNonMonotonic() {
        Document document = document("a\nb\n\nc\nd\ne\nf\ng");
        TokenCounter counter = counter(text -> text.equals("a\nb\n\n")
                ? 9
                : (int) text.chars().filter(Character::isLetter).count());
        List<Chunk> chunks = new SourceCodeChunker(counter, 6).chunk(document);

        assertEquals("a\nb\n\nc\nd\n", chunks.getFirst().content());
        verify(document, chunks, counter, 6);
    }

    @Test
    void countsCombinedSourceSlicesInsteadOfAddingPerLineCounts() {
        TokenCounter counter = counter(text -> text.isEmpty() ? 0 : text.contains("a") && text.contains("b") ? 4 : 1);
        Document document = document("a\nb");
        List<Chunk> chunks = new SourceCodeChunker(counter, 4).chunk(document);

        assertEquals(List.of("a\n", "b"), chunks.stream().map(Chunk::content).toList());
        verify(document, chunks, counter, 4);
    }

    @Test
    void splitsOversizedLinesWithoutDiscardingEarlierLinesOrLeadingWhitespace() {
        SourceCodeChunker chunker = new SourceCodeChunker(tokenizer, 4);

        for (String text : List.of("one two three\n", "ok\none two three\n", "\n\none two three\n")) {
            Document document = document(text);
            List<Chunk> chunks = chunker.chunk(document);
            assertTrue(chunks.size() > 1);
            verify(document, chunks, tokenizer, 4, 0, false);
        }
    }

    @Test
    void rejectsInvalidBudgetsAndNullInputs() {
        assertThrows(NullPointerException.class, () -> new SourceCodeChunker(null, 32));
        assertThrows(NullPointerException.class, () -> new SourceCodeChunker(counter(text -> 0, null), 32));
        assertThrows(NullPointerException.class, () -> new SourceCodeChunker(tokenizer, 32).chunk(null));
        assertThrows(IllegalArgumentException.class, () -> new SourceCodeChunker(tokenizer, 8, -1));
        assertThrows(IllegalArgumentException.class, () -> new SourceCodeChunker(tokenizer, 8, 6));
        assertThrows(IllegalArgumentException.class, () -> new SourceCodeChunker(tokenizer, 8, 7));

        for (int limit : new int[] {-1, 0, 1, 2, 257}) {
            assertThrows(IllegalArgumentException.class, () -> new SourceCodeChunker(tokenizer, limit));
        }
    }

    @Test
    void batchesLargeRegionsThatNormalizeToZeroTokens() {
        AtomicInteger calls = new AtomicInteger();
        TokenCounter counter = counter(text -> {
            calls.incrementAndGet();

            return tokenizer.count(text);
        });
        Document document = document("\u200b\n".repeat(10_000) + "hello\n" + "\u200b\n".repeat(10_000));
        List<Chunk> chunks = new SourceCodeChunker(counter, 3).chunk(document);

        assertEquals(1, chunks.size());
        assertTrue(calls.get() < 50, "Whole-line growth should not count every prefix individually");
        verify(document, chunks, tokenizer, 3);
    }

    @Test
    void remainsDeterministicAcrossMixedWholeLineInputs() {
        Random random = new Random(102);
        String[] lines = {"one", "    two", "\tthree", "  four", "", " \t", "😀", "İçerik", "// hello!"};
        String[] endings = {"\n", "\r\n", "\r"};

        for (int example = 0; example < 40; example++) {
            StringBuilder source = new StringBuilder("start\n");

            for (int line = 0; line < 30; line++) {
                source.append(lines[random.nextInt(lines.length)]).append(endings[random.nextInt(endings.length)]);
            }

            source.append("end");
            Document document = document(source.toString());
            int limit = 12 + random.nextInt(12);
            SourceCodeChunker chunker = new SourceCodeChunker(tokenizer, limit);
            List<Chunk> chunks = chunker.chunk(document);
            assertEquals(chunks, chunker.chunk(document));
            verify(document, chunks, tokenizer, limit);
        }
    }

    @Test
    void keepsTheTwoArgumentConstructorEquivalentToZeroOverlap() {
        Document document = document("one two three four five six\nseven eight nine ten");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6).chunk(document);

        assertEquals(chunks, new SourceCodeChunker(tokenizer, 6, 0).chunk(document));
        verify(document, chunks, tokenizer, 6, 0, false);
    }

    @Test
    void prefersCompleteTrailingLinesOverLongerWordOverlap() {
        Document document = document("one two\n    three\n    four\n    five\n    six\n    seven");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6, 3).chunk(document);

        assertEquals("one two\n    three\n    four\n", chunks.getFirst().content());
        assertEquals(document.content().indexOf("    three"), startOffset(chunks.get(1)));
        assertTrue(chunks.get(1).content().startsWith("    three\n    four\n"));
        verify(document, chunks, tokenizer, 6, 3, true);
    }

    @Test
    void dropsOverlapInsteadOfSplittingTheNextFittingLine() {
        Document document = document("one\ntwo\nthree four five\nsix");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 5, 1).chunk(document);

        assertEquals("one\ntwo\n", chunks.getFirst().content());
        assertEquals("three four five\n", chunks.get(1).content());
        assertEquals(endOffset(chunks.getFirst()), startOffset(chunks.get(1)));
        verify(document, chunks, tokenizer, 5, 1, true);
    }

    @Test
    void overlapsWholeWordsInsideAnOversizedLine() {
        Document document = document("one two three four five six seven eight nine ten");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6, 2).chunk(document);

        assertEquals("one two three four ", chunks.getFirst().content());
        assertEquals("three four five six ", chunks.get(1).content());
        assertTrue(startOffset(chunks.get(1)) < endOffset(chunks.getFirst()));
        verify(document, chunks, tokenizer, 6, 2, false);
    }

    @Test
    void splitsAndOverlapsAnOversizedUnitAtCharacterBoundaries() {
        Document document = document("+".repeat(31));
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 7, 2).chunk(document);

        assertEquals("+++++", chunks.getFirst().content());
        assertEquals(3, startOffset(chunks.get(1)));
        verify(document, chunks, tokenizer, 7, 2, false);
    }

    @Test
    void doesNotUseSentencePreferencesDuringIntraLineFallback() {
        Document document = document("One. Two. Three four five six seven eight nine");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 8).chunk(document);

        assertEquals("One. Two. Three four ", chunks.getFirst().content());
        verify(document, chunks, tokenizer, 8, 0, false);
    }

    @Test
    void keepsLongUnknownWordsWholeWhenTheirActualTokenCountFits() {
        String unknown = "a".repeat(101);
        Document document = document(unknown + " hello world\n");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 3).chunk(document);

        assertEquals(unknown + " ", chunks.getFirst().content());
        verify(document, chunks, tokenizer, 3, 0, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void keepsFallbackAndOverlapOffsetsSafeAroundUnicodeAndLineTerminators(String newline) {
        TokenCounter counter = counter(text -> (int)
                text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count());
        Document document = document("+😀+😀+😀" + newline + "\t+😀+😀+😀" + newline + "last");
        List<Chunk> chunks = new SourceCodeChunker(counter, 5, 1).chunk(document);

        assertEquals("+😀+", chunks.getFirst().content());
        assertTrue(startOffset(chunks.get(1)) < endOffset(chunks.getFirst()));
        verify(document, chunks, counter, 5, 1, false);
    }

    @Test
    void attachesTrailingBlankLinesToTheLastNonBlankFallbackSlice() {
        Document document = document("+".repeat(12) + "\r\n\r\n\t\u00a0");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 4).chunk(document);

        assertTrue(chunks.getLast().content().endsWith("\r\n\r\n\t\u00a0"));
        verify(document, chunks, tokenizer, 4, 0, false);
    }

    @Test
    void leavesTheFollowingLinesIndentationOutsideTheOversizedLineFallback() {
        Document document = document("+".repeat(13) + "\r\n    next\r\n");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 7).chunk(document);

        assertEquals("    next\r\n", chunks.getLast().content());
        assertEquals(document.content().indexOf("    next"), startOffset(chunks.getLast()));
        verify(document, chunks, tokenizer, 7, 0, false);
    }

    @Test
    void rechecksNonMonotonicSuffixesBeforeUsingThemAsOverlap() {
        TokenCounter counter = counter(text -> text.equals("c\nd\n")
                ? 20
                : (int) text.chars().filter(Character::isLetter).count());
        Document document = document("a\nb\nc\nd\ne\nf\ng\nh\ni");
        List<Chunk> chunks = new SourceCodeChunker(counter, 6, 2).chunk(document);

        assertTrue(chunks.size() > 1);
        verify(document, chunks, counter, 6, 2, true);
    }

    @Test
    void rejectsAnIndivisibleSourceCharacterThatCannotFit() {
        TokenCounter counter = counter(text -> text.contains("😀") ? 10 : tokenizer.count(text));
        SourceCodeChunker chunker = new SourceCodeChunker(counter, 4, 1);

        for (String source : List.of("😀", "\n\n😀", "one\n😀")) {
            var exception = assertThrows(IllegalArgumentException.class, () -> chunker.chunk(document(source)));
            assertTrue(exception.getMessage().contains("cannot fit a source character"));
        }
    }

    @Test
    void maintainsProgressWithTheLargestAllowedOverlap() {
        Document document = document("+".repeat(80) + "\n" + "hello world ".repeat(50));
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 6, 3).chunk(document);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.size()
                <= document.content().codePointCount(0, document.content().length()));
        verify(document, chunks, tokenizer, 6, 3, false);
    }

    @Test
    void preservesLargeWhitespaceAndZeroTokenRegionsDuringFallback() {
        Document document = document(
                " \t".repeat(5000) + "\u200b ".repeat(5000) + "hello world ".repeat(250) + "\r\n".repeat(5000));
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 32, 8).chunk(document);

        assertTrue(chunks.size() > 1);
        verify(document, chunks, tokenizer, 32, 8, false);
    }

    @Test
    void avoidsRecountingHugeOversizedUnitsAndTheirZeroTokenPrefixes() {
        AtomicLong countedCharacters = new AtomicLong();
        TokenCounter counter = counter(text -> {
            countedCharacters.addAndGet(text.length());

            return (int) text.chars().filter(character -> character == '+').count();
        });
        Document document = document("\u200b".repeat(10_000) + "+".repeat(10_000));
        List<Chunk> chunks = new SourceCodeChunker(counter, 32, 8).chunk(document);

        assertTrue(chunks.size() > 1);
        assertTrue(
                countedCharacters.get() < 50L * document.content().length(),
                "Fallback should not recount the complete oversized suffix or every zero-token prefix per" + " chunk");
        verify(document, chunks, counter, 32, 8, false);
    }

    @Test
    void keepsMixedFallbackAndOverlapDeterministicWithoutGaps() {
        Random random = new Random(103);
        String[] units = {
            "hello",
            "paymentValidation",
            "İçerik",
            "cafe\u0301",
            "世😀",
            "[CLS]",
            "+=!?;",
            "a".repeat(101),
            " ",
            "\t",
            "\r\n",
            "\n\n",
            "\r",
            "\u00a0",
            "\u200b"
        };

        for (int example = 0; example < 100; example++) {
            StringBuilder source = new StringBuilder();

            for (int unit = 0; unit < 40; unit++) {
                source.append(units[random.nextInt(units.length)]);
            }

            int limit = 3 + random.nextInt(18);
            int overlap = random.nextInt(limit - 2);
            Document document = document(source.toString());
            SourceCodeChunker chunker = new SourceCodeChunker(tokenizer, limit, overlap);
            List<Chunk> chunks = assertDoesNotThrow(
                    () -> chunker.chunk(document),
                    "example="
                            + example
                            + ", limit="
                            + limit
                            + ", overlap="
                            + overlap
                            + ", source="
                            + document.content().replace("\r", "\\r").replace("\n", "\\n"));
            assertEquals(chunks, chunker.chunk(document));
            verify(document, chunks, tokenizer, limit, overlap, false);
        }
    }

    private static int startOffset(Chunk chunk) {
        return Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
    }

    private static int endOffset(Chunk chunk) {
        return Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
    }

    private static void verify(Document document, List<Chunk> chunks, TokenCounter counter, int limit) {
        verify(document, chunks, counter, limit, 0, true);
    }

    /**
     * Reconstructs only newly covered source while independently checking overlap budgets, safe offsets, and physical
     * line positions.
     */
    private static void verify(
            Document document, List<Chunk> chunks, TokenCounter counter, int limit, int overlap, boolean wholeLines) {
        StringBuilder reconstructed = new StringBuilder();
        int coveredEnd = 0;
        int previousStart = -1;

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
            int end = Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
            assertTrue(start > previousStart);
            assertTrue(start <= coveredEnd);
            assertTrue(end > coveredEnd);

            if (overlap == 0) {
                assertEquals(coveredEnd, start);
            }

            assertTrue(counter.count(document.content().substring(start, coveredEnd)) <= overlap);
            assertEquals(document.content().substring(start, end), chunk.content());
            assertEquals(document.sourcePath(), chunk.sourcePath());
            assertEquals(document.type(), chunk.documentType());
            assertEquals(index, chunk.index());
            assertFalse(chunk.content().isBlank());
            assertEquals(List.of(), chunk.metadata().headingPath());
            assertTrue(counter.countModelInput(chunk.content()) <= limit);
            assertEquals(
                    new LineRange(lineAt(document.content(), start), lineAt(document.content(), end - 1)),
                    chunk.sourceLocation());

            assertSafeBoundary(document.content(), start);
            assertSafeBoundary(document.content(), end);

            if (wholeLines && end < document.content().length()) {
                char previous = document.content().charAt(end - 1);
                assertTrue(previous == '\n' || previous == '\r');
                assertFalse(previous == '\r' && document.content().charAt(end) == '\n');
            }

            reconstructed.append(document.content(), coveredEnd, end);
            coveredEnd = end;
            previousStart = start;
        }

        assertEquals(document.content().length(), coveredEnd);
        assertEquals(document.content(), reconstructed.toString());
    }

    private static void assertSafeBoundary(String text, int offset) {
        if (offset > 0 && offset < text.length()) {
            assertFalse(Character.isHighSurrogate(text.charAt(offset - 1))
                    && Character.isLowSurrogate(text.charAt(offset)));
            assertFalse(text.charAt(offset - 1) == '\r' && text.charAt(offset) == '\n');
        }
    }

    /* Counts physical lines independently of the production boundary index while treating CRLF as one terminator. */
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

    private TokenCounter counter(ToIntFunction<String> count) {
        return counter(count, tokenizer.identity());
    }

    private static TokenCounter counter(ToIntFunction<String> count, TokenizerIdentity identity) {
        return new TokenCounter() {

            /**
             * Returns the identity supplied by the test.
             *
             * @return the configured tokenizer identity, which may be null for validation tests
             */
            @Override
            public TokenizerIdentity identity() {
                return identity;
            }

            /**
             * Counts tokens using the behavior supplied by the test.
             *
             * @param text source slice to count
             * @return the simulated content token count
             */
            @Override
            public int count(String text) {
                return count.applyAsInt(text);
            }
        };
    }

    private static Document document(String text) {
        return new Document(
                Path.of("src/Example.java"),
                DocumentType.SOURCE_CODE,
                text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
