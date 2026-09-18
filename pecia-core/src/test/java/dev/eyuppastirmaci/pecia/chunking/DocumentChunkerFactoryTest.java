package dev.eyuppastirmaci.pecia.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.chunking.source.SourceCodeChunker;
import dev.eyuppastirmaci.pecia.chunking.text.TextChunker;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DocumentChunkerFactoryTest {

    private final DocumentChunker textChunker = document -> {
        throw new AssertionError("Selecting the text strategy must not run it");
    };
    private final DocumentChunker markdownChunker = document -> {
        throw new AssertionError("Selecting the Markdown strategy must not run it");
    };
    private final DocumentChunker sourceCodeChunker = document -> {
        throw new AssertionError("Selecting the source-code strategy must not run it");
    };

    @Test
    void selectsAndReusesInjectedStrategiesWithoutRunningThem() {
        DocumentChunkerFactory factory = new DocumentChunkerFactory(textChunker, markdownChunker, sourceCodeChunker);

        assertSame(textChunker, factory.getChunker(DocumentType.PLAIN_TEXT));
        assertSame(textChunker, factory.getChunker(DocumentType.STRUCTURED_TEXT));
        assertSame(markdownChunker, factory.getChunker(DocumentType.MARKDOWN));
        assertSame(sourceCodeChunker, factory.getChunker(DocumentType.SOURCE_CODE));
        assertSame(markdownChunker, factory.getChunker(DocumentType.MARKDOWN));
    }

    @Test
    void rejectsMissingStrategiesAtConstruction() {
        assertThrows(
                NullPointerException.class, () -> new DocumentChunkerFactory(null, markdownChunker, sourceCodeChunker));
        assertThrows(
                NullPointerException.class, () -> new DocumentChunkerFactory(textChunker, null, sourceCodeChunker));
        assertThrows(NullPointerException.class, () -> new DocumentChunkerFactory(textChunker, markdownChunker, null));
    }

    @Test
    void createsAndReusesTheBuiltInStrategies() {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), 256, 32);
        DocumentChunker markdown = factory.getChunker(DocumentType.MARKDOWN);
        DocumentChunker text = factory.getChunker(DocumentType.PLAIN_TEXT);
        DocumentChunker source = factory.getChunker(DocumentType.SOURCE_CODE);

        assertInstanceOf(MarkdownChunker.class, markdown);
        assertInstanceOf(TextChunker.class, text);
        assertInstanceOf(SourceCodeChunker.class, source);
        assertSame(markdown, factory.getChunker(DocumentType.MARKDOWN));
        assertSame(text, factory.getChunker(DocumentType.STRUCTURED_TEXT));
        assertSame(source, factory.getChunker(DocumentType.SOURCE_CODE));
        assertNotSame(text, source);
        assertThrows(NullPointerException.class, () -> factory.getChunker((DocumentType) null));
    }

    @Test
    void routesDetectedMarkdownExtensionsToHeadingAwareChunking() {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), 256, 0);
        FileTypeDetector detector = new FileTypeDetector();
        String text = "# Installation\nintro\n## Windows\n- one\n- two\n";

        for (String filename : List.of("guide.md", "guide.markdown", "GUIDE.MD", "GUIDE.MARKDOWN")) {
            Path path = Path.of(filename);
            DocumentType type = detector.detect(path).orElseThrow();
            Document document = document(path, type, text);
            DocumentChunker chunker = factory.getChunker(document);
            List<Chunk> chunks = chunker.chunk(document);

            assertEquals(new MarkdownChunker(MiniLmTokenizer.bundled(), 256, 0).chunk(document), chunks);
            assertEquals(
                    List.of(List.of("Installation"), List.of("Installation", "Windows")),
                    chunks.stream().map(chunk -> chunk.metadata().headingPath()).toList());
        }
    }

    @Test
    void doesNotApplyMarkdownInterpretationToOtherDocumentFamilies() {
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 256, 0);
        String text = "# First\nbody\n## Second\nbody\n";

        for (DocumentType type :
                List.of(DocumentType.PLAIN_TEXT, DocumentType.STRUCTURED_TEXT, DocumentType.SOURCE_CODE)) {
            Document document = document(Path.of("example.txt"), type, text);
            List<Chunk> chunks = factory.getChunker(document).chunk(document);

            DocumentChunker direct = type == DocumentType.SOURCE_CODE
                    ? new SourceCodeChunker(tokenizer, 256, 0)
                    : new TextChunker(tokenizer, 256, 0);
            assertEquals(direct.chunk(document), chunks);
            assertEquals(1, chunks.size());
            assertEquals(List.of(), chunks.getFirst().metadata().headingPath());
        }
    }

    @Test
    void passesTheSameBudgetAndOverlapToAllBuiltInStrategies() {
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 10, 3);
        String text = "one two three four five six seven eight ".repeat(20);

        for (DocumentType type : DocumentType.values()) {
            Document document = document(Path.of("example.md"), type, text);
            List<Chunk> chunks = factory.getChunker(document).chunk(document);

            DocumentChunker direct =
                    switch (type) {
                        case MARKDOWN -> new MarkdownChunker(tokenizer, 10, 3);
                        case SOURCE_CODE -> new SourceCodeChunker(tokenizer, 10, 3);
                        case PLAIN_TEXT, STRUCTURED_TEXT -> new TextChunker(tokenizer, 10, 3);
                    };

            assertEquals(direct.chunk(document), chunks);
            assertTrue(chunks.size() > 1);
            int previousEnd = 0;
            boolean hasOverlap = false;

            for (Chunk chunk : chunks) {
                int start = Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
                int end = Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
                assertTrue(tokenizer.countModelInput(chunk.content()) <= 10);
                assertTrue(tokenizer.count(text.substring(start, previousEnd)) <= 3);
                hasOverlap |= start < previousEnd;
                previousEnd = end;
            }

            assertTrue(hasOverlap);
            assertEquals(text.length(), previousEnd);
        }
    }

    @Test
    void routesEveryDeclaredSourceFilenameToTheSharedSourceCodeStrategy() {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), 6, 0);
        DocumentChunker source = factory.getChunker(DocumentType.SOURCE_CODE);
        FileTypeDetector detector = new FileTypeDetector();
        List<String> names = new ArrayList<>(DocumentType.SOURCE_CODE.basenames());

        for (String extension : DocumentType.SOURCE_CODE.extensions()) {
            names.add("example." + extension);
        }

        for (String name : names) {
            for (String filename : List.of(name, name.toUpperCase(Locale.ROOT))) {
                Path path = Path.of("src", filename);
                DocumentType type = detector.detect(path).orElseThrow();
                Document document = document(path, type, "first\n    nested\nend\nlast\nextra");
                assertEquals(DocumentType.SOURCE_CODE, type);
                assertSame(source, factory.getChunker(document));
                assertEquals(
                        "first\n    nested\n",
                        factory.getChunker(document).chunk(document).getFirst().content());
            }
        }
    }

    @Test
    void executesSourceBoundariesInsteadOfThePreviousGeneralTextFallback() {
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 6, 0);
        Document document = document(
                Path.of("src", "Example.java"), DocumentType.SOURCE_CODE, "first\n    nested\nend\nlast\nextra");
        List<Chunk> chunks = factory.getChunker(document).chunk(document);

        assertEquals(new SourceCodeChunker(tokenizer, 6, 0).chunk(document), chunks);
        assertNotEquals(new TextChunker(tokenizer, 6, 0).chunk(document), chunks);
        assertEquals("first\n    nested\n", chunks.getFirst().content());
    }

    @Test
    void rejectsInvalidBuiltInConfigurationImmediately() {
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

        assertThrows(NullPointerException.class, () -> DocumentChunkerFactory.create(null, 256, 32));
        assertThrows(IllegalArgumentException.class, () -> DocumentChunkerFactory.create(tokenizer, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> DocumentChunkerFactory.create(tokenizer, 257, 0));
        assertThrows(IllegalArgumentException.class, () -> DocumentChunkerFactory.create(tokenizer, 8, -1));
        assertThrows(IllegalArgumentException.class, () -> DocumentChunkerFactory.create(tokenizer, 8, 6));
    }

    @Test
    void rejectsNullDocumentTypes() {
        DocumentChunkerFactory factory = new DocumentChunkerFactory(textChunker, markdownChunker, sourceCodeChunker);

        assertThrows(NullPointerException.class, () -> factory.getChunker((DocumentType) null));
    }

    private static Document document(Path path, DocumentType type, String text) {
        return new Document(path, type, text, ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
