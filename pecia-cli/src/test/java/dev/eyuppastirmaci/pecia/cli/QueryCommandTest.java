package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryOptions;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class QueryCommandTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void useCanonicalRoot() throws IOException {
        // Resolved contexts use on-disk spellings; Windows runners may expose temp directories by short names.
        root = root.toRealPath();
    }

    @Test
    void printsOrderedPathsFullLineRangesRawScoresAndOptionalHeadings() throws Exception {
        Files.writeString(root.resolve("plain.txt"), "needle İstanbul 😀\r\nsecond line\r\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nneedle İstanbul 😀\n");
        IndexResult result = new IndexService(loader).index(root);
        List<SearchHit> expected = new QueryService(loader).search(root, new SearchRequest("needle"));
        byte[] before = Files.readAllBytes(result.context().databasePath());

        assertEquals(0, run("needle", "--root", root.toString()));
        assertEquals("", err.toString());

        String rendered = out.toString();
        int previous = -1;

        for (SearchHit hit : expected) {
            int position = rendered.indexOf(hit.sourcePath().toString() + ":");
            assertTrue(position > previous);
            previous = position;
            assertTrue(rendered.contains("  BM25: " + hit.score().value()));
        }

        assertTrue(rendered.contains("plain.txt:1-2"));
        assertTrue(rendered.contains("guide.md:1-3"));
        assertEquals(1, rendered.split("  heading: Guide", -1).length - 1);
        assertTrue(rendered.contains("  needle İstanbul 😀"));
        assertTrue(rendered.contains("  second line"));
        assertArrayEquals(before, Files.readAllBytes(result.context().databasePath()));

        out.getBuffer().setLength(0);

        assertEquals(0, run("needle", "--root", root.toString(), "--limit", "1"));
        assertTrue(out.toString().startsWith(expected.getFirst().sourcePath().toString() + ":"));
        assertEquals(1, out.toString().split("  BM25:", -1).length - 1);
    }

    @Test
    void semanticQueriesPrintCosineSimilaritiesInRankedOrder() throws Exception {
        String refunds = "Refunds are issued to the original card within five days.";
        Files.writeString(root.resolve("refunds.txt"), refunds + "\n");
        Files.writeString(root.resolve("shipping.txt"), "Parcels leave the warehouse every weekday morning.\n");
        Files.writeString(root.resolve("accounts.txt"), "Users reset forgotten passwords from the sign-in page.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
        QueryService semantic = QueryService.withModelLookup(loader, () -> Optional.of(TinyModel.cached()));
        List<SearchHit> expected = semantic.query(
                        root, new SearchRequest(refunds), QueryOptions.mode(SearchMode.SEMANTIC))
                .hits();

        assertEquals(0, run(semantic, refunds, "--root", root.toString(), "--mode", "semantic"));

        assertEquals("", err.toString());
        assertTrue(out.toString().startsWith("refunds.txt:1-1" + System.lineSeparator()), out.toString());
        assertEquals(
                expected.stream().map(hit -> "  cosine: " + hit.score().value()).toList(),
                out.toString()
                        .lines()
                        .filter(line -> line.startsWith("  cosine: "))
                        .toList());
        assertEquals(3, expected.size());
        assertFalse(out.toString().contains("BM25"));
    }

    @Test
    void hybridQueriesPrintFusedScoresWithTheSidesThatRankedEachHit() throws Exception {
        String refunds = "Refunds are issued to the original card within five days.";
        Files.writeString(root.resolve("refunds.txt"), refunds + "\n");
        Files.writeString(root.resolve("shipping.txt"), "Parcels leave the warehouse every weekday morning.\n");
        Files.writeString(root.resolve("accounts.txt"), "Users reset forgotten passwords from the sign-in page.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));
        // Indexed lexically afterwards, so this file has no embedding and only the lexical side can rank it.
        Files.writeString(root.resolve("notes.txt"), refunds + " Ask the bank if it takes longer.\n");
        new IndexService(loader).index(root);
        QueryService hybrid = QueryService.withModelLookup(loader, () -> Optional.of(TinyModel.cached()));
        List<SearchHit> expected = hybrid.query(root, new SearchRequest(refunds), QueryOptions.mode(SearchMode.HYBRID))
                .hits();

        assertEquals(0, run(hybrid, refunds, "--root", root.toString(), "--mode", "hybrid"));

        assertEquals("", err.toString());
        List<String> scores = out.toString()
                .lines()
                .filter(line -> line.startsWith("  RRF: "))
                .toList();
        assertEquals(4, expected.size());
        assertEquals(
                List.of(
                        "  RRF: " + expected.get(0).score().value() + " (lexical #1, semantic #1)",
                        "  RRF: " + expected.get(1).score().value() + " (semantic #2)",
                        "  RRF: " + expected.get(2).score().value() + " (lexical #2)",
                        "  RRF: " + expected.get(3).score().value() + " (semantic #3)"),
                scores);
        // Rank 2 on either side scores the same; the tie breaks by path.
        assertEquals(Path.of("accounts.txt"), expected.get(1).sourcePath());
        assertEquals(Path.of("notes.txt"), expected.get(2).sourcePath());
        assertTrue(out.toString().startsWith("refunds.txt:1-1" + System.lineSeparator()), out.toString());
    }

    @Test
    void aSemanticQueryWithoutTheCachedModelSaysSoAndNeverDownloadsIt() throws Exception {
        Files.writeString(root.resolve("refunds.txt"), "Refunds are issued to the original card.\n");
        IndexService.withModelProvider(loader, listener -> TinyModel.cached()).index(root, IndexOptions.semantic(true));

        // The test task points PECIA_CACHE_DIR at an empty build directory, which never holds the model.
        assertEquals(1, run("refunds", "--root", root.toString(), "--mode", "semantic"));

        assertEquals("", out.toString());
        assertTrue(err.toString().startsWith("pecia query: MODEL_NOT_CACHED: The semantic model "), err.toString());
    }

    @Test
    void childRootResolvesCustomStoreAndPrintsProjectRelativeStoredContent() throws Exception {
        Files.writeString(
                root.resolve(".pecia.toml"),
                "[index]\ninclude = ['docs/*.txt']\n[store]\npath = 'state/custom index.db'\n");
        Path child = Files.createDirectories(root.resolve("docs/nested"));
        Path source = Files.writeString(root.resolve("docs/guide.txt"), "needle İstanbul 😀\nsecond line\n");
        IndexResult indexed = new IndexService(loader).index(root);
        Path database = root.resolve("state/custom index.db");
        assertEquals(database, indexed.context().databasePath());
        assertEquals(1, indexed.indexedFiles());
        byte[] before = Files.readAllBytes(database);
        Files.delete(source);

        assertEquals(0, run("needle", "--root", child.toString()));

        assertEquals("", err.toString());
        assertTrue(out.toString().startsWith("docs/guide.txt:1-2" + System.lineSeparator()));
        assertTrue(out.toString().contains("  needle İstanbul 😀" + System.lineSeparator()));
        assertTrue(out.toString().contains("  second line" + System.lineSeparator()));
        assertEquals(1, out.toString().split("  BM25:", -1).length - 1);
        assertArrayEquals(before, Files.readAllBytes(database));
        assertFalse(Files.exists(root.resolve(".pecia")));
        assertFalse(Files.exists(child.resolve(".pecia")));
        assertFalse(Files.exists(child.resolve("state")));
    }

    @Test
    void defaultArgumentsFollowTheCoreContract() {
        QueryCommand command = new QueryCommand(new QueryService(loader));
        new CommandLine(command).parseArgs("needle");

        assertEquals("needle", command.text);
        assertEquals(Path.of("."), command.root);
        assertEquals(SearchRequest.DEFAULT_LIMIT, command.limit);
        assertThrows(NullPointerException.class, () -> new QueryCommand(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "!!!"})
    void noMatchesAreSuccessful(String query) throws Exception {
        new IndexService(loader).index(root);

        assertEquals(0, run(query, "--root", root.toString()));
        assertEquals("No results." + System.lineSeparator(), out.toString());
        assertEquals("", err.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "word "})
    void invalidQueryAndLimitsDoNotCreateAnIndex(String query) {
        String actual = query.equals("word ") ? query.repeat(65) : query;

        assertEquals(1, run(actual, "--root", root.toString()));
        assertTrue(err.toString().startsWith("pecia query:"));
        assertEquals("", out.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void outOfRangeLimitsAreCoreValidationErrors(String limit) {
        assertEquals(1, run("needle", "--root", root.toString(), "--limit", limit));
        assertTrue(err.toString().contains("limit must be between"));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void missingArgumentAndNonNumericLimitAreUsageErrors() {
        assertEquals(2, run());
        assertTrue(err.toString().contains("Missing required parameter"));

        err.getBuffer().setLength(0);

        assertEquals(2, run("needle", "--root", root.toString(), "--limit", "many"));
        assertTrue(err.toString().contains("Invalid value"));
        assertEquals("", out.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    // The lexical index has no embeddings, so semantic queries fail before they look for the model.
    @ParameterizedTest(name = "file {0}, flag {1} -> exit {2} {3}")
    @CsvSource(
            nullValues = "none",
            value = {
                "none, none, 0, none",
                "none, lexical, 0, none",
                "none, auto, 0, none",
                "none, semantic, 1, EMBEDDINGS_MISSING",
                "none, hybrid, 1, EMBEDDINGS_MISSING",
                "semantic, none, 1, EMBEDDINGS_MISSING",
                "semantic, lexical, 0, none",
                "semantic, auto, 0, none",
                "lexical, semantic, 1, EMBEDDINGS_MISSING",
                "hybrid, none, 1, EMBEDDINGS_MISSING",
                "hybrid, auto, 0, none"
            })
    void modeFlagOverridesTheConfiguredMode(String file, String flag, int exitCode, String reason) throws Exception {
        if (file != null) {
            Files.writeString(root.resolve(".pecia.toml"), "[search]\nmode = \"" + file + "\"\n");
        }

        Files.writeString(root.resolve("guide.md"), "# Guide\n\nmodeneedle\n");
        new IndexService(loader).index(root);

        int actual = flag == null
                ? run("modeneedle", "--root", root.toString())
                : run("modeneedle", "--root", root.toString(), "--mode", flag);

        assertEquals(exitCode, actual);

        if (exitCode == 0) {
            assertTrue(out.toString().startsWith("guide.md:"), out.toString());
            assertEquals("", err.toString());
        } else {
            assertEquals("", out.toString());
            assertTrue(err.toString().startsWith("pecia query: " + reason + ": "), err.toString());
        }
    }

    @Test
    void missingEmbeddingsPrintWarningsThenTheErrorWithoutAnIndexHint() throws Exception {
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nneedle\n");
        new IndexService(loader).index(root);

        assertEquals(1, run("needle", "--root", root.toString(), "--mode", "hybrid"));

        assertEquals(
                List.of(
                        typoWarning(config),
                        "pecia query: EMBEDDINGS_MISSING: The index has no embeddings for semantic search; index the"
                                + " project semantically first"),
                err.toString().lines().toList());
        assertEquals("", out.toString());
    }

    @Test
    void unknownModeIsAUsageErrorNamingTheValidModes() {
        assertEquals(2, run("needle", "--root", root.toString(), "--mode", "Hybrid"));
        assertTrue(
                err.toString()
                        .contains("Invalid value for option '--mode': expected one of lexical, semantic, hybrid, auto"
                                + " but was 'Hybrid'"),
                err.toString());
        assertEquals("", out.toString());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void missingIndexProvidesAnIndexCommandWithoutCreatingAnything() throws Exception {
        assertEquals(1, run("needle", "--root", root.toString()));
        assertTrue(err.toString().contains("INDEX_NOT_FOUND"));
        // Tests run from class directories, so the hint names the JAR the build produces.
        assertTrue(err.toString().contains("Run: java -jar 'pecia-cli/build/libs/pecia.jar'"), err.toString());
        assertTrue(err.toString().contains(" index '" + root + "'"));
        assertFalse(err.toString().contains("\tat "));
        assertEquals("", out.toString());

        try (Stream<Path> files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CORRUPT_INDEX", "WRONG_PROJECT", "INCOMPATIBLE_INDEX"})
    void storageFailuresAreConciseAndDoNotModifyTheIndex(String reason) throws Exception {
        IndexResult indexed = new IndexService(loader).index(root);
        Path db = indexed.context().databasePath();

        if (reason.equals("CORRUPT_INDEX")) {
            Files.writeString(db, "not a database");
        } else {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toUri());
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        reason.equals("WRONG_PROJECT")
                                ? "UPDATE index_metadata SET project_root_uri = 'file:///other/'"
                                : "PRAGMA user_version = 99");
            }
        }

        byte[] before = Files.readAllBytes(db);

        assertEquals(1, run("needle", "--root", root.toString()));
        assertTrue(err.toString().contains(reason));
        assertFalse(err.toString().contains("\tat "));
        assertEquals("", out.toString());
        assertArrayEquals(before, Files.readAllBytes(db));
    }

    @Test
    void versionOneRequiresIndexingWithoutMigrating() throws Exception {
        Path db = root.resolve(".pecia/index.db");
        Files.createDirectory(db.getParent());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toUri());
                Statement statement = connection.createStatement();
                InputStream input = getClass().getResourceAsStream("/db/migration/V1__create_initial_schema.sql")) {
            assertNotNull(input);
            statement.executeUpdate(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            statement.execute("PRAGMA user_version = 1");

            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO index_metadata VALUES (1, ?, 1)")) {
                insert.setString(1, root.toRealPath().toUri().toASCIIString());
                insert.executeUpdate();
            }
        }

        byte[] before = Files.readAllBytes(db);

        assertEquals(1, run("needle", "--root", root.toString()));
        assertTrue(err.toString().contains("MIGRATION_REQUIRED"));
        assertTrue(err.toString().contains("Run: java -jar"));
        assertArrayEquals(before, Files.readAllBytes(db));
    }

    @Test
    void queryPrintsConfigurationWarningsFirstWithoutChangingStdoutOrExitCode() throws Exception {
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nneedle\n");
        new IndexService(loader).index(root);

        assertEquals(0, run("needle", "--root", root.toString()));
        String withWarning = out.toString();
        assertEquals(typoWarning(config) + System.lineSeparator(), err.toString());

        Files.writeString(config, "[chunk]\n");
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        assertEquals(0, run("needle", "--root", root.toString()));
        assertEquals(withWarning, out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void failedQueryPrintsConfigurationWarningsBeforeTheError() throws Exception {
        Path config = Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_token = 64\n");

        assertEquals(1, run("needle", "--root", root.toString()));

        List<String> lines = err.toString().lines().toList();
        assertEquals(typoWarning(config), lines.get(0));
        assertTrue(lines.get(1).startsWith("pecia query: INDEX_NOT_FOUND:"), lines.get(1));
        assertEquals("", out.toString());
    }

    @Test
    void helpDoesNotRequireQueryOrOpenResources() throws Exception {
        assertEquals(0, run("--root", root.toString(), "--help"));
        assertTrue(out.toString().contains("--limit"));
        assertTrue(out.toString().contains("--root"));
        assertTrue(out.toString().contains("BM25 lexical ranking"));
        assertTrue(out.toString().contains("default: " + SearchRequest.DEFAULT_LIMIT));
        assertTrue(out.toString().contains("--mode=<mode>"));
        // The usage message wraps long descriptions; collapsing whitespace still catches a break inside a name.
        assertTrue(out.toString()
                .replaceAll("\\s+", " ")
                .contains("(lexical, semantic, hybrid, auto), overriding search.mode in .pecia.toml (default: auto)."));
        assertEquals("", err.toString());

        try (Stream<Path> files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void invalidRootShowsTheUnderlyingContextProblem() throws Exception {
        Path file = Files.writeString(root.resolve("not-a-directory.txt"), "text");

        assertEquals(1, run("needle", "--root", file.toString()));
        assertTrue(err.toString().contains("Target must be a directory"));
        assertTrue(err.toString().contains(file.toString()));
        assertFalse(err.toString().contains("\tat "));
        assertEquals("", out.toString());
    }

    private static String typoWarning(Path config) {
        return "warning: " + config + ":2:1: unknown key chunk.max_token (did you mean chunk.max_tokens?)";
    }

    private int run(String... args) {
        return run(new QueryService(loader), args);
    }

    private int run(QueryService service, String... args) {
        CommandLine command = new CommandLine(new QueryCommand(service));
        command.setOut(new PrintWriter(out));
        command.setErr(new PrintWriter(err));

        return command.execute(args);
    }
}
