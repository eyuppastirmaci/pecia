package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.MatchRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.testing.PropertySeeds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Every range covers a word of the query, and every word of the query in the snippet is covered, under the folding of
 * SQLite's {@code unicode61 remove_diacritics 2} tokenizer.
 */
class MatchRangePropertiesTest {

    // Spellings that fold together, and words that only look alike.
    private static final List<String> WORDS = List.of(
            "refund",
            "Refund",
            "REFUND",
            "refunds",
            "café",
            "Cafe",
            "naïve",
            "naive",
            "über",
            "Uber",
            "şehir",
            "Şehir",
            "İstanbul",
            "istanbul",
            "garçon",
            "five",
            "days",
            "😀",
            "pay");
    private static final List<String> SEPARATORS = List.of(" ", ", ", ". ", " - ", "\n", " 😀 ");

    @Property(seed = PropertySeeds.FIXED, tries = 100)
    void rangesCoverExactlyTheQueryWords(@ForAll("documents") List<String> words, @ForAll("picks") List<Integer> picks)
            throws Exception {
        String content = join(words, picks);
        List<String> query = picks.stream()
                .map(pick -> words.get(pick % words.size()))
                .filter(word -> !word.equals("😀"))
                .distinct()
                .toList();

        if (query.isEmpty()) {
            return;
        }

        Path root = Files.createTempDirectory("pecia-match-");

        try (SqliteStorage storage = SqliteStorage.open(root.resolve("index.db"), root)) {
            insert(storage, content);
            List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest(String.join(" ", query)));

            assertEquals(1, hits.size(), content);
            assertCovered(
                    hits.getFirst(),
                    query.stream().map(MatchRangePropertiesTest::fold).collect(Collectors.toSet()));
        } finally {
            delete(root);
        }
    }

    @Provide
    Arbitrary<List<String>> documents() {
        return Arbitraries.of(WORDS).list().ofMinSize(1).ofMaxSize(30);
    }

    @Provide
    Arbitrary<List<Integer>> picks() {
        return Arbitraries.integers().between(0, 1000).list().ofMinSize(1).ofMaxSize(3);
    }

    /** Checks that each range is one query word and each query word of the snippet lies in a range. */
    private static void assertCovered(SearchHit hit, Set<String> query) {
        int[] codePoints = hit.snippet().codePoints().toArray();
        int start = -1;

        for (int position = 0; position <= codePoints.length; position++) {
            boolean wordCharacter = position < codePoints.length && Character.isLetterOrDigit(codePoints[position]);

            if (wordCharacter && start < 0) {
                start = position;
            } else if (!wordCharacter && start >= 0) {
                String word = new String(codePoints, start, position - start);
                boolean inRange = covered(hit.matches(), start, position);
                assertEquals(query.contains(fold(word)), inRange, word + " in " + hit.snippet() + " " + hit.matches());
                start = -1;
            }
        }

        for (MatchRange match : hit.matches()) {
            String text = new String(codePoints, match.start(), match.end() - match.start());
            assertTrue(query.contains(fold(text)), text + " in " + hit.snippet());
        }
    }

    private static boolean covered(List<MatchRange> matches, int start, int end) {
        return matches.stream().anyMatch(match -> match.start() <= start && end <= match.end());
    }

    /** Folds case and removes diacritics as the tokenizer does for these words. */
    private static String fold(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> words, List<Integer> picks) {
        StringBuilder content = new StringBuilder();

        for (int index = 0; index < words.size(); index++) {
            if (index > 0) {
                content.append(SEPARATORS.get((index + picks.getFirst()) % SEPARATORS.size()));
            }

            content.append(words.get(index));
        }

        return content.toString();
    }

    private static void insert(SqliteStorage storage, String content) throws Exception {
        Path source = Path.of("a.md");
        var file = storage.files()
                .save(source, DocumentType.MARKDOWN, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
        storage.chunks()
                .insert(
                        file.id(),
                        new Chunk(
                                source,
                                DocumentType.MARKDOWN,
                                0,
                                content,
                                new LineRange(1, 1),
                                new ChunkMetadata(List.of(), Map.of())));
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
