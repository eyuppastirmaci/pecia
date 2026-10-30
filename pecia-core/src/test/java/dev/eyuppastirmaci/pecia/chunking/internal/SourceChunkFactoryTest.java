package dev.eyuppastirmaci.pecia.chunking.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A source chunk must extend the covered text by at least one character. */
class SourceChunkFactoryTest {

    private static final String TEXT = "int a;\nint b;\n";
    private static final Document DOCUMENT = new Document(
            Path.of("src/Sample.java"),
            DocumentType.SOURCE_CODE,
            TEXT,
            ContentHash.sha256(TEXT.getBytes(StandardCharsets.UTF_8)));

    @Test
    void aSliceThatEndsWhereCoverageAlreadyEndsIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> SourceChunkFactory.create(DOCUMENT, 1, 0, 7, 7, offset -> 1));
        assertThrows(
                IllegalArgumentException.class, () -> SourceChunkFactory.create(DOCUMENT, 1, 0, 6, 7, offset -> 1));
    }

    @Test
    void aSliceThatAddsOneCharacterIsAccepted() {
        assertEquals(
                "int a;\ni",
                SourceChunkFactory.create(DOCUMENT, 1, 0, 8, 7, offset -> 1).content());
    }
}
