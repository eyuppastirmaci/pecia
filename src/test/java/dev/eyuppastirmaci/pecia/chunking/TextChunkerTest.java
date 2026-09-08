package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void preservesShortTextExactlyIncludingLeadingAndTrailingWhitespace() {
        Document document = document(" \tHello, world!\r\n", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 32, 0).chunk(document);

        assertEquals(1, chunks.size());
        assertEquals(document.content(), chunks.getFirst().content());
        assertEquals(new LineRange(1, 1), chunks.getFirst().sourceLocation());
        verify(document, chunks, 32, 0);
        assertThrows(UnsupportedOperationException.class, () -> chunks.add(chunks.getFirst()));
    }

    @Test
    void returnsNoChunksForEmptyOrWhitespaceOnlyDocuments() {
        TextChunker chunker = new TextChunker(tokenizer, 32, 0);

        for (String content : List.of("", " ", "\t\r\n\n", "\u00a0\u2003")) {
            assertEquals(List.of(), chunker.chunk(document(content, DocumentType.PLAIN_TEXT)));
        }
    }

    @Test
    void prefersCompleteParagraphsOverLaterSentenceOrWordBoundaries() {
        Document document = document("One. Two.\n \nThree four five six seven eight.", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 10, 0).chunk(document);

        assertEquals("One. Two.\n \n", chunks.getFirst().content());
        verify(document, chunks, 10, 0);
    }

    @Test
    void prefersSentenceBoundariesIncludingClosingQuotes() {
        Document document = document("\"Hello world.\" A very long sentence has many words.", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 10, 0).chunk(document);

        assertEquals("\"Hello world.\" ", chunks.getFirst().content());
        verify(document, chunks, 10, 0);
    }

    @Test
    void usesLineBoundariesWhenNoParagraphOrSentenceFits() {
        Document document = document("one two\nthree four five six seven", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 6, 0).chunk(document);

        assertEquals("one two\n", chunks.getFirst().content());
        verify(document, chunks, 6, 0);
    }

    @Test
    void reservesSpecialTokensAndAllowsAnExactlyFullChunk() {
        Document full = document("hello ".repeat(254), DocumentType.PLAIN_TEXT);
        Document oversized = document("hello ".repeat(255), DocumentType.PLAIN_TEXT);
        TextChunker chunker = new TextChunker(tokenizer, 256, 0);

        assertEquals(1, chunker.chunk(full).size());
        assertEquals(256, tokenizer.countModelInput(chunker.chunk(full).getFirst().content()));
        assertEquals(2, chunker.chunk(oversized).size());
        verify(oversized, chunker.chunk(oversized), 256, 0);
    }

    @Test
    void reusesAWholeWordSuffixWithinTheOverlapBudget() {
        Document document = document("one two three four five six seven eight", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 6, 2).chunk(document);

        assertEquals(List.of("one two three four ", "three four five six ", "five six seven eight"),
                chunks.stream().map(Chunk::content).toList());
        verify(document, chunks, 6, 2);
    }

    @Test
    void dropsOverlapIfItPreventsForwardProgress() {
        Document document = document("one two paymentValidation hello", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 6, 2).chunk(document);

        assertEquals(List.of("one two ", "paymentValidation ", "hello"),
                chunks.stream().map(Chunk::content).toList());
        verify(document, chunks, 6, 2);
    }

    @Test
    void splitsOversizedWordsWithoutDiscardingTheirSourceCharacters() {
        Document document = document("paymentValidation", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 4, 0).chunk(document);

        assertTrue(chunks.size() > 1);
        verify(document, chunks, 4, 0);
    }

    @Test
    void preservesLongUnknownWordsEvenWhenTheirTokenCountsAreNonMonotonic() {
        String longWord = "a".repeat(101);
        assertTrue(tokenizer.count(longWord.substring(0, 100)) > tokenizer.count(longWord));
        Document document = document(longWord, DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 3, 0).chunk(document);

        assertEquals(1, chunks.size());
        assertEquals(longWord, chunks.getFirst().content());
        verify(document, chunks, 3, 0);
    }

    @Test
    void preservesCrLfLoneCrAndAccurateInclusiveLineLocations() {
        Document document = document("one two\r\n\r\nthree four\r\nfive six\rseven", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 4, 0).chunk(document);

        assertEquals(List.of(new LineRange(1, 2), new LineRange(3, 3), new LineRange(4, 4), new LineRange(5, 5)),
                chunks.stream().map(Chunk::sourceLocation).toList());
        verify(document, chunks, 4, 0);
    }

    @Test
    void neverSplitsSupplementaryUnicodeCharacters() {
        Document document = document("世😀世😀世😀", DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 3, 0).chunk(document);

        assertEquals(6, chunks.size());
        verify(document, chunks, 3, 0);
    }

    @Test
    void preservesTurkishAndCombiningCharactersAcrossLocaleChanges() {
        Document document = document("İstanbul'da ödeme doğrulama.\n\nBaşlık: cafe\u0301 ve içerik.\n".repeat(3),
                DocumentType.PLAIN_TEXT);
        TextChunker chunker = new TextChunker(tokenizer, 14, 3);
        List<Chunk> expected = chunker.chunk(document);
        Locale previous = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(expected, chunker.chunk(document));
        } finally {
            Locale.setDefault(previous);
        }

        verify(document, expected, 14, 3);
    }

    @Test
    void worksAsTheFactorysSharedGeneralTextStrategy() {
        TextChunker textChunker = new TextChunker(tokenizer, 12, 0);
        DocumentChunkerFactory factory = new DocumentChunkerFactory(textChunker, textChunker, textChunker);

        for (DocumentType type : List.of(DocumentType.PLAIN_TEXT, DocumentType.STRUCTURED_TEXT)) {
            Document document = document("name: sample\nvalue: another value\n".repeat(4), type);
            verify(document, factory.getChunker(type).chunk(document), 12, 0);
        }
    }

    @Test
    void rejectsInvalidBudgetsAndNullInputs() {
        assertThrows(NullPointerException.class, () -> new TextChunker(null, 256, 32));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(tokenizer, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(tokenizer, 257, 0));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(tokenizer, 8, -1));
        assertThrows(IllegalArgumentException.class, () -> new TextChunker(tokenizer, 8, 6));
        assertThrows(NullPointerException.class, () -> new TextChunker(tokenizer, 256, 32).chunk(null));
    }

    @Test
    void maintainsCoverageAndLimitsAcrossSeededMixedInputs() {
        Random random = new Random(8);
        String[] units = {"hello", "paymentValidation", "İçerik", "cafe\u0301", "世😀", "[CLS]", "word.",
                "a".repeat(101), " ", "\t", "\r\n", "\n\n", "\r", "\u00a0"};

        for (int example = 0; example < 60; example++) {
            StringBuilder text = new StringBuilder("start ");

            for (int unit = 0; unit < 30; unit++) {
                text.append(units[random.nextInt(units.length)]);
            }

            int limit = 4 + random.nextInt(17);
            int overlap = random.nextInt(limit - 2);
            Document document = document(text.toString(), DocumentType.PLAIN_TEXT);
            TextChunker chunker = new TextChunker(tokenizer, limit, overlap);
            List<Chunk> chunks = chunker.chunk(document);
            assertEquals(chunks, chunker.chunk(document));
            verify(document, chunks, limit, overlap);
        }
    }

    @Test
    void processesLargeParagraphsAndWhitespaceRunsWithoutLosingContent() {
        Document document = document(" \t".repeat(5000) + "hello world ".repeat(1000) + "\r\n".repeat(5000),
                DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 256, 32).chunk(document);

        assertTrue(chunks.size() > 1);
        verify(document, chunks, 256, 32);
    }

    @Test
    void preservesLargeRegionsThatNormalizeToNoTokens() {
        Document document = document("\u200b ".repeat(10_000) + "hello ".repeat(300)
                + "\u200b ".repeat(10_000), DocumentType.PLAIN_TEXT);
        List<Chunk> chunks = new TextChunker(tokenizer, 256, 32).chunk(document);

        assertEquals(2, chunks.size());
        verify(document, chunks, 256, 32);
    }

    /* Reconstructs the source from recorded offsets while independently checking coverage, overlap, and line locations. */
    private void verify(Document document, List<Chunk> chunks, int limit, int overlap) {
        int coveredEnd = 0;
        int previousStart = -1;
        StringBuilder reconstructed = new StringBuilder();

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
            int end = Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
            assertTrue(start > previousStart);
            assertTrue(start <= coveredEnd);
            assertTrue(end > coveredEnd);
            assertEquals(document.content().substring(start, end), chunk.content());
            assertEquals(document.sourcePath(), chunk.sourcePath());
            assertEquals(document.type(), chunk.documentType());
            assertEquals(index, chunk.index());
            assertFalse(chunk.content().isBlank());
            assertEquals(List.of(), chunk.metadata().headingPath());
            assertTrue(tokenizer.countModelInput(chunk.content()) <= limit);
            assertTrue(tokenizer.count(document.content().substring(start, coveredEnd)) <= overlap);
            assertEquals(new LineRange(lineAt(document.content(), start), lineAt(document.content(), end - 1)),
                    chunk.sourceLocation());
            assertSafeBoundary(document.content(), start);
            assertSafeBoundary(document.content(), end);
            reconstructed.append(document.content(), coveredEnd, end);
            coveredEnd = end;
            previousStart = start;
        }

        assertEquals(document.content().length(), coveredEnd);
        assertEquals(document.content(), reconstructed.toString());

        if (overlap == 0) {
            assertEquals(document.content(), chunks.stream().map(Chunk::content).collect(Collectors.joining()));
        }
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

    private static Document document(String text, DocumentType type) {
        return new Document(Path.of("docs/example.txt"), type, text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
