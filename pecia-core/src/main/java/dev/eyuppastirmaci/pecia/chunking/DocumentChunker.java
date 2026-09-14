package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;

import java.util.List;

public interface DocumentChunker {

    /**
     * Splits a document into chunks using the strategy's configured token budget and overlap while preserving source locations.
     *
     * @param document extracted document to split
     * @return an immutable list of chunks in source order with consecutive zero-based indices, or empty for blank content
     * @throws NullPointerException if document is null
     */
    List<Chunk> chunk(Document document);
}
