package dev.eyuppastirmaci.pecia.chunking.markdown;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.internal.SourceText;
import dev.eyuppastirmaci.pecia.chunking.text.TextChunker;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Chunks Markdown along heading and block boundaries while preserving heading context. */
public final class MarkdownChunker implements DocumentChunker {

    private final MarkdownBlockPlanner planner;
    private final TextChunker fallback;
    private final MarkdownSectionParser sectionParser = new MarkdownSectionParser();

    /**
     * Creates a Markdown chunker with overlap limited to oversized leaf blocks.
     *
     * @param maxTokens model input limit including special tokens
     * @param overlapTokens maximum best-effort overlap in content tokens
     * @throws NullPointerException if the tokenizer or its identity is null
     * @throws IllegalArgumentException if the token budget or overlap is invalid
     */
    public MarkdownChunker(TokenCounter tokenCounter, int maxTokens, int overlapTokens) {
        fallback = new TextChunker(tokenCounter, maxTokens, overlapTokens);
        planner = new MarkdownBlockPlanner(tokenCounter, maxTokens);
    }

    /**
     * Splits Markdown at structural boundaries with token-bounded text fallback and overlap confined
     * to oversized leaf blocks.
     *
     * @param document extracted document whose original text and source identity are preserved
     * @return immutable source-ordered chunks with UTF-16 offsets, line ranges, and heading paths, or
     *     empty for whitespace-only text
     * @throws NullPointerException if document is null
     * @throws IllegalArgumentException if an indivisible source character cannot fit within the token
     *     budget
     */
    @Override
    public List<Chunk> chunk(Document document) {
        List<MarkdownBlockGroup> groups = planner.planWithContainerSplitting(document.content());

        if (groups.isEmpty()) {
            return List.of();
        }

        SourceText boundaries = new SourceText(document.content());
        List<MarkdownSection> sections = sectionParser.parse(document.content());
        List<Chunk> chunks = new ArrayList<>();
        int sectionIndex = 0;

        for (MarkdownBlockGroup group : groups) {
            // Leading blank lines can share a group with the first heading, so use the first
            // non-whitespace source character.
            int contextOffset = SourceText.skipWhitespace(document.content(), group.startOffset());

            while (sectionIndex + 1 < sections.size()
                    && sections.get(sectionIndex).endOffset() <= contextOffset) {
                sectionIndex++;
            }

            List<String> headingPath = sections.get(sectionIndex).headingPath();

            if (group.requiresSplit()) {
                appendFallback(document, boundaries, group, headingPath, chunks);
            } else {
                append(document, boundaries, group.startOffset(), group.endOffset(), headingPath, chunks);
            }
        }

        return List.copyOf(chunks);
    }

    /**
     * Translates fragment-local fallback offsets back to the original document without changing its
     * raw-byte hash or source text.
     */
    private void appendFallback(
            Document document,
            SourceText boundaries,
            MarkdownBlockGroup group,
            List<String> headingPath,
            List<Chunk> chunks) {
        String content = document.content().substring(group.startOffset(), group.endOffset());
        Document fragment = new Document(document.sourcePath(), document.type(), content, document.contentHash());

        for (Chunk chunk : fallback.chunk(fragment)) {
            int start = group.startOffset()
                    + Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
            int end = group.startOffset()
                    + Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
            append(document, boundaries, start, end, headingPath, chunks);
        }
    }

    private static void append(
            Document document,
            SourceText boundaries,
            int start,
            int end,
            List<String> headingPath,
            List<Chunk> chunks) {
        // Heading context remains metadata only, leaving the exact source slice and its token budget
        // unchanged.
        ChunkMetadata metadata = new ChunkMetadata(
                headingPath,
                Map.of(
                        "startOffset", Integer.toString(start),
                        "endOffset", Integer.toString(end)));
        LineRange location = new LineRange(boundaries.lineAt(start), boundaries.lineAt(end - 1));
        chunks.add(new Chunk(
                document.sourcePath(),
                document.type(),
                chunks.size(),
                document.content().substring(start, end),
                location,
                metadata));
    }
}
