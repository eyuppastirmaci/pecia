package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchHitTest {
    private static final SearchScore SCORE = new SearchScore(-0.25, SearchScore.Kind.SQLITE_BM25);
    private static final Path PATH = Path.of("docs/Ödeme rehberi.md");
    private static final LineRange LINES = new LineRange(118, 161);

    @Test
    void preservesChunkIdentityLocationContextAndLiteralSnippet() {
        ChunkMetadata metadata = new ChunkMetadata(List.of("Guide", "Payments"), Map.of("language", "tr"));
        String snippet = "  ödeme <T> & kayıt\r\n …";
        SearchHit hit = new SearchHit(42, 3, PATH, DocumentType.MARKDOWN, LINES, metadata, SCORE, snippet);

        assertEquals(42, hit.chunkId());
        assertEquals(3, hit.chunkIndex());
        assertEquals(PATH, hit.sourcePath());
        assertEquals(DocumentType.MARKDOWN, hit.documentType());
        assertEquals(new LineRange(118, 161), hit.sourceLocation());
        assertEquals(metadata, hit.metadata());
        assertEquals(SCORE, hit.score());
        assertEquals(snippet, hit.snippet());
    }

    @Test
    void metadataRetainsImmutableOrderedHeadingsAndAttributes() {
        List<String> headings = new ArrayList<>(List.of("Payments", "Setup"));
        Map<String, String> attributes = new HashMap<>(Map.of("language", "java"));
        SearchHit hit = hit(new ChunkMetadata(headings, attributes), "excerpt");

        headings.clear();
        attributes.clear();

        assertEquals(List.of("Payments", "Setup"), hit.metadata().headingPath());
        assertEquals(Map.of("language", "java"), hit.metadata().attributes());
        assertThrows(UnsupportedOperationException.class, () -> hit.metadata().headingPath().add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> hit.metadata().attributes().put("new", "value"));
    }

    @Test
    void permitsMissingHeadingsAndAnEmptyExcerpt() {
        SearchHit hit = hit(ChunkMetadata.empty(), "");

        assertEquals(List.of(), hit.metadata().headingPath());
        assertEquals("", hit.snippet());
    }

    @Test
    void reusesExtensibleSourceLocations() {
        record PageLocation(int page) implements SourceLocation { }
        SourceLocation location = new PageLocation(2);
        SearchHit hit = new SearchHit(1, 0, PATH, DocumentType.PLAIN_TEXT, location,
                ChunkMetadata.empty(), SCORE, "excerpt");

        assertEquals(location, hit.sourceLocation());
    }

    @Test
    void rejectsInvalidDatabaseIdentityAndChunkPosition() {
        for (long id : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SearchHit(id, 0, PATH, DocumentType.MARKDOWN, LINES, ChunkMetadata.empty(), SCORE, "text"));
        }

        assertThrows(IllegalArgumentException.class,
                () -> new SearchHit(1, -1, PATH, DocumentType.MARKDOWN, LINES, ChunkMetadata.empty(), SCORE, "text"));
    }

    @Test
    void rejectsNonProjectRelativeOrUnnormalizedPaths() {
        for (Path path : List.of(Path.of(""), Path.of("."), Path.of(".."), Path.of("../outside.md"),
                Path.of("docs/../notes.md"), PATH.toAbsolutePath())) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SearchHit(1, 0, path, DocumentType.MARKDOWN, LINES, ChunkMetadata.empty(), SCORE, "text"));
        }
    }

    @Test
    void requiresAllReferenceComponents() {
        assertThrows(NullPointerException.class,
                () -> new SearchHit(1, 0, null, DocumentType.MARKDOWN, LINES, ChunkMetadata.empty(), SCORE, "text"));
        assertThrows(NullPointerException.class,
                () -> new SearchHit(1, 0, PATH, null, LINES, ChunkMetadata.empty(), SCORE, "text"));
        assertThrows(NullPointerException.class,
                () -> new SearchHit(1, 0, PATH, DocumentType.MARKDOWN, null, ChunkMetadata.empty(), SCORE, "text"));
        assertThrows(NullPointerException.class,
                () -> new SearchHit(1, 0, PATH, DocumentType.MARKDOWN, LINES, null, SCORE, "text"));
        assertThrows(NullPointerException.class,
                () -> new SearchHit(1, 0, PATH, DocumentType.MARKDOWN, LINES, ChunkMetadata.empty(), null, "text"));
        assertThrows(NullPointerException.class, () -> hit(ChunkMetadata.empty(), null));
    }

    @Test
    void validatesSnippetCodePointBudgetIncludingEllipsisWithoutTruncation() {
        String boundary = "\ud83d\ude80".repeat(399) + "…";

        assertEquals(boundary, hit(ChunkMetadata.empty(), boundary).snippet());
        assertThrows(IllegalArgumentException.class, () -> hit(ChunkMetadata.empty(), boundary + "x"));
        assertEquals("x".repeat(400), hit(ChunkMetadata.empty(), "x".repeat(400)).snippet());
        assertThrows(IllegalArgumentException.class, () -> hit(ChunkMetadata.empty(), "x".repeat(401)));
    }

    private static SearchHit hit(ChunkMetadata metadata, String snippet) {
        return new SearchHit(1, 0, PATH, DocumentType.MARKDOWN, LINES, metadata, SCORE, snippet);
    }
}
