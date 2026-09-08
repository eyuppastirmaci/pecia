package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.content.DocumentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        DocumentChunkerFactory factory = new DocumentChunkerFactory(
                textChunker, markdownChunker, sourceCodeChunker);

        assertSame(textChunker, factory.getChunker(DocumentType.PLAIN_TEXT));
        assertSame(textChunker, factory.getChunker(DocumentType.STRUCTURED_TEXT));
        assertSame(markdownChunker, factory.getChunker(DocumentType.MARKDOWN));
        assertSame(sourceCodeChunker, factory.getChunker(DocumentType.SOURCE_CODE));
        assertSame(markdownChunker, factory.getChunker(DocumentType.MARKDOWN));
    }

    @Test
    void rejectsMissingStrategiesAtConstruction() {
        assertThrows(NullPointerException.class,
                () -> new DocumentChunkerFactory(null, markdownChunker, sourceCodeChunker));
        assertThrows(NullPointerException.class,
                () -> new DocumentChunkerFactory(textChunker, null, sourceCodeChunker));
        assertThrows(NullPointerException.class,
                () -> new DocumentChunkerFactory(textChunker, markdownChunker, null));
    }

    @Test
    void rejectsNullDocumentTypes() {
        DocumentChunkerFactory factory = new DocumentChunkerFactory(
                textChunker, markdownChunker, sourceCodeChunker);

        assertThrows(NullPointerException.class, () -> factory.getChunker(null));
    }
}
