package dev.eyuppastirmaci.pecia.chunking.source;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContent;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCodeLocationTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void assignsLineTerminatorsToTheLinesTheyTerminate(String newline) {
        Document document = document("one" + newline + " \ttwo" + newline + "three" + newline);
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 3).chunk(document);
        int firstEnd = 3 + newline.length();
        int secondEnd = firstEnd + 5 + newline.length();

        assertEquals(3, chunks.size());
        assertSlice(document, chunks.get(0), 0, "one" + newline, 0, firstEnd, 1, 1);
        assertSlice(document, chunks.get(1), 1, " \ttwo" + newline, firstEnd, secondEnd, 2, 2);
        assertSlice(document, chunks.get(2), 2, "three" + newline, secondEnd, document.content().length(), 3, 3);
    }

    @Test
    void usesGlobalOffsetsAcrossMixedLineEndings() {
        Document document = document("one\r\n \ttwo\rthree\nfour");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 3).chunk(document);

        assertEquals(4, chunks.size());
        assertSlice(document, chunks.get(0), 0, "one\r\n", 0, 5, 1, 1);
        assertSlice(document, chunks.get(1), 1, " \ttwo\r", 5, 11, 2, 2);
        assertSlice(document, chunks.get(2), 2, "three\n", 11, 17, 3, 3);
        assertSlice(document, chunks.get(3), 3, "four", 17, 21, 4, 4);
    }

    @Test
    void usesUtf16RatherThanByteOrCodePointOffsetsDuringFallback() {
        Document document = document("😀+\r\n\t++");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 3).chunk(document);

        assertEquals(4, chunks.size());
        assertSlice(document, chunks.get(0), 0, "😀", 0, 2, 1, 1);
        assertSlice(document, chunks.get(1), 1, "+\r\n", 2, 5, 1, 1);
        assertSlice(document, chunks.get(2), 2, "\t+", 5, 7, 2, 2);
        assertSlice(document, chunks.get(3), 3, "+", 7, 8, 2, 2);
        assertEquals(4, chunks.getFirst().content().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(1, chunks.getFirst().content().codePointCount(0, 2));
    }

    @Test
    void recordsOverlappingOffsetsWithinOnePhysicalLine() {
        Document document = document("++++++");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 5, 1).chunk(document);

        assertEquals(3, chunks.size());
        assertSlice(document, chunks.get(0), 0, "+++", 0, 3, 1, 1);
        assertSlice(document, chunks.get(1), 1, "+++", 2, 5, 1, 1);
        assertSlice(document, chunks.get(2), 2, "++", 4, 6, 1, 1);
        verifySource(document, chunks, 5, 1);
    }

    @Test
    void recordsOverlappingLineRangesWithoutResettingFragmentOffsets() {
        Document document = document("one\ntwo\nthree\nfour\nfive");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 5, 1).chunk(document);

        assertEquals(2, chunks.size());
        assertSlice(document, chunks.get(0), 0, "one\ntwo\nthree\n", 0, 14, 1, 3);
        assertSlice(document, chunks.get(1), 1, "three\nfour\nfive", 8, 23, 3, 5);
        verifySource(document, chunks, 5, 1);
    }

    @Test
    void includesLeadingAndTrailingWhitespaceInTheSourceRange() {
        Document document = document(" \r\n\t\none\r\n\r\n\t");
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 3).chunk(document);

        assertEquals(1, chunks.size());
        assertSlice(document, chunks.getFirst(), 0, document.content(), 0, 13, 1, 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\r\n", "\r"})
    void doesNotIncludeAPhantomLineAtEndOfInput(String ending) {
        Document document = document("one" + ending);
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 3).chunk(document);

        assertEquals(1, chunks.size());
        assertSlice(document, chunks.getFirst(), 0, document.content(), 0, document.content().length(), 1, 1);
    }

    @Test
    void leavesUnicodeNormalizationIndentationAndMarkdownLikeSyntaxUntouched() {
        String text = "// İSTANBUL café cafe\u0301 😀\r\n\tconst view = <Panel title=\"# Heading\">\r"
                + "  {value}\n</Panel>;\n```\n\u00a0\u2003";
        Document document = document(text);
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 8, 2).chunk(document);

        assertTrue(chunks.size() > 1);
        verifySource(document, chunks, 8, 2);
        assertEquals(text, document.content());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsExtractedTextCoordinatesSeparateFromTheRawByteHash(boolean bom) throws Exception {
        String text = "😀+\r\n\t" + "+".repeat(20) + "\r\n";
        byte[] bytes = ((bom ? "\uFEFF" : "") + text).getBytes(StandardCharsets.UTF_8);
        Path source = Path.of("src", "İçerik.tsx");
        Path file = directory.resolve(source).toAbsolutePath().normalize();
        FileContent loaded = new FileContent(file, bytes);
        Document document = new TextDocumentExtractor().extract(
                new ExtractionRequest(file, source, DocumentType.SOURCE_CODE), loaded);
        ContentHash hash = document.contentHash();
        List<Chunk> chunks = new SourceCodeChunker(tokenizer, 5, 1).chunk(document);

        assertEquals(text, document.content());
        assertSame(hash, document.contentHash());
        assertEquals(ContentHash.sha256(bytes), hash);
        assertArrayEquals(bytes, loaded.bytes());
        assertSlice(document, chunks.getFirst(), 0, "😀+\r\n", 0, 5, 1, 1);
        verifySource(document, chunks, 5, 1);

        if (bom) {
            assertNotEquals(ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)), hash);
        }
    }

    @Test
    void keepsMetadataImmutableAndSourceIdentityLocalToEachDocument() {
        SourceCodeChunker chunker = new SourceCodeChunker(tokenizer, 5, 1);
        Document first = document("++++++");
        Document second = new Document(Path.of("other", "SameContent.py"), DocumentType.SOURCE_CODE,
                first.content(), first.contentHash());
        List<Chunk> original = chunker.chunk(first);
        List<Chunk> other = chunker.chunk(second);

        assertEquals(original, chunker.chunk(first));
        assertEquals(original.size(), other.size());
        verifySource(second, other, 5, 1);
        assertEquals(0, other.getFirst().index());
        assertThrows(UnsupportedOperationException.class, () -> original.add(original.getFirst()));
        assertThrows(UnsupportedOperationException.class, () -> original.getFirst().metadata().attributes().put("startOffset", "99"));
        assertThrows(UnsupportedOperationException.class, () -> original.getFirst().metadata().headingPath().add("Heading"));
    }

    /* Checks exact source coordinates and identity against explicit expected values rather than the production boundary helpers. */
    private static void assertSlice(Document document, Chunk chunk, int index, String content,
                                    int start, int end, int firstLine, int lastLine) {
        assertEquals(content, chunk.content());
        assertEquals(document.content().substring(start, end), chunk.content());
        assertEquals(document.sourcePath(), chunk.sourcePath());
        assertEquals(document.type(), chunk.documentType());
        assertEquals(index, chunk.index());
        assertEquals(new LineRange(firstLine, lastLine), chunk.sourceLocation());
        assertEquals(List.of(), chunk.metadata().headingPath());
        assertEquals(Map.of("startOffset", Integer.toString(start), "endOffset", Integer.toString(end)),
                chunk.metadata().attributes());
    }

    /* Reconstructs only newly covered characters and independently verifies overlap, token budgets, and line ranges. */
    private void verifySource(Document document, List<Chunk> chunks, int limit, int overlap) {
        String text = document.content();
        StringBuilder reconstructed = new StringBuilder();
        int covered = 0;
        int previousStart = -1;

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
            int end = Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
            assertTrue(start > previousStart && start <= covered);
            assertTrue(end > covered && end <= text.length());
            assertTrue(tokenizer.countModelInput(chunk.content()) <= limit);
            assertTrue(tokenizer.count(text.substring(start, covered)) <= overlap);
            assertSlice(document, chunk, index, text.substring(start, end), start, end,
                    lineAt(text, start), lineAt(text, end - 1));

            for (int offset : new int[] {start, end}) {
                if (offset > 0 && offset < text.length()) {
                    assertFalse(Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)));
                    assertFalse(text.charAt(offset - 1) == '\r' && text.charAt(offset) == '\n');
                }
            }

            reconstructed.append(text, covered, end);
            covered = end;
            previousStart = start;
        }

        assertEquals(text.length(), covered);
        assertEquals(text, reconstructed.toString());
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), reconstructed.toString().getBytes(StandardCharsets.UTF_8));
    }

    /* Counts complete terminators before a source character, treating CRLF as a single terminator. */
    private static int lineAt(String text, int offset) {
        var terminators = Pattern.compile("\r\n|\r|\n").matcher(text);
        int line = 1;

        while (terminators.find() && terminators.end() <= offset) {
            line++;
        }

        return line;
    }

    private static Document document(String text) {
        return new Document(Path.of("src", "Example.java"), DocumentType.SOURCE_CODE, text,
                ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
