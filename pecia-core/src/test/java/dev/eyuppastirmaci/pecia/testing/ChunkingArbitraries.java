package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * jqwik generators for chunking properties: plain text, Markdown, and source code built from fragments that stress
 * chunk boundaries, plus valid token budgets. Documents stay small so each property runs quickly; small budgets
 * still force splits within lines, words, and code points.
 */
public final class ChunkingArbitraries {

    /** The bundled MiniLM tokenizer reserves two special tokens and accepts 256 input tokens. */
    private static final int SPECIAL_TOKENS = 2;

    private static final int MAX_INPUT_TOKENS = 256;

    private static final List<String> WORDS = List.of(
            "payment",
            "validation",
            "JWT_SECRET",
            "İstanbul",
            "ödeme",
            "ığüşöç",
            "café",
            "é", // e followed by a combining acute accent
            "😀",
            "👍🏽",
            "漢字",
            "naïve",
            "x",
            "42");

    private static final List<String> LINE_ENDINGS = List.of("\n", "\r\n", "\r");

    private ChunkingArbitraries() {}

    /** Valid limits: {@code maxTokens} includes the special tokens, and overlap stays below the content budget. */
    public static Arbitrary<Budget> budgets() {
        return Arbitraries.integers()
                .between(SPECIAL_TOKENS + 1, MAX_INPUT_TOKENS)
                .flatMap(maxTokens -> Arbitraries.integers()
                        .between(0, maxTokens - SPECIAL_TOKENS - 1)
                        .map(overlapTokens -> new Budget(maxTokens, overlapTokens)));
    }

    /** Paragraphs and sentences with mixed line endings, blank lines, whitespace-only lines, and long words. */
    public static Arbitrary<String> plainText() {
        return lines(plainLine(), 0, 30);
    }

    /** Headings at several levels, list items, paragraphs, and closed or unclosed fences. */
    public static Arbitrary<String> markdown() {
        return lines(markdownLine(), 0, 25);
    }

    /** Indented code lines, blank lines between blocks, and long identifiers or literals. */
    public static Arbitrary<String> sourceCode() {
        return lines(sourceLine(), 0, 30);
    }

    /** Short documents of every type, each built from its type's generator. */
    public static Arbitrary<Document> documents() {
        return Arbitraries.of(DocumentType.PLAIN_TEXT, DocumentType.MARKDOWN, DocumentType.SOURCE_CODE)
                .flatMap(type -> {
                    Arbitrary<String> text =
                            switch (type) {
                                case MARKDOWN -> markdown();
                                case SOURCE_CODE -> sourceCode();
                                default -> plainText();
                            };
                    return text.map(content -> document(type, content));
                });
    }

    /**
     * Documents of every type, long enough to produce many chunks at the largest budgets, which short generated
     * documents reach only rarely.
     */
    public static Arbitrary<Document> longDocuments() {
        return Arbitraries.of(DocumentType.PLAIN_TEXT, DocumentType.MARKDOWN, DocumentType.SOURCE_CODE)
                .flatMap(type -> {
                    Arbitrary<String> line =
                            switch (type) {
                                case MARKDOWN -> markdownLine();
                                case SOURCE_CODE -> sourceLine();
                                default -> plainLine();
                            };
                    return lines(line, 150, 400).map(content -> document(type, content));
                });
    }

    /** Budgets from half the model input up to all of it, where only long documents need several chunks. */
    public static Arbitrary<Budget> largeBudgets() {
        return Arbitraries.integers()
                .between(MAX_INPUT_TOKENS / 2, MAX_INPUT_TOKENS)
                .flatMap(maxTokens -> Arbitraries.integers()
                        .between(0, maxTokens - SPECIAL_TOKENS - 1)
                        .map(overlapTokens -> new Budget(maxTokens, overlapTokens)));
    }

    /** A document of the given type and content, with a fixed project-relative path and its content hash. */
    public static Document document(DocumentType type, String content) {
        Path sourcePath =
                switch (type) {
                    case MARKDOWN -> Path.of("docs/sample.md");
                    case SOURCE_CODE -> Path.of("src/Sample.java");
                    case PLAIN_TEXT, STRUCTURED_TEXT -> Path.of("notes/sample.txt");
                };
        return new Document(sourcePath, type, content, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Arbitrary<String> plainLine() {
        return Arbitraries.oneOf(sentence(), whitespace(), longWord(), Arbitraries.just(""));
    }

    private static Arbitrary<String> markdownLine() {
        Arbitrary<String> heading = Combinators.combine(Arbitraries.integers().between(1, 6), sentence())
                .as((level, text) -> "#".repeat(level) + " " + text);
        Arbitrary<String> listItem = Combinators.combine(Arbitraries.of("- ", "* ", "1. ", "  - "), sentence())
                .as(String::concat);
        Arbitrary<String> fence = Combinators.combine(
                        Arbitraries.of("```", "```java", "~~~"),
                        codeLine().list().ofMaxSize(6),
                        Arbitraries.of(true, false))
                .as((open, body, closed) -> {
                    String joined = String.join("\n", body);
                    return open + "\n" + joined + (closed ? "\n" + open.substring(0, 3) : "");
                });
        return Arbitraries.frequencyOf(
                Tuple.of(3, sentence()),
                Tuple.of(2, heading),
                Tuple.of(2, listItem),
                Tuple.of(1, fence),
                Tuple.of(1, longWord()),
                Tuple.of(2, Arbitraries.just("")));
    }

    private static Arbitrary<String> sourceLine() {
        return Arbitraries.frequencyOf(
                Tuple.of(5, codeLine()), Tuple.of(2, Arbitraries.just("")), Tuple.of(1, longWord()));
    }

    private static Arbitrary<String> sentence() {
        return Combinators.combine(
                        Arbitraries.of(WORDS).list().ofMinSize(1).ofMaxSize(12),
                        Arbitraries.of(" ", "  ", "\t"),
                        Arbitraries.of("", ".", "!", "?", ","))
                .as((words, separator, end) -> String.join(separator, words) + end);
    }

    private static Arbitrary<String> codeLine() {
        Arbitrary<String> indent = Arbitraries.of("", "    ", "        ", "\t", "\t\t");
        Arbitrary<String> body = Arbitraries.of(
                "class Sample {",
                "}",
                "void validate() {",
                "String secret = \"JWT_SECRET\";",
                "return payment.isValid();",
                "// ödeme kontrolü 😀",
                "String şehir = \"İstanbul\"; // ılık",
                "if (a < b && c > d) {",
                "<Component prop={value} />");
        return Combinators.combine(indent, body).as(String::concat);
    }

    private static Arbitrary<String> whitespace() {
        return Arbitraries.of(" ", "\t", "  \t ", " ", "　");
    }

    /* A single token-dense word that exceeds small budgets and must be split inside the word. */
    private static Arbitrary<String> longWord() {
        return Combinators.combine(
                        Arbitraries.of("a", "ö", "😀", "漢", "é"),
                        Arbitraries.integers().between(20, 120))
                .as(String::repeat);
    }

    /* Joins lines with independently chosen line endings and an optional final line ending. */
    private static Arbitrary<String> lines(Arbitrary<String> line, int minLines, int maxLines) {
        Arbitrary<String> ending = Arbitraries.of(LINE_ENDINGS);
        return Combinators.combine(
                        Combinators.combine(line, ending)
                                .as(String::concat)
                                .list()
                                .ofMinSize(minLines)
                                .ofMaxSize(maxLines),
                        Arbitraries.of(true, false))
                .as((parts, trimFinalEnding) -> {
                    String text = String.join("", parts);
                    return trimFinalEnding ? text.replaceFirst("(\\r\\n|\\r|\\n)\\z", "") : text;
                });
    }

    /**
     * Valid chunking limits.
     *
     * @param maxTokens maximum model input tokens, including special tokens
     * @param overlapTokens maximum content tokens shared between adjacent chunks
     */
    public record Budget(int maxTokens, int overlapTokens) {}
}
