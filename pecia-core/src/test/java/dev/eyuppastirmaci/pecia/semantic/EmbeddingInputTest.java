package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each chunk is embedded after one line of context: its path and heading path, within a fixed token allowance. */
class EmbeddingInputTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void theContextLineNamesThePathAndHeadingsBeforeABlankLineAndTheContent() {
        assertEquals(
                "bridge/README.md > Bridge > Intent\n\nDecouple an abstraction from its implementation.",
                EmbeddingInput.of(
                        Path.of("bridge", "README.md"),
                        List.of("Bridge", "Intent"),
                        "Decouple an abstraction from its implementation."));
        assertEquals(
                "src/App.java\n\nclass App {}",
                EmbeddingInput.of(Path.of("src", "App.java"), List.of(), "class App {}"));
        assertEquals(
                "İçerik/ödeme.md > Başlık 😀\n\n",
                EmbeddingInput.of(Path.of("İçerik", "ödeme.md"), List.of("Başlık 😀"), ""));
    }

    @Test
    void longContextsLoseLeadingPartsFirstAndKeepTheMostSpecificHeading() {
        Path deep =
                Path.of("platform", "services", "payments", "gateway", "internal", "adapters", "legacy", "README.md");
        List<String> headings = List.of("Payment gateway", "Legacy adapters", "Retry policy", "Exponential backoff");

        String context = EmbeddingInput.context(deep, headings);

        assertTrue(tokenizer.count(context) <= EmbeddingInput.CONTEXT_TOKENS, context);
        assertTrue(context.startsWith("… "), context);
        assertTrue(context.endsWith(" > Exponential backoff"), context);
        // Whole parts are dropped, so what remains starts with a complete path segment or heading.
        List<String> parts = new ArrayList<>();
        deep.forEach(name -> parts.add(name.toString()));
        parts.addAll(headings);
        assertTrue(
                parts.stream()
                        .anyMatch(part ->
                                context.startsWith("… " + part + "/") || context.startsWith("… " + part + " > ")),
                context);
        assertTrue(tokenizer.count(String.join(" > ", headings) + deep) > EmbeddingInput.CONTEXT_TOKENS);
    }

    @Test
    void aSingleOverlongHeadingLosesLeadingCharacters() {
        String heading = "word ".repeat(80).strip();

        String context = EmbeddingInput.context(Path.of("notes.md"), List.of(heading));

        assertTrue(tokenizer.count(context) <= EmbeddingInput.CONTEXT_TOKENS, context);
        assertTrue(context.startsWith("… "), context);
        assertTrue(heading.endsWith(context.substring(2)), context);
    }

    @Test
    void theProfileRecordsThisInputAndTheAllowanceIsFixed() {
        assertEquals("path-heading-content-v1", EmbeddingInput.NAME);
        assertEquals(EmbeddingInput.NAME, EmbeddingProfile.minilm().input());
        assertEquals(32, EmbeddingInput.CONTEXT_TOKENS);
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> EmbeddingInput.of(Path.of("a.md"), List.of(), null));
        assertThrows(NullPointerException.class, () -> EmbeddingInput.of(Path.of("a.md"), null, "x"));
        assertThrows(NullPointerException.class, () -> EmbeddingInput.of(null, List.of(), "x"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingInput.of(Path.of("..", "a.md"), List.of(), "x"));
    }
}
