package dev.eyuppastirmaci.pecia.chunking.source;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunker;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContent;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class SourceCodeChunkingAcceptanceTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @ParameterizedTest
    @MethodSource("samples")
    void extractsAndChunksRepresentativeFamiliesAcrossInputVariants(SourceSample sample) throws Exception {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            for (boolean bom : List.of(false, true)) {
                for (boolean finalNewline : List.of(false, true)) {
                    for (int overlap : List.of(0, 3)) {
                        String text = sample.text().replace("\n", newline) + (finalNewline ? newline : "");
                        byte[] bytes = ((bom ? "\uFEFF" : "") + text).getBytes(StandardCharsets.UTF_8);
                        Document document = extract(sample.filename(), bytes);
                        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 24, overlap);
                        DocumentChunker chunker = factory.getChunker(document);
                        List<Chunk> chunks = chunker.chunk(document);
                        assertInstanceOf(SourceCodeChunker.class, chunker);
                        assertSame(factory.getChunker(DocumentType.SOURCE_CODE), chunker);
                        assertEquals(DocumentType.SOURCE_CODE, document.type());
                        assertEquals(text, document.content());
                        assertEquals(ContentHash.sha256(bytes), document.contentHash());
                        assertEquals(chunks, chunker.chunk(document));
                        assertTrue(chunks.size() > 1);
                        verifyCoverage(document, chunks, 24, overlap);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"panel.jsx", "panel.tsx"})
    void treatsMarkdownLikeSyntaxAndIncompleteCodeAsRawSource(String filename) throws Exception {
        String text = "const markdown = `\n# Not a heading\n```java\n" + "int value = 1;\n".repeat(40)
                + "`;\nconst panel = <Panel title=\"## Literal\">\n"
                + "  <span>{value}</span>\n".repeat(40) + "  {/* deliberately unfinished";
        Document document = extract(filename, text.getBytes(StandardCharsets.UTF_8));
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 16, 3);
        List<Chunk> chunks = factory.getChunker(document).chunk(document);

        assertTrue(chunks.size() > 1);
        verifyCoverage(document, chunks, 16, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bundle.js", "generated.sql", "minified.css"})
    void splitsLongUnbrokenLinesAndPreservesAdjacentMixedIndentation(String filename) throws Exception {
        String line = switch (filename) {
            case "bundle.js" -> "const values=[" + "1,2,3,".repeat(1500) + "0];";
            case "generated.sql" -> "SELECT " + "1+2+3+".repeat(1500) + "0;";
            case "minified.css" -> ".item{" + "margin:0;padding:0;".repeat(600) + "}";
            default -> throw new AssertionError("Unexpected fixture: " + filename);
        };

        Document document = extract(filename, ("\t" + line + "\r\n \t\r\n    tail\n\tend\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));

        for (int overlap : List.of(0, 8)) {
            DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 32, overlap);
            List<Chunk> chunks = factory.getChunker(document).chunk(document);
            assertTrue(chunks.size() > 100);
            verifyCoverage(document, chunks, 32, overlap);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\r\n", "\u00a0\u2003"})
    void acceptsEmptyBomOnlyAndWhitespaceOnlySourceFiles(String text) throws Exception {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 8, 2);

        for (boolean bom : List.of(false, true)) {
            byte[] bytes = ((bom ? "\uFEFF" : "") + text).getBytes(StandardCharsets.UTF_8);
            Document document = extract("empty.py", bytes);
            assertEquals(text, document.content());
            assertEquals(ContentHash.sha256(bytes), document.contentHash());
            assertEquals(List.of(), factory.getChunker(document).chunk(document));
        }
    }

    @Test
    void retainsCoverageAtTinyBudgetsAndMaximumAllowedOverlap() throws Exception {
        String text = "\t# İÇERİK 😀\r\n\n" + "value+=1;".repeat(20)
                + "\r\n \t\n" + "<Node>{value}</Node>".repeat(15) + "\r\n\r\n\u00a0";
        Document document = extract("tiny.tsx", text.getBytes(StandardCharsets.UTF_8));

        for (int limit : List.of(3, 4, 6, 12)) {
            int overlap = limit - tokenizer.identity().specialTokenCount() - 1;
            DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, limit, overlap);
            List<Chunk> chunks = factory.getChunker(document).chunk(document);
            assertTrue(chunks.size() > 1);
            verifyCoverage(document, chunks, limit, overlap);
        }
    }

    @Test
    void preservesSelectionAndResultsAcrossDefaultLocales() throws Exception {
        String text = "# İÇERİK\ndef calculate():\n" + "\tprint('İstanbul 😀')\n".repeat(30);
        Locale previous = Locale.getDefault();
        Document baseline = extract("İÇERİK.PYI", text.getBytes(StandardCharsets.UTF_8));
        List<Chunk> expected = DocumentChunkerFactory.create(tokenizer, 16, 3).getChunker(baseline).chunk(baseline);

        try {
            for (Locale locale : List.of(Locale.forLanguageTag("tr-TR"), Locale.ENGLISH, Locale.ROOT)) {
                Locale.setDefault(locale);
                Document document = extract("İÇERİK.PYI", text.getBytes(StandardCharsets.UTF_8));
                DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 16, 3);
                assertEquals(expected, factory.getChunker(document).chunk(document));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void sharedFactoryDoesNotLeakOversizedUnitOrOverlapStateAcrossConcurrentDocuments() throws Exception {
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer, 16, 3);
        List<Callable<List<Chunk>>> tasks = new ArrayList<>();
        List<List<Chunk>> expected = new ArrayList<>();
        List<Document> documents = new ArrayList<>();
        List<SourceSample> concurrentSamples = new ArrayList<>(samples());
        concurrentSamples.add(new SourceSample("bundle.js", "const value=" + "1+".repeat(1000) + "0;"));
        concurrentSamples.add(new SourceSample("generated.sql", "SELECT " + "2+".repeat(800) + "0;"));

        for (SourceSample sample : concurrentSamples) {
            Document document = extract(sample.filename(), sample.text().getBytes(StandardCharsets.UTF_8));
            documents.add(document);
            DocumentChunker chunker = factory.getChunker(document);
            assertSame(factory.getChunker(DocumentType.SOURCE_CODE), chunker);
            expected.add(chunker.chunk(document));
            tasks.add(() -> chunker.chunk(document));
        }

        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int run = 0; run < 3; run++) {
                var results = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);

                for (int index = 0; index < results.size(); index++) {
                    assertFalse(results.get(index).isCancelled());
                    List<Chunk> chunks = results.get(index).get();
                    assertEquals(expected.get(index), chunks);
                    verifyCoverage(documents.get(index), chunks, 16, 3);
                }
            }
        }
    }

    private static List<SourceSample> samples() {
        return List.of(
                sample("Example.java", "class Example {\n    String build() {\n        var result = new StringBuilder();\n",
                        "        result.append(\"İçerik 😀\");\n", "        return result.toString();\n    }\n}"),
                sample("main.py", "def build(items):\n    output = []\n",
                        "    for item in items:\n        output.append(str(item))\n", "    return output"),
                sample("main.go", "package main\n\nfunc build() string {\n\tvalue := \"\"\n",
                        "\tvalue += \"İçerik 😀\"\n", "\treturn value\n}"),
                sample("main.rs", "fn build() -> String {\n    let mut value = String::new();\n",
                        "    value.push_str(\"İçerik 😀\");\n", "    value\n}"),
                sample("Main.kt", "fun build(): String {\n    val result = StringBuilder()\n",
                        "    result.append(\"İçerik 😀\")\n", "    return result.toString()\n}"),
                sample("panel.jsx", "export const Panel = ({label}) => (\n  <ul>\n",
                        "    <li>{label} {/* # Not a heading */}</li>\n", "  </ul>\n);"),
                sample("panel.tsx", "type Props = {label: string};\nexport const Panel = ({label}: Props) => (\n  <section>\n",
                        "    <p title=\"## Literal\">{label}</p>\n", "  </section>\n);"),
                sample("run.sh", "#!/bin/sh\n# Print the message\n",
                        "printf '%s\\n' 'İçerik 😀'\n", "exit 0"),
                sample("query.sql", "BEGIN;\n",
                        "INSERT INTO messages (body) VALUES ('İçerik 😀');\n", "COMMIT;"),
                sample("style.css", ".items {\n", "    padding: 1rem;\n    color: #123456;\n", "}"),
                sample("Dockerfile", "FROM alpine:3.20\nWORKDIR /app\n",
                        "RUN printf '%s\\n' 'İçerik 😀' >> /app/messages\n", "CMD [\"cat\", \"/app/messages\"]"),
                sample("Makefile", ".PHONY: all\nall:\n", "\t@printf '%s\\n' 'İçerik 😀'\n", "\t@echo done"),
                sample("script.ps1", "$messages = @()\n", "$messages += 'İçerik 😀'\n", "$messages | Write-Output")
        );
    }

    private static SourceSample sample(String filename, String prefix, String body, String suffix) {
        return new SourceSample(filename, prefix + body.repeat(24) + suffix);
    }

    private static Document extract(String filename, byte[] bytes) throws Exception {
        Path source = Path.of("src", filename);
        Path file = source.toAbsolutePath().normalize();
        DocumentType type = new FileTypeDetector().detect(source).orElseThrow();

        return new TextDocumentExtractor().extract(new ExtractionRequest(file, source, type), new FileContent(file, bytes));
    }

    /* Reconstructs only newly covered source while checking original identity, safe offsets, line ranges, and exact token limits. */
    private void verifyCoverage(Document document, List<Chunk> chunks, int limit, int overlap) {
        String text = document.content();
        StringBuilder reconstructed = new StringBuilder();
        int covered = 0;
        int previousStart = -1;

        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            int start = Integer.parseInt(chunk.metadata().attributes().get("startOffset"));
            int end = Integer.parseInt(chunk.metadata().attributes().get("endOffset"));
            assertTrue(start > previousStart && start <= covered && end > covered && end <= text.length());
            assertEquals(text.substring(start, end), chunk.content());
            assertEquals(index, chunk.index());
            assertEquals(document.sourcePath(), chunk.sourcePath());
            assertEquals(document.type(), chunk.documentType());
            assertEquals(List.of(), chunk.metadata().headingPath());
            assertEquals(Set.of("startOffset", "endOffset"), chunk.metadata().attributes().keySet());
            assertEquals(new LineRange(lineAt(text, start), lineAt(text, end - 1)), chunk.sourceLocation());
            assertTrue(chunk.content().codePoints().anyMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)));
            assertTrue(tokenizer.countModelInput(chunk.content()) <= limit);
            assertTrue(tokenizer.count(text.substring(start, covered)) <= overlap);

            if (overlap == 0) {
                assertEquals(covered, start);
            }

            for (int offset : new int[] {start, end}) {
                if (offset > 0 && offset < text.length()) {
                    assertFalse(Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)));
                    assertFalse(text.charAt(offset - 1) == '\r' && text.charAt(offset) == '\n');
                }
            }

            reconstructed.append(text, covered, end);
            covered = end;
            previousStart = start;
        }

        assertEquals(text.length(), covered);
        assertEquals(text, reconstructed.toString());
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), reconstructed.toString().getBytes(StandardCharsets.UTF_8));
    }

    /* Counts only complete line terminators preceding the requested source character. */
    private static int lineAt(String text, int offset) {
        var terminators = Pattern.compile("\r\n|\r|\n").matcher(text);
        int line = 1;

        while (terminators.find() && terminators.end() <= offset) {
            line++;
        }

        return line;
    }

    private record SourceSample(String filename, String text) {
    }
}
