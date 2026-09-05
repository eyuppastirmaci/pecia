package dev.eyuppastirmaci.pecia.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.UNSUPPORTED_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentExtractionServiceTest {

    @TempDir
    Path root;

    @Test
    void loadsBytesThenDelegatesInterpretationToTheStrategy() throws Exception {
        Path file = Files.writeString(root.resolve("guide.md"), "# Başlık\n\nİçerik");
        DocumentExtractionService service = new DocumentExtractionService(
                new FileContentLoader(1024), new TextDocumentExtractor());

        Document document = service.extract(new ExtractionRequest(file, Path.of("docs/guide.md"), DocumentType.MARKDOWN));

        assertEquals(Path.of("docs/guide.md"), document.sourcePath());
        assertEquals(DocumentType.MARKDOWN, document.type());
        assertEquals("# Başlık\n\nİçerik", document.content());
    }

    @Test
    void checksStrategySupportBeforeReadingTheFile() {
        Path missing = root.resolve("missing.md");
        DocumentExtractor rejectingStrategy = new DocumentExtractor() {

            @Override
            public boolean supports(DocumentType type) {

                return false;
            }

            @Override
            public Document extract(ExtractionRequest request, FileContent content) {

                throw new AssertionError("Unsupported strategy must not be called");
            }
        };
        DocumentExtractionService service = new DocumentExtractionService(
                new FileContentLoader(1024), rejectingStrategy);

        ExtractionException failure = assertThrows(ExtractionException.class,
                () -> service.extract(new ExtractionRequest(missing, Path.of("missing.md"), DocumentType.MARKDOWN)));

        assertEquals(UNSUPPORTED_TYPE, failure.reason());
    }

    @Test
    void validatesRequestsBeforeFilesystemWork() throws IOException {
        Path file = Files.writeString(root.resolve("notes.txt"), "content");

        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionRequest(Path.of("relative.txt"), Path.of("notes.txt"), DocumentType.PLAIN_TEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new ExtractionRequest(file, Path.of("../notes.txt"), DocumentType.PLAIN_TEXT));
    }
}
