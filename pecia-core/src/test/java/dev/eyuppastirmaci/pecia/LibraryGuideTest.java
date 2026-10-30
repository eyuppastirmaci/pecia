package dev.eyuppastirmaci.pecia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexException;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryResult;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the example in {@code docs/library.md} and keeps the guide's copy identical to the code run here. */
class LibraryGuideTest {

    private static final Path GUIDE = Path.of("../docs/library.md");
    private static final Path THIS_SOURCE = Path.of("src/test/java/dev/eyuppastirmaci/pecia/LibraryGuideTest.java");
    private static final String START = "// guide example start";
    private static final String END = "// guide example end";

    @TempDir
    Path project;

    @Test
    void theGuidesExampleIndexesAndFindsAFile() throws Exception {
        Files.writeString(project.resolve("refunds.md"), "# Refund policy\n\nRefunds go back to the original card.\n");

        List<SearchHit> hits = indexAndSearch(project);

        assertEquals(Path.of("refunds.md"), hits.getFirst().sourcePath());
    }

    @Test
    void theGuideShowsExactlyTheTestedExample() throws IOException {
        Matcher block = Pattern.compile("```java\\n(.*?)```", Pattern.DOTALL)
                .matcher(Files.readString(GUIDE, StandardCharsets.UTF_8));
        assertTrue(block.find(), "No java block in " + GUIDE);

        String source = Files.readString(THIS_SOURCE, StandardCharsets.UTF_8);
        // The last markers are the ones in the method; the first are these constants.
        String example = source.substring(source.lastIndexOf(START) + START.length(), source.lastIndexOf(END));

        assertEquals(dedent(example), dedent(block.group(1)));
    }

    /** The guide's example, as a method so that it can run against a temporary project. */
    private static List<SearchHit> indexAndSearch(Path project) throws IOException, IndexException, QueryException {
        // guide example start
        PeciaConfigLoader configLoader = new PeciaConfigLoader(new PeciaConfigParser());
        new PeciaConfigFile().writeDefault(project); // like `pecia init`; keeps an existing .pecia.toml

        IndexResult indexed = new IndexService(configLoader).index(project);
        System.out.println(indexed.status() + ": " + indexed.indexedFiles() + " files indexed");

        QueryResult found = new QueryService(configLoader).query(project, new SearchRequest("refund policy", 5));
        for (SearchHit hit : found.hits()) {
            System.out.println(hit.sourcePath() + " (" + hit.score().value() + "): " + hit.snippet());
        }
        // guide example end

        return found.hits();
    }

    /** Removes the surrounding blank lines and the indentation all lines share, keeping nested indentation. */
    private static String dedent(String text) {
        List<String> lines =
                text.stripTrailing().lines().dropWhile(String::isBlank).toList();
        int shared = lines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length())
                .min()
                .orElse(0);
        return String.join(
                "\n",
                lines.stream()
                        .map(line -> line.isBlank() ? "" : line.substring(shared))
                        .toList());
    }
}
