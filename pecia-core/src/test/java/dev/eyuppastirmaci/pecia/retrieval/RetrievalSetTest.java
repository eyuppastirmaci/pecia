package dev.eyuppastirmaci.pecia.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.retrieval.RetrievalQuery.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The committed labels follow the rules, and the loader rejects labels that break them. */
class RetrievalSetTest {

    // One query of each kind, all valid; the cases below change one thing.
    private static final String VALID = """
            [[query]]
            id = "identifier"
            kind = "identifier"
            text = "RetryPolicy"
            answers = ["src/RetryPolicy.java"]
            related = ["docs/retries.md"]
            answer_first_in = ["lexical", "hybrid"]

            [[query]]
            id = "keyword"
            kind = "keyword"
            text = "retry limit"
            answers = ["docs/retries.md"]

            [[query]]
            id = "paraphrase"
            kind = "paraphrase"
            text = "how often is a payment tried again"
            answers = ["docs/retries.md"]

            [[query]]
            id = "mixed"
            kind = "mixed"
            text = "RetryPolicy limit"
            answers = ["src/RetryPolicy.java"]

            [[query]]
            id = "turkish"
            kind = "turkish"
            text = "yeniden deneme"
            answers = ["docs/retries.md"]
            """;

    @TempDir
    Path root;

    @Test
    void theCommittedSetCoversEveryKindWithEnoughQueries() throws IOException {
        RetrievalSet set = RetrievalSet.committed();

        Map<Kind, Long> perKind =
                set.queries().stream().collect(Collectors.groupingBy(RetrievalQuery::kind, Collectors.counting()));
        assertEquals(Set.of(Kind.values()), perKind.keySet());
        perKind.forEach((kind, count) -> assertTrue(count >= 6, kind + " has only " + count + " queries"));
        assertTrue(set.files().size() >= 50, set.files().size() + " corpus files");
    }

    @Test
    void validLabelsLoadWithTheirGrades() throws IOException {
        RetrievalSet set = load(VALID);

        Map<String, RetrievalQuery> byId =
                set.queries().stream().collect(Collectors.toMap(RetrievalQuery::id, Function.identity()));
        RetrievalQuery identifier = byId.get("identifier");
        assertEquals(List.of("docs/retries.md", "src/RetryPolicy.java"), set.files());
        assertEquals(Kind.IDENTIFIER, identifier.kind());
        assertEquals(2, identifier.grade("src/RetryPolicy.java"));
        assertEquals(1, identifier.grade("docs/retries.md"));
        assertEquals(0, identifier.grade("README.md"));
        assertEquals(Set.of(SearchMode.LEXICAL, SearchMode.HYBRID), identifier.answerFirstIn());
        assertEquals(Set.of(), byId.get("keyword").answerFirstIn());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "unknown key | answers = [\"docs/retries.md\"] | anwsers = [\"docs/retries.md\"] | unknown keys",
                "missing file | answers = [\"docs/retries.md\"] | answers = [\"docs/missing.md\"] | not in the corpus",
                "no answers | answers = [\"docs/retries.md\"] | answers = [] | no answers",
                "unknown kind | kind = \"keyword\" | kind = \"keywords\" | unknown kind",
                "auto mode | answer_first_in = [\"lexical\", \"hybrid\"] | answer_first_in = [\"auto\"] | unknown mode",
                "repeated mode | answer_first_in = [\"lexical\", \"hybrid\"] | answer_first_in = [\"lexical\", \"lexical\"]"
                        + " | repeats the mode",
                "answer also related | related = [\"docs/retries.md\"] | related = [\"src/RetryPolicy.java\"]"
                        + " | repeats a path",
                "repeated id | id = \"mixed\" | id = \"keyword\" | repeats an id",
                "repeated text | text = \"RetryPolicy limit\" | text = \"retry limit\" | repeats the text",
                "missing kind | kind = \"turkish\" | kind = \"keyword\" | Every kind needs queries",
            })
    void labelsThatBreakARuleAreRejected(String name, String original, String replacement, String problem)
            throws IOException {
        String broken = replaceFirst(VALID, original, replacement);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(broken));

        assertTrue(error.getMessage().contains(problem), error.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"docs/diagram.png, unsupported type", "node_modules/lib.md, skips", ".hidden/notes.md, skips"})
    void corpusFilesThatIndexingSkipsAreRejected(String file, String problem) throws IOException {
        Path extra = root.resolve("corpus").resolve(file);
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "content\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(VALID));

        assertTrue(error.getMessage().contains(problem), error.getMessage());
    }

    private RetrievalSet load(String queries) throws IOException {
        Path corpus = root.resolve("corpus");
        Files.createDirectories(corpus.resolve("src"));
        Files.createDirectories(corpus.resolve("docs"));
        Files.writeString(corpus.resolve("src/RetryPolicy.java"), "final class RetryPolicy {}\n");
        Files.writeString(corpus.resolve("docs/retries.md"), "# Retries\n");
        Files.writeString(root.resolve("queries.toml"), queries);
        return RetrievalSet.load(root);
    }

    /** Replaces the first occurrence, failing when the text does not contain it, so a case cannot test nothing. */
    private static String replaceFirst(String text, String original, String replacement) {
        int index = text.indexOf(original);
        assertTrue(index >= 0, "Not in the valid labels: " + original);
        return text.substring(0, index) + replacement + text.substring(index + original.length());
    }
}
