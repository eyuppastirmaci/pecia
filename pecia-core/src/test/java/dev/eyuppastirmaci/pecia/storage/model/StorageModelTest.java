package dev.eyuppastirmaci.pecia.storage.model;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageModelTest {
    private static final Path SOURCE = Path.of("docs", "İstanbul.md");
    private static final ContentHash HASH = ContentHash.sha256("\uFEFF# Başlık\r\n".getBytes(StandardCharsets.UTF_8));

    @Test
    void retainsSourceIdentityAndRawByteHashWithoutDocumentContent() {
        StoredFile file = new StoredFile(1, SOURCE, DocumentType.MARKDOWN, HASH);

        assertEquals(SOURCE, file.sourcePath());
        assertEquals(DocumentType.MARKDOWN, file.documentType());
        assertSame(HASH, file.contentHash());
    }

    @Test
    void retainsTheExistingChunkIncludingUnicodeOffsetsAndMetadata() {
        ChunkMetadata metadata = new ChunkMetadata(List.of("Başlık", "Alt"),
                Map.of("startOffset", "9", "endOffset", "14", "custom", "\"Türkçe\"\n"));
        Chunk chunk = new Chunk(SOURCE, DocumentType.MARKDOWN, 2, "😀 é", new LineRange(3, 3), metadata);
        StoredChunk stored = new StoredChunk(7, 1, chunk);

        assertSame(chunk, stored.chunk());
        assertEquals(7, stored.id());
        assertEquals(1, stored.fileId());
        assertEquals(5, stored.chunk().content().length());
        assertEquals(metadata, stored.chunk().metadata());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsUnpersistedIdentifiers(long id) {
        Chunk chunk = new Chunk(SOURCE, DocumentType.MARKDOWN, 0, "text", new LineRange(1, 1), ChunkMetadata.empty());

        assertThrows(IllegalArgumentException.class, () -> new StoredFile(id, SOURCE, DocumentType.MARKDOWN, HASH));
        assertThrows(IllegalArgumentException.class, () -> new StoredChunk(id, 1, chunk));
        assertThrows(IllegalArgumentException.class, () -> new StoredChunk(1, id, chunk));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "../outside.md", "docs/../file.md"})
    void sharesTheCoreSourcePathContract(String path) {
        assertThrows(IllegalArgumentException.class,
                () -> new StoredFile(1, Path.of(path), DocumentType.MARKDOWN, HASH));
    }

    @Test
    void rejectsAbsoluteSourcePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoredFile(1, SOURCE.toAbsolutePath(), DocumentType.MARKDOWN, HASH));
    }

    @Test
    void rejectsMissingRequiredData() {
        assertThrows(NullPointerException.class, () -> new StoredFile(1, null, DocumentType.MARKDOWN, HASH));
        assertThrows(NullPointerException.class, () -> new StoredFile(1, SOURCE, null, HASH));
        assertThrows(NullPointerException.class, () -> new StoredFile(1, SOURCE, DocumentType.MARKDOWN, null));
        assertThrows(NullPointerException.class, () -> new StoredChunk(1, 1, null));
    }

    @Test
    void rejectsLocationsTheInitialSchemaCannotRepresent() {
        Chunk chunk = new Chunk(SOURCE, DocumentType.MARKDOWN, 0, "text", new SourceLocation() { }, ChunkMetadata.empty());

        assertThrows(IllegalArgumentException.class, () -> new StoredChunk(1, 1, chunk));
    }
}
