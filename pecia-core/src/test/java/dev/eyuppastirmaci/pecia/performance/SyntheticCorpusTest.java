package dev.eyuppastirmaci.pecia.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The corpus is reproducible, mixes the file types it promises, and favours common words. */
class SyntheticCorpusTest {

    @TempDir
    Path directory;

    @Test
    void theSameSeedAndSizeWriteTheSameBytes() throws IOException {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Path other = Files.createDirectory(directory.resolve("other"));

        new SyntheticCorpus(7).write(first, 60);
        new SyntheticCorpus(7).write(second, 60);
        new SyntheticCorpus(8).write(other, 60);

        assertEquals(contents(first), contents(second));
        assertNotEquals(contents(first), contents(other));
    }

    @Test
    void filesAreSpreadOverTypesAndDirectories() throws IOException {
        List<Path> files = new SyntheticCorpus(7).write(directory, 200);

        Map<String, Long> byType = files.stream()
                .collect(Collectors.groupingBy(
                        path -> path.getFileName().toString().replaceAll(".*\\.", ""), Collectors.counting()));
        assertEquals(Map.of("md", 120L, "java", 50L, "txt", 30L), byType);
        assertEquals(100, files.stream().map(path -> path.getName(0)).distinct().count());
        assertTrue(Files.readString(directory.resolve(files.get(12))).contains("final class File12 {"));
    }

    @Test
    void commonWordsAreSampledFarMoreOftenThanRareOnes() {
        SyntheticCorpus corpus = new SyntheticCorpus(7);
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(1);

        for (int sample = 0; sample < 100_000; sample++) {
            counts.merge(corpus.word(random), 1, Integer::sum);
        }

        List<String> words = corpus.vocabulary();
        assertEquals(SyntheticCorpus.VOCABULARY_SIZE, words.stream().distinct().count());
        // Under 1/rank weights the most common word is about a thousand times as frequent as the thousandth.
        int top = counts.getOrDefault(words.get(0), 0);
        int thousandth = counts.getOrDefault(words.get(999), 1);
        assertTrue(top > 100 * thousandth, top + " vs " + thousandth);
    }

    @Test
    void anEditChangesOnlyTheFileItNames() throws IOException {
        SyntheticCorpus corpus = new SyntheticCorpus(7);
        List<Path> files = corpus.write(directory, 20);
        Map<Path, String> before = contents(directory);

        corpus.edit(directory, files.get(3), 0);

        Map<Path, String> after = contents(directory);
        assertTrue(after.get(files.get(3)).startsWith(before.get(files.get(3))));
        assertNotEquals(before.get(files.get(3)), after.get(files.get(3)));
        after.remove(files.get(3));
        before.remove(files.get(3));
        assertEquals(before, after);
    }

    private static Map<Path, String> contents(Path root) throws IOException {
        Map<Path, String> contents = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                contents.put(root.relativize(path), Files.readString(path));
            }
        }

        return contents;
    }
}
