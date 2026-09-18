package dev.eyuppastirmaci.pecia.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.chunking.source.SourceCodeChunker;
import dev.eyuppastirmaci.pecia.chunking.text.TextChunker;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceChunkerRoutingTest {

    private final DocumentChunker textChunker = unusedStrategy("text");
    private final DocumentChunker markdownChunker = unusedStrategy("markdown");
    private final DocumentChunker sourceChunker = unusedStrategy("source");
    private final DocumentChunker javaChunker = unusedStrategy("java");
    private final DocumentChunker goChunker = unusedStrategy("go");

    @Test
    void selectsAndReusesRegisteredSourceStrategiesWithoutRunningThem() {
        DocumentChunkerFactory factory = factory(Map.of(".JAVA", javaChunker, "go", goChunker));
        Document java = document("src/Example.java", DocumentType.SOURCE_CODE);
        Document go = document("src/main.GO", DocumentType.SOURCE_CODE);

        assertSame(javaChunker, factory.getChunker(java));
        assertSame(goChunker, factory.getChunker(go));
        assertSame(javaChunker, factory.getChunker(java));
        assertSame(sourceChunker, factory.getChunker(DocumentType.SOURCE_CODE));
    }

    @Test
    void usesOnlyTheFinalExtensionOfTheFilename() {
        DocumentChunkerFactory factory = factory(Map.of("java", javaChunker, ".go", goChunker));

        assertSame(javaChunker, factory.getChunker(document("src/Example.test.JAVA", DocumentType.SOURCE_CODE)));
        assertSame(goChunker, factory.getChunker(document("src/Example.java.go", DocumentType.SOURCE_CODE)));
        assertSame(javaChunker, factory.getChunker(document("src/.hidden.java", DocumentType.SOURCE_CODE)));
        assertSame(sourceChunker, factory.getChunker(document("folder.java/Makefile", DocumentType.SOURCE_CODE)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "main.py",
                "Dockerfile",
                "Containerfile",
                "Makefile",
                "Jenkinsfile",
                ".java",
                "file.",
                "code.java.txt"
            })
    void fallsBackForUnregisteredOrMissingExtensions(String filename) {
        DocumentChunkerFactory factory = factory(Map.of("java", javaChunker));

        assertSame(sourceChunker, factory.getChunker(document(filename, DocumentType.SOURCE_CODE)));
    }

    @Test
    void preservesTheClassifiedFamilyInsteadOfReclassifyingByExtension() {
        DocumentChunkerFactory factory = factory(Map.of("java", javaChunker, "md", javaChunker, "json", goChunker));

        assertSame(textChunker, factory.getChunker(document("example.java", DocumentType.PLAIN_TEXT)));
        assertSame(textChunker, factory.getChunker(document("example.json", DocumentType.STRUCTURED_TEXT)));
        assertSame(markdownChunker, factory.getChunker(document("example.md", DocumentType.MARKDOWN)));
        assertSame(markdownChunker, factory.getChunker(document("example.java", DocumentType.MARKDOWN)));
    }

    @Test
    void preservesDefaultRoutingWhenNoExtensionsAreRegistered() {
        DocumentChunkerFactory factory = new DocumentChunkerFactory(textChunker, markdownChunker, sourceChunker);

        for (DocumentType type : DocumentType.values()) {
            assertSame(factory.getChunker(type), factory.getChunker(document("example.java", type)));
        }
    }

    @Test
    void snapshotsTheRegistryInsteadOfKeepingMutableCallerState() {
        Map<String, DocumentChunker> registry = new HashMap<>();
        registry.put("java", javaChunker);
        DocumentChunkerFactory factory = factory(registry);
        registry.put("java", goChunker);
        registry.put("go", goChunker);
        registry.clear();

        assertSame(javaChunker, factory.getChunker(document("Example.java", DocumentType.SOURCE_CODE)));
        assertSame(sourceChunker, factory.getChunker(document("main.go", DocumentType.SOURCE_CODE)));
    }

    @Test
    void normalizesKeysAndFilenamesIndependentlyOfTheDefaultLocale() {
        Locale previous = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            DocumentChunkerFactory factory = factory(Map.of(".PYI", javaChunker));
            assertSame(javaChunker, factory.getChunker(document("module.pyi", DocumentType.SOURCE_CODE)));
            assertSame(javaChunker, factory.getChunker(document("module.PYI", DocumentType.SOURCE_CODE)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", ".", "..java", "*.java", "java ", " java", "java/go", "java\\go", "tar.gz", "java?", ".*"})
    void rejectsBlankPathsGlobsAndCompoundRegistryKeys(String extension) {
        assertThrows(IllegalArgumentException.class, () -> factory(Map.of(extension, javaChunker)));
    }

    @Test
    void rejectsDuplicateNormalizedExtensionsEvenWhenTheyShareTheSameStrategy() {
        assertThrows(IllegalArgumentException.class, () -> factory(Map.of("java", javaChunker, ".JAVA", goChunker)));
        assertThrows(IllegalArgumentException.class, () -> factory(Map.of(".java", javaChunker, "JAVA", javaChunker)));
    }

    @Test
    void rejectsNullDocumentsRegistriesKeysAndStrategies() {
        assertThrows(NullPointerException.class, () -> factory(Map.of()).getChunker((Document) null));
        assertThrows(NullPointerException.class, () -> factory(null));
        Map<String, DocumentChunker> nullKey = new HashMap<>();
        nullKey.put(null, javaChunker);
        Map<String, DocumentChunker> nullValue = new HashMap<>();
        nullValue.put("java", null);
        assertThrows(NullPointerException.class, () -> factory(nullKey));
        assertThrows(NullPointerException.class, () -> factory(nullValue));
    }

    @Test
    void builtInFactoryCombinesOverridesWithTheExistingFallbacks() {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(
                MiniLmTokenizer.bundled(), 256, 32, Map.of(".java", javaChunker, ".go", goChunker));

        assertSame(javaChunker, factory.getChunker(document("Example.java", DocumentType.SOURCE_CODE)));
        assertSame(goChunker, factory.getChunker(document("main.go", DocumentType.SOURCE_CODE)));
        assertInstanceOf(MarkdownChunker.class, factory.getChunker(document("guide.md", DocumentType.MARKDOWN)));
        assertInstanceOf(SourceCodeChunker.class, factory.getChunker(document("main.py", DocumentType.SOURCE_CODE)));
        assertSame(
                factory.getChunker(DocumentType.SOURCE_CODE),
                factory.getChunker(document("Dockerfile", DocumentType.SOURCE_CODE)));
        assertInstanceOf(TextChunker.class, factory.getChunker(document("notes.txt", DocumentType.PLAIN_TEXT)));
        assertInstanceOf(TextChunker.class, factory.getChunker(document("config.json", DocumentType.STRUCTURED_TEXT)));
    }

    @Test
    void builtInFactoryExecutesExplicitOverridesWithoutChangingOtherFamilyDefaults() {
        List<Document> received = new ArrayList<>();
        DocumentChunker override = document -> {
            received.add(document);

            return List.of();
        };
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(
                tokenizer, 6, 1, Map.of(".JAVA", override, "md", override, "json", override));
        Document java = document("Example.java", DocumentType.SOURCE_CODE);
        Document python = document("main.py", DocumentType.SOURCE_CODE);
        DocumentChunker selected = factory.getChunker(java);
        assertSame(override, selected);
        assertEquals(List.of(), received);
        assertEquals(List.of(), selected.chunk(java));
        assertEquals(List.of(java), received);
        assertSame(factory.getChunker(DocumentType.SOURCE_CODE), factory.getChunker(python));
        assertEquals(
                new SourceCodeChunker(tokenizer, 6, 1).chunk(python),
                factory.getChunker(python).chunk(python));
        assertSame(
                factory.getChunker(DocumentType.MARKDOWN),
                factory.getChunker(document("guide.md", DocumentType.MARKDOWN)));
        assertSame(
                factory.getChunker(DocumentType.STRUCTURED_TEXT),
                factory.getChunker(document("config.json", DocumentType.STRUCTURED_TEXT)));
        assertSame(
                factory.getChunker(DocumentType.PLAIN_TEXT),
                factory.getChunker(document("Example.java", DocumentType.PLAIN_TEXT)));
        assertEquals(List.of(java), received);
    }

    @Test
    void callersCanExecuteTheSelectedStrategyThroughTheInterface() {
        List<Document> received = new ArrayList<>();
        DocumentChunker override = document -> {
            received.add(document);

            return List.of();
        };
        Document document = document("Example.java", DocumentType.SOURCE_CODE);
        DocumentChunker chunker = factory(Map.of("java", override)).getChunker(document);
        assertEquals(List.of(), received);
        assertEquals(List.of(), chunker.chunk(document));
        assertEquals(List.of(document), received);
    }

    private DocumentChunkerFactory factory(Map<String, DocumentChunker> registry) {
        return new DocumentChunkerFactory(textChunker, markdownChunker, sourceChunker, registry);
    }

    private static Document document(String filename, DocumentType type) {
        String content = "source content";

        return new Document(
                Path.of(filename), type, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static DocumentChunker unusedStrategy(String name) {
        return document -> {
            throw new AssertionError("Strategy selection must not execute chunking: " + name);
        };
    }
}
