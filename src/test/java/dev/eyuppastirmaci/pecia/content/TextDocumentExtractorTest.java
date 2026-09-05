package dev.eyuppastirmaci.pecia.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.BINARY_CONTENT;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.INVALID_UTF8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDocumentExtractorTest {

    private static final Path FILE = Path.of("C:/fixtures/notes.txt").toAbsolutePath().normalize();
    private final TextDocumentExtractor extractor = new TextDocumentExtractor();

    @ParameterizedTest
    @EnumSource(DocumentType.class)
    void supportsEveryV01TextFamily(DocumentType type) {
        assertTrue(extractor.supports(type));
    }

    @Test
    void extractsUtf8AndPreservesUnicodeAndLineEndings() throws Exception {
        String text = "Pecia\r\nTürkçe içerik\nこんにちは";
        ExtractionRequest request = request(DocumentType.PLAIN_TEXT);
        Document document = extractor.extract(request, content(text.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Path.of("notes.txt"), document.sourcePath());
        assertEquals(DocumentType.PLAIN_TEXT, document.type());
        assertEquals(text, document.content());
        assertEquals(ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)), document.contentHash());
    }

    @Test
    void removesOnlyAnInitialUtf8Bom() throws Exception {
        byte[] text = "\uFEFFheading\uFEFFbody".getBytes(StandardCharsets.UTF_8);
        Document document = extractor.extract(request(DocumentType.MARKDOWN), content(text));

        assertEquals("heading\uFEFFbody", document.content());
        assertEquals(ContentHash.sha256(text), document.contentHash());
    }

    @Test
    void acceptsEmptyAndBomOnlyFiles() throws Exception {
        Document empty = extractor.extract(request(DocumentType.PLAIN_TEXT), content(new byte[0]));
        Document bomOnly = extractor.extract(request(DocumentType.PLAIN_TEXT),
                content("\uFEFF".getBytes(StandardCharsets.UTF_8)));

        assertEquals("", empty.content());
        assertEquals("", bomOnly.content());
    }

    @Test
    void rejectsMalformedUtf8AndUtf16() {
        ExtractionException malformed = assertThrows(ExtractionException.class,
                () -> extractor.extract(request(DocumentType.PLAIN_TEXT), content(new byte[]{(byte) 0xC3, 0x28})));
        byte[] utf16 = "hello".getBytes(StandardCharsets.UTF_16);
        ExtractionException encodedDifferently = assertThrows(ExtractionException.class,
                () -> extractor.extract(request(DocumentType.PLAIN_TEXT), content(utf16)));

        assertEquals(INVALID_UTF8, malformed.reason());
        assertEquals(INVALID_UTF8, encodedDifferently.reason());
    }

    @Test
    void rejectsNulAndRepeatedBinaryControls() {
        ExtractionException nul = assertThrows(ExtractionException.class,
                () -> extractor.extract(request(DocumentType.PLAIN_TEXT), content("hello\0world".getBytes(StandardCharsets.UTF_8))));
        ExtractionException controls = assertThrows(ExtractionException.class,
                () -> extractor.extract(request(DocumentType.PLAIN_TEXT), content(new byte[]{1, 2, 3, 4, 'a'})));

        assertEquals(BINARY_CONTENT, nul.reason());
        assertEquals(BINARY_CONTENT, controls.reason());
    }

    @Test
    void doesNotReadTheFilesystemAndRequiresMatchingInputs() throws Exception {
        Document document = extractor.extract(request(DocumentType.PLAIN_TEXT), content("memory only".getBytes(StandardCharsets.UTF_8)));

        assertEquals("memory only", document.content());

        FileContent other = new FileContent(Path.of("C:/fixtures/other.txt").toAbsolutePath().normalize(), new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(request(DocumentType.PLAIN_TEXT), other));
    }

    private static ExtractionRequest request(DocumentType type) {

        return new ExtractionRequest(FILE, Path.of("notes.txt"), type);
    }

    private static FileContent content(byte[] bytes) {

        return new FileContent(FILE, bytes);
    }
}
