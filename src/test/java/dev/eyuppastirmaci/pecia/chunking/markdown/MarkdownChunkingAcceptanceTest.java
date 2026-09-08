package dev.eyuppastirmaci.pecia.chunking.markdown;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContent;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownChunkingAcceptanceTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @ParameterizedTest
    @ValueSource(strings = {"guide.md", "GUIDE.MARKDOWN"})
    void extractsAndChunksMarkdownAcrossBomLineEndingFenceAndOverlapVariants(String filename) throws Exception {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            for (boolean bom : List.of(false, true)) {
                for (boolean closed : List.of(false, true)) {
                    for (int overlap : List.of(0, 3)) {
                        String preamble = "Önsöz 😀" + newline + newline;
                        String root = "# Installation" + newline;
                        String list = "- one" + newline + "- two" + newline + newline;
                        String windows = "## Windows" + newline + list + "```java" + newline
                                + ("# code, not heading" + newline + "int value = 1;" + newline).repeat(25);
                        String ending = (closed ? "```" + newline : "") + "# Usage" + newline + "end";
                        String text = preamble + root + windows + ending;
                        byte[] bytes = ((bom ? "\uFEFF" : "") + text).getBytes(StandardCharsets.UTF_8);
                        Document document = extract(filename, bytes);
                        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 24, overlap);
                        List<Chunk> chunks = factory.getChunker(document).chunk(document);
                        assertEquals(text, document.content());
                        assertEquals(ContentHash.sha256(bytes), document.contentHash());
                        assertEquals(DocumentType.MARKDOWN, document.type());
                        assertEquals(chunks, factory.getChunker(document).chunk(document));
                        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains(list)));
                        verifyCoverage(document, chunks, 24, overlap);

                        for (Chunk chunk : chunks) {
                            int start = offset(chunk, "startOffset");
                            List<String> expected = start < preamble.length() ? List.of()
                                    : start < preamble.length() + root.length() ? List.of("Installation")
                                    : closed && start >= text.lastIndexOf("# Usage") ? List.of("Usage")
                                    : List.of("Installation", "Windows");
                            assertEquals(expected, chunk.metadata().headingPath());
                        }
                    }
                }
            }
        }
    }

    @Test
    void preservesUnicodeWhitespaceAroundHeadingsAndOversizedBlocks() throws Exception {
        for (String whitespace : List.of("\f", "\u00a0", "\u2003")) {
            String text = whitespace + "\n\n# Root\n\n" + "word ".repeat(80) + "\n\n" + whitespace;
            Document document = extract("guide.md", text.getBytes(StandardCharsets.UTF_8));
            List<Chunk> chunks = DocumentChunkerFactory.create(tokenizer, 8, 2)
                                                     .getChunker(document).chunk(document);
            verifyCoverage(document, chunks, 8, 2);

            for (Chunk chunk : chunks) {
                assertEquals(List.of("Root"), chunk.metadata().headingPath());
                assertTrue(chunk.content().codePoints().anyMatch(value -> !Character.isWhitespace(value)
                        && !Character.isSpaceChar(value)));
            }
        }
    }

    @Test
    void preservesWhitespaceOnlyParagraphsInsideOversizedListItems() throws Exception {
        String text = "# Root\n- " + "first ".repeat(30) + "\n\n  \f\n\n  " + "last ".repeat(30) + "\n";
        Document document = extract("guide.md", text.getBytes(StandardCharsets.UTF_8));
        List<Chunk> chunks = DocumentChunkerFactory.create(tokenizer, 8, 2).getChunker(document).chunk(document);
        verifyCoverage(document, chunks, 8, 2);

        for (Chunk chunk : chunks) {
            assertEquals(List.of("Root"), chunk.metadata().headingPath());
        }
    }

    @Test
    void preservesResultsAcrossTurkishAndEnglishLocales() throws Exception {
        Document document = extract("İÇERİK.MD", ("# İÇERİK\n\n## İstanbul\n" + "ödeme doğrulama ".repeat(30))
                .getBytes(StandardCharsets.UTF_8));
        DocumentChunker chunker = DocumentChunkerFactory.create(tokenizer, 16, 3).getChunker(document);
        List<Chunk> expected = chunker.chunk(document);
        Locale previous = Locale.getDefault();

        try {
            for (Locale locale : List.of(Locale.forLanguageTag("tr-TR"), Locale.ENGLISH, Locale.ROOT)) {
                Locale.setDefault(locale);
                assertEquals(expected, chunker.chunk(document));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void sharedFactoryDoesNotLeakHeadingStateBetweenConcurrentDocuments() throws Exception {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 16, 3);
        List<Callable<List<Chunk>>> tasks = new ArrayList<>();
        List<List<Chunk>> expected = new ArrayList<>();

        for (int index = 0; index < 12; index++) {
            String text = "# Document " + index + "\n## Child\n" + "hello world ".repeat(30);
            Document document = extract("guide" + index + ".md", text.getBytes(StandardCharsets.UTF_8));
            DocumentChunker chunker = factory.getChunker(document);
            expected.add(chunker.chunk(document));
            tasks.add(() -> chunker.chunk(document));
        }

        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = executor.invokeAll(tasks);

            for (int index = 0; index < results.size(); index++) {
                assertEquals(expected.get(index), results.get(index).get());
            }
        }
    }

    private static Document extract(String filename, byte[] bytes) throws Exception {
        Path sourcePath = Path.of("docs", filename);
        Path file = sourcePath.toAbsolutePath().normalize();
        DocumentType type = new FileTypeDetector().detect(sourcePath).orElseThrow();

        return new TextDocumentExtractor().extract(new ExtractionRequest(file, sourcePath, type), new FileContent(file, bytes));
    }

    /* Reconstructs the extracted source and independently verifies global line locations, overlap, and model-inclusive limits. */
    private void verifyCoverage(Document document, List<Chunk> chunks, int limit, int overlap) {
        String text = document.content();
        StringBuilder reconstructed = new StringBuilder();
        int covered = 0;
        int previousStart = -1;

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = offset(chunk, "startOffset");
            int end = offset(chunk, "endOffset");
            assertTrue(start > previousStart && start <= covered && end > covered);
            assertEquals(text.substring(start, end), chunk.content());
            assertEquals(index, chunk.index());
            assertEquals(document.sourcePath(), chunk.sourcePath());
            assertEquals(document.type(), chunk.documentType());
            assertEquals(new LineRange(lineAt(text, start), lineAt(text, end - 1)), chunk.sourceLocation());
            assertTrue(tokenizer.countModelInput(chunk.content()) <= limit);
            assertTrue(tokenizer.count(text.substring(start, covered)) <= overlap);
            reconstructed.append(text, covered, end);
            covered = end;
            previousStart = start;
        }

        assertEquals(text.length(), covered);
        assertEquals(text, reconstructed.toString());
    }

    private static int offset(Chunk chunk, String name) {
        return Integer.parseInt(chunk.metadata().attributes().get(name));
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
}
