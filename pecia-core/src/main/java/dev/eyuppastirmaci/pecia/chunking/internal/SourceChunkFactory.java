package dev.eyuppastirmaci.pecia.chunking.internal;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.LineRange;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/** Creates chunks with original source offsets and line locations. */
public final class SourceChunkFactory {

    private SourceChunkFactory() {}

    /**
     * Creates a non-blank source slice that extends coverage with offsets and the caller's line
     * mapping.
     *
     * @param document source document whose content is sliced without normalization
     * @param index zero-based index of the resulting chunk
     * @param start inclusive UTF-16 source offset
     * @param end exclusive UTF-16 source offset
     * @param coveredEnd exclusive end of previously covered source text
     * @param lineAt mapping from a source offset to its one-based line number
     * @return a source chunk with an empty heading path and preserved document identity
     * @throws NullPointerException if the document or line mapping is null
     * @throws IndexOutOfBoundsException if the slice offsets are outside the source or reversed
     * @throws IllegalArgumentException if the slice is blank, does not extend coverage, or has
     *     invalid chunk metadata
     */
    public static Chunk create(
            Document document, int index, int start, int end, int coveredEnd, IntUnaryOperator lineAt) {
        String content = document.content().substring(start, end);

        if (end <= coveredEnd || SourceText.skipWhitespace(content, 0) == content.length()) {
            throw new IllegalArgumentException("Token budget cannot fit a source character at offset " + start);
        }

        ChunkMetadata metadata = new ChunkMetadata(
                List.of(),
                Map.of(
                        "startOffset", Integer.toString(start),
                        "endOffset", Integer.toString(end)));
        // The exclusive end lies outside the slice, so its preceding character determines the final
        // line.
        LineRange location = new LineRange(lineAt.applyAsInt(start), lineAt.applyAsInt(end - 1));

        return new Chunk(document.sourcePath(), document.type(), index, content, location, metadata);
    }
}
