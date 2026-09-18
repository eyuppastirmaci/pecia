package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class QueryCommandTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

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

    @Test
    void missingIndexProvidesAnIndexCommandWithoutCreatingAnything() throws Exception {
        assertEquals(1, run("needle", "--root", root.toString()));
        assertTrue(err.toString().contains("INDEX_NOT_FOUND"));
        assertTrue(err.toString().contains("Run: java -jar "));
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
                statement.execute(reason.equals("WRONG_PROJECT")
                        ? "UPDATE index_metadata SET project_root_uri = 'file:///other/'" : "PRAGMA user_version = 99");
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

            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO index_metadata VALUES (1, ?, 1)")) {
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
    void helpDoesNotRequireQueryOrOpenResources() throws Exception {
        assertEquals(0, run("--root", root.toString(), "--help"));
        assertTrue(out.toString().contains("--limit"));
        assertTrue(out.toString().contains("--root"));
        assertTrue(out.toString().contains("BM25 lexical ranking"));
        assertTrue(out.toString().contains("default: " + SearchRequest.DEFAULT_LIMIT));
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

    private int run(String... args) {
        CommandLine command = new CommandLine(new QueryCommand(new QueryService(loader)));
        command.setOut(new PrintWriter(out));
        command.setErr(new PrintWriter(err));

        return command.execute(args);
    }
}
