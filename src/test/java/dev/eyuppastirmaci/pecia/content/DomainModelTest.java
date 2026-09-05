package dev.eyuppastirmaci.pecia.content;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainModelTest {

    private static final ContentHash HASH = ContentHash.sha256(new byte[0]);

    @Test
    void documentAcceptsEmptyContentAndPreservesItsSourceIdentity() {
        Document document = new Document(Path.of("docs/empty.md"), DocumentType.MARKDOWN, "", HASH);

        assertEquals(Path.of("docs/empty.md"), document.sourcePath());
        assertEquals(DocumentType.MARKDOWN, document.type());
        assertEquals("", document.content());
        assertEquals(HASH, document.contentHash());
    }

    @Test
    void documentRejectsInvalidState() {
        assertThrows(NullPointerException.class,
                () -> new Document(null, DocumentType.PLAIN_TEXT, "content", HASH));
        assertThrows(NullPointerException.class,
                () -> new Document(Path.of("notes.txt"), null, "content", HASH));
        assertThrows(NullPointerException.class,
                () -> new Document(Path.of("notes.txt"), DocumentType.PLAIN_TEXT, null, HASH));
        assertThrows(NullPointerException.class,
                () -> new Document(Path.of("notes.txt"), DocumentType.PLAIN_TEXT, "content", null));
    }

    @Test
    void sourcePathsMustBeNormalizedProjectRelativePaths() {
        List<Path> invalidPaths = List.of(
                Path.of(""),
                Path.of("."),
                Path.of(".."),
                Path.of("../outside.md"),
                Path.of("docs/../notes.md"),
                Path.of("notes.md").toAbsolutePath()
        );

        for (Path path : invalidPaths) {

            assertThrows(IllegalArgumentException.class,
                    () -> new Document(path, DocumentType.PLAIN_TEXT, "content", HASH));
            assertThrows(IllegalArgumentException.class,
                    () -> chunk(path, 0, "content", new LineRange(1, 1), ChunkMetadata.empty()));
        }
    }

    @Test
    void lineRangeIsOneBasedAndInclusive() {
        assertEquals(new LineRange(4, 9), new LineRange(4, 9));
        assertThrows(IllegalArgumentException.class, () -> new LineRange(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LineRange(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new LineRange(5, 4));
    }

    @Test
    void sourceLocationCanGrowBeyondLineRanges() {
        record PageRange(int firstPage, int lastPage) implements SourceLocation { }

        SourceLocation location = new PageRange(2, 3);
        Chunk chunk = chunk(Path.of("future.pdf"), 0, "future content", location, ChunkMetadata.empty());

        assertEquals(location, chunk.sourceLocation());
    }

    @Test
    void chunkRequiresACompleteNonBlankValue() {
        Chunk chunk = chunk(Path.of("src/Main.java"), 0, "class Main {}", new LineRange(1, 1),
                ChunkMetadata.empty());

        assertEquals(Path.of("src/Main.java"), chunk.sourcePath());
        assertEquals(DocumentType.SOURCE_CODE, chunk.documentType());
        assertEquals(0, chunk.index());
        assertEquals(new LineRange(1, 1), chunk.sourceLocation());

        assertThrows(IllegalArgumentException.class,
                () -> chunk(Path.of("notes.txt"), -1, "content", new LineRange(1, 1), ChunkMetadata.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> chunk(Path.of("notes.txt"), 0, "   ", new LineRange(1, 1), ChunkMetadata.empty()));
        assertThrows(NullPointerException.class,
                () -> chunk(Path.of("notes.txt"), 0, null, new LineRange(1, 1), ChunkMetadata.empty()));
        assertThrows(NullPointerException.class,
                () -> chunk(Path.of("notes.txt"), 0, "content", null, ChunkMetadata.empty()));
        assertThrows(NullPointerException.class,
                () -> chunk(Path.of("notes.txt"), 0, "content", new LineRange(1, 1), null));
    }

    @Test
    void metadataMakesDefensiveCopies() {
        List<String> headings = new ArrayList<>(List.of("Guide", "Install"));
        Map<String, String> attributes = new HashMap<>(Map.of("language", "java"));
        attributes.put("category", "source");
        ChunkMetadata metadata = new ChunkMetadata(headings, attributes);

        headings.add("Changed");
        attributes.put("generated", "true");

        assertEquals(List.of("Guide", "Install"), metadata.headingPath());
        assertEquals(Map.of("category", "source", "language", "java"), metadata.attributes());
        assertEquals(List.of("category", "language"), new ArrayList<>(metadata.attributes().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> metadata.headingPath().add("Changed"));
        assertThrows(UnsupportedOperationException.class, () -> metadata.attributes().put("new", "value"));
        assertEquals(ChunkMetadata.empty(), new ChunkMetadata(List.of(), Map.of()));
    }

    @Test
    void metadataRejectsInvalidEntries() {
        assertThrows(NullPointerException.class, () -> new ChunkMetadata(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new ChunkMetadata(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new ChunkMetadata(List.of(" "), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ChunkMetadata(List.of(), Map.of(" ", "value")));

        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("language", null);

        assertThrows(IllegalArgumentException.class, () -> new ChunkMetadata(List.of(), nullValue));
    }

    private static Chunk chunk(Path path, int index, String content, SourceLocation location,
            ChunkMetadata metadata) {

        return new Chunk(path, DocumentType.SOURCE_CODE, index, content, location, metadata);
    }
}
