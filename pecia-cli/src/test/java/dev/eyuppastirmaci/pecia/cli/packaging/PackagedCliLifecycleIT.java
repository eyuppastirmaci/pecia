package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertIndexSummary;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.hitLocations;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.content.LineRange;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliLifecycleIT {

    private static final String CALENDAR_TEXT = """
        needle january one two
        needle february one two
        needle march one two
        needle april one two
        needle may one two
        needle june one two
        needle july one two
        needle august one two
        needle september one two
        needle october one two
        needle november one two
        needle december one two
        """;

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
    }

    @Test
    void lexicalLifecycleKeepsCliQueriesCurrentAcrossUnchangedEditAndDeleteRuns() throws Exception {
        cli.enableOffline();
        cli.runReadOnly(project, "--help");
        Files.writeString(project.resolve(".pecia.toml"), """
            [index]
            include = ['**/*.md', '**/*.java', '**/*.txt']
            """);
        Files.createDirectory(project.resolve("docs"));
        Files.createDirectory(project.resolve("src"));
        Path guide = project.resolve("docs/Ödeme.md");
        Files.writeString(guide, "# Ödeme rehberi\n\noriginalneedle İstanbul café 😀\n");
        Files.writeString(project.resolve("src/Auth.java"), "String JWT_SECRET;\n");
        Files.writeString(project.resolve("a-weak.txt"), "ranking filler filler filler\n");
        Files.writeString(project.resolve("z-strong.txt"), "ranking ranking ranking filler\n");
        Files.writeString(project.resolve("empty.txt"), "");
        Path database = project.resolve(".pecia/index.db");

        assertIndexSummary(cli.run(project, "index"), database, 5, 4);
        Result original = cli.runReadOnly(project, "query", "originalneedle");
        assertEquals(List.of("docs/Ödeme.md:1-3"), hitLocations(original));
        assertTrue(original.out().contains("  heading: Ödeme rehberi\n"), original.out());
        assertTrue(original.out().contains("  originalneedle İstanbul café 😀\n"), original.out());
        if (launcherPreserves("İstanbul café")) {
            assertEquals(
                    List.of("docs/Ödeme.md:1-3"), hitLocations(cli.runReadOnly(project, "query", "İstanbul café")));
        }
        Result source = cli.runReadOnly(project, "query", "JWT_SECRET");
        assertEquals(List.of("src/Auth.java:1-1"), hitLocations(source));
        assertTrue(source.out().contains("  String JWT_SECRET;\n"), source.out());
        Result ranking = cli.runReadOnly(project, "query", "ranking");
        assertRanking(ranking);
        Result limited = cli.runReadOnly(project, "query", "ranking", "--limit", "1");
        assertEquals(List.of("z-strong.txt:1-1"), hitLocations(limited));
        assertEquals(List.of(bm25Scores(ranking).getFirst()), bm25Scores(limited));

        assertIndexSummary(cli.run(project, "index"), database, 5, 0, 0);
        assertEquals(
                original.out(),
                cli.runReadOnly(project, "query", "originalneedle").out());
        assertEquals(ranking.out(), cli.runReadOnly(project, "query", "ranking").out());

        Files.writeString(guide, "# Ödeme rehberi\n\nreplacementneedle İstanbul café 😀\n");
        assertIndexSummary(cli.run(project, "index"), database, 5, 1, 1);
        Result replacement = cli.runReadOnly(project, "query", "replacementneedle");
        assertEquals(List.of("docs/Ödeme.md:1-3"), hitLocations(replacement));
        assertTrue(replacement.out().contains("  heading: Ödeme rehberi\n"), replacement.out());
        assertTrue(replacement.out().contains("  replacementneedle İstanbul café 😀\n"), replacement.out());
        assertEquals(
                "No results.\n",
                cli.runReadOnly(project, "query", "originalneedle").out());
        assertRanking(cli.runReadOnly(project, "query", "ranking"));

        Files.delete(guide);
        assertIndexSummary(cli.run(project, "index"), database, 4, 0, 1, 0);
        assertEquals(
                "No results.\n",
                cli.runReadOnly(project, "query", "replacementneedle").out());
        if (launcherPreserves("İstanbul café")) {
            assertEquals(
                    "No results.\n",
                    cli.runReadOnly(project, "query", "İstanbul café").out());
        }
        assertEquals(List.of("src/Auth.java:1-1"), hitLocations(cli.runReadOnly(project, "query", "JWT_SECRET")));
        assertRanking(cli.runReadOnly(project, "query", "ranking"));
        assertIndexSummary(cli.run(project, "index"), database, 4, 0, 0);
        Result preview = cli.runReadOnly(project, "index", "--dry-run");
        assertTrue(
                preview.out().contains("files: 0 new, 0 changed, 0 rechunked, 4 unchanged, 0 rejected, 0 removed\n"),
                preview.out());
        assertFalse(preview.out().contains("Ödeme.md"), preview.out());
    }

    @Test
    void configuredRechunkingUpdatesCliRangesAndThenReturnsToUnchangedIndexing() throws Exception {
        cli.enableOffline();
        Path docs = Files.createDirectory(project.resolve("docs"));
        Files.writeString(docs.resolve("calendar.txt"), CALENDAR_TEXT);
        Path database = project.resolve("state/search.db");
        List<LineRange> large = List.of(new LineRange(1, 6), new LineRange(7, 12));
        List<LineRange> small =
                List.of(new LineRange(1, 3), new LineRange(4, 6), new LineRange(7, 9), new LineRange(10, 12));
        writeCalendarConfig(26, 1);

        Result freshPreview = cli.runReadOnly(project, "index", "--dry-run");
        assertTrue(
                freshPreview.out().contains("index: " + database + " (none yet; would be created)\n"),
                freshPreview.out());
        assertTrue(freshPreview.out().contains("\nnew:\n  docs/calendar.txt (~"), freshPreview.out());
        assertFalse(Files.exists(project.resolve("state")));
        assertIndexSummary(cli.run(project, "index"), database, 1, 2);
        Result original = assertCalendarQuery(docs, large);
        assertIndexSummary(cli.run(project, "index"), database, 1, 0, 0);
        assertEquals(original.out(), assertCalendarQuery(docs, large).out());

        writeCalendarConfig(14, 1);
        Result changedPreview = cli.runReadOnly(project, "index", "--dry-run");
        assertTrue(changedPreview.out().contains("\nrechunked:\n  docs/calendar.txt (~"), changedPreview.out());
        // Preview and query must leave the old chunks intact until a real index run.
        assertEquals(original.out(), assertCalendarQuery(docs, large).out());
        // Only the chunk settings changed, so the file is counted as rechunked.
        assertIndexSummary(cli.run(project, "index"), database, 1, 1, 1, 0, 4);
        Result smaller = assertCalendarQuery(docs, small);
        Result july = cli.runReadOnly(project, "query", "july", "--root", docs.toString());
        assertEquals(List.of("docs/calendar.txt:7-9"), hitLocations(july));
        assertIndexSummary(cli.run(project, "index"), database, 1, 0, 0);
        assertEquals(smaller.out(), assertCalendarQuery(docs, small).out());

        writeCalendarConfig(26, 1);
        assertIndexSummary(cli.run(project, "index"), database, 1, 1, 1, 0, 2);
        Result larger = assertCalendarQuery(docs, large);
        assertEquals(
                List.of("docs/calendar.txt:7-12"),
                hitLocations(cli.runReadOnly(project, "query", "july", "--root", docs.toString())));
        assertIndexSummary(cli.run(project, "index"), database, 1, 0, 0);
        assertEquals(larger.out(), assertCalendarQuery(docs, large).out());

        writeCalendarConfig(26, 4);
        byte[] beforeConcurrencyChange = Files.readAllBytes(database);
        assertIndexSummary(cli.run(project, "index"), database, 1, 0, 0);
        assertArrayEquals(beforeConcurrencyChange, Files.readAllBytes(database));
        assertEquals(larger.out(), assertCalendarQuery(docs, large).out());
        cli.runReadOnly(project, "index", "--dry-run");
        assertEquals(CALENDAR_TEXT, Files.readString(docs.resolve("calendar.txt")));
        assertFalse(Files.exists(project.resolve(".pecia")));
        assertFalse(Files.exists(docs.resolve("state")));
    }

    private Result assertCalendarQuery(Path root, List<LineRange> ranges) throws Exception {
        Result result = cli.runReadOnly(project, "query", "needle", "--root", root.toString(), "--limit", "100");
        assertEquals(
                ranges.stream()
                        .map(range -> "docs/calendar.txt:" + range.startLine() + "-" + range.endLine())
                        .toList(),
                hitLocations(result));
        List<String> blocks = List.of(result.out().stripTrailing().split("\n\n"));
        assertEquals(ranges.size(), blocks.size());
        assertEquals(ranges.size(), bm25Scores(result).size());
        for (int index = 0; index < ranges.size(); index++) {
            LineRange range = ranges.get(index);
            List<String> rendered = blocks.get(index).lines().toList();
            assertEquals(
                    CALENDAR_TEXT.lines().toList().subList(range.startLine() - 1, range.endLine()).stream()
                            .map(line -> "  " + line)
                            .toList(),
                    rendered.subList(2, rendered.size()));
        }
        return result;
    }

    private void writeCalendarConfig(int maxTokens, int concurrency) throws IOException {
        Files.writeString(project.resolve(".pecia.toml"), """
            [index]
            include = ['docs/*.txt']
            [chunk]
            max_tokens = %d
            overlap_tokens = 0
            [embed]
            concurrency = %d
            [store]
            path = 'state/search.db'
            """.formatted(maxTokens, concurrency));
    }

    private static List<Double> bm25Scores(Result result) {
        List<Double> scores = result.out()
                .lines()
                .filter(line -> line.startsWith("  BM25: "))
                .map(line -> Double.parseDouble(line.substring("  BM25: ".length())))
                .toList();
        assertTrue(scores.stream().allMatch(Double::isFinite), result.out());
        return scores;
    }

    private static void assertRanking(Result result) {
        assertEquals(List.of("z-strong.txt:1-1", "a-weak.txt:1-1"), hitLocations(result));
        List<Double> scores = bm25Scores(result);
        assertEquals(2, scores.size());
        assertTrue(scores.getFirst() < scores.getLast(), result.out());
    }

    /**
     * Reports whether a child JVM receives this argument intact. The Windows Java launcher decodes the command line
     * through the ANSI code page, so characters outside it, such as 'İ' under cp1252, arrive as '?'. Platform
     * launchers must remove this limitation; until then, such assertions run only where the argument survives.
     */
    private static boolean launcherPreserves(String argument) {
        return Charset.forName(System.getProperty("sun.jnu.encoding"))
                .newEncoder()
                .canEncode(argument);
    }
}
