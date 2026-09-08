package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.chunking.source.SourceCodeChunker;
import dev.eyuppastirmaci.pecia.chunking.text.TextChunker;

import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DocumentChunkerFactory {

    private final DocumentChunker textChunker;
    private final DocumentChunker markdownChunker;
    private final DocumentChunker sourceCodeChunker;
    private final Map<String, DocumentChunker> sourceChunkersByExtension;

    public DocumentChunkerFactory(DocumentChunker textChunker, DocumentChunker markdownChunker,
                                  DocumentChunker sourceCodeChunker) {
        this(textChunker, markdownChunker, sourceCodeChunker, Map.of());
    }

    public DocumentChunkerFactory(DocumentChunker textChunker, DocumentChunker markdownChunker,
                                  DocumentChunker sourceCodeChunker, Map<String, DocumentChunker> sourceChunkersByExtension) {
        this.textChunker = Objects.requireNonNull(textChunker, "textChunker");
        this.markdownChunker = Objects.requireNonNull(markdownChunker, "markdownChunker");
        this.sourceCodeChunker = Objects.requireNonNull(sourceCodeChunker, "sourceCodeChunker");
        this.sourceChunkersByExtension = normalizeExtensions(sourceChunkersByExtension);
    }

    /**
     * Creates reusable text, Markdown, and source-code strategies with a shared token policy.
     *
     * @param tokenCounter tokenizer used by the built-in strategies
     * @param maxTokens maximum input tokens including model-added special tokens
     * @param overlapTokens maximum best-effort overlap in content tokens, confined to oversized leaf blocks for Markdown
     * @return a factory with dedicated Markdown and source-code strategies and shared general text routing for other families
     * @throws NullPointerException if tokenCounter or its identity is null
     * @throws IllegalArgumentException if the token budget or overlap is incompatible with the tokenizer
     */
    public static DocumentChunkerFactory create(TokenCounter tokenCounter, int maxTokens, int overlapTokens) {
        return create(tokenCounter, maxTokens, overlapTokens, Map.of());
    }

    /**
     * Creates reusable built-in strategies with explicit extension overrides for documents already classified as source code.
     *
     * @param tokenCounter tokenizer used by the built-in strategies
     * @param maxTokens maximum input tokens including model-added special tokens
     * @param overlapTokens maximum best-effort overlap in content tokens, confined to oversized leaf blocks for Markdown
     * @param sourceChunkersByExtension custom strategies responsible for their own token policy, keyed by case-insensitive alphanumeric extensions with an optional leading dot
     * @return a factory with copied extension overrides and a general source-code strategy for unmatched source files
     * @throws NullPointerException if the tokenizer, its identity, the registry, a key, or a strategy is null
     * @throws IllegalArgumentException if token settings are invalid or extension keys are invalid or duplicate after normalization
     */
    public static DocumentChunkerFactory create(TokenCounter tokenCounter, int maxTokens, int overlapTokens,
                                               Map<String, DocumentChunker> sourceChunkersByExtension) {
        DocumentChunker textChunker = new TextChunker(tokenCounter, maxTokens, overlapTokens);
        DocumentChunker markdownChunker = new MarkdownChunker(tokenCounter, maxTokens, overlapTokens);
        DocumentChunker sourceCodeChunker = new SourceCodeChunker(tokenCounter, maxTokens, overlapTokens);

        return new DocumentChunkerFactory(textChunker, markdownChunker, sourceCodeChunker, sourceChunkersByExtension);
    }

    /**
     * Selects a registered final-extension strategy for source code or the document family's default strategy without reading the file.
     *
     * @param document classified document whose type and source filename determine the strategy
     * @return the existing extension-specific strategy or the family fallback when no source override matches
     * @throws NullPointerException if document is null
     */
    public DocumentChunker getChunker(Document document) {
        Objects.requireNonNull(document, "document");

        if (document.type() == DocumentType.SOURCE_CODE) {
            String filename = document.sourcePath().getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = filename.lastIndexOf('.');

            if (dot > 0 && dot < filename.length() - 1) {
                return sourceChunkersByExtension.getOrDefault(filename.substring(dot + 1), sourceCodeChunker);
            }
        }

        return getChunker(document.type());
    }

    /**
     * Returns the family-level strategy without applying filename-specific overrides.
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

    /* Canonicalizes extension keys into an immutable registry and rejects ambiguous aliases instead of silently overwriting them. */
    private static Map<String, DocumentChunker> normalizeExtensions(Map<String, DocumentChunker> extensions) {
        Objects.requireNonNull(extensions, "sourceChunkersByExtension");
        Map<String, DocumentChunker> normalized = new HashMap<>();

        for (Map.Entry<String, DocumentChunker> entry : extensions.entrySet()) {
            String extension = Objects.requireNonNull(entry.getKey(), "source extension").toLowerCase(Locale.ROOT);
            DocumentChunker chunker = Objects.requireNonNull(entry.getValue(), "source extension strategy");

            if (extension.startsWith(".")) {
                extension = extension.substring(1);
            }

            if (!extension.matches("[a-z0-9]+")) {
                throw new IllegalArgumentException("Source extension must be alphanumeric with an optional leading dot: " + entry.getKey());
            }

            if (normalized.putIfAbsent(extension, chunker) != null) {
                throw new IllegalArgumentException("Duplicate source extension: " + extension);
            }
        }

        return Map.copyOf(normalized);
    }
}
