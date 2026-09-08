package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.content.DocumentType;

import java.util.Objects;

public final class DocumentChunkerFactory {

    private final DocumentChunker textChunker;
    private final DocumentChunker markdownChunker;
    private final DocumentChunker sourceCodeChunker;

    public DocumentChunkerFactory(DocumentChunker textChunker, DocumentChunker markdownChunker,
                                  DocumentChunker sourceCodeChunker) {
        this.textChunker = Objects.requireNonNull(textChunker, "textChunker");
        this.markdownChunker = Objects.requireNonNull(markdownChunker, "markdownChunker");
        this.sourceCodeChunker = Objects.requireNonNull(sourceCodeChunker, "sourceCodeChunker");
    }

    /**
     * Returns the configured chunking strategy for the requested document type.
     *
     * @param type document type whose chunking strategy is requested
     * @return the existing strategy instance mapped to the document type
     * @throws NullPointerException if type is null
     */
    public DocumentChunker getChunker(DocumentType type) {
        Objects.requireNonNull(type, "type");

        return switch (type) {
            case PLAIN_TEXT, STRUCTURED_TEXT -> textChunker;
            case MARKDOWN -> markdownChunker;
            case SOURCE_CODE -> sourceCodeChunker;
        };
    }
}
