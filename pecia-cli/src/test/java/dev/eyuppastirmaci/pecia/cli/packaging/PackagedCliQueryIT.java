package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertIndexSummary;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertNoStackTrace;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.initialized;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliQueryIT {

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
    void queryReadsThePackagedIndexFromAnotherDirectoryWithoutSourceFilesOrTokenizer() throws Exception {
        Files.createDirectory(project.resolve("src"));
        Path source = project.resolve("src/PaymentService.java");
        Files.writeString(source, """
            class PaymentService {
                String secret = "JWT_SECRET";
                void charge() {}
            }
            """);
        Files.createDirectory(project.resolve("docs"));
        Path document = project.resolve("docs/Ödeme.md");
        Files.writeString(document, "# Ödeme rehberi\n\nJWT_SECRET ödeme işlemini doğrular.\n");
        Path database = project.resolve(".pecia/index.db");
        assertIndexSummary(cli.run(project, "index"), database, 2, 2);

        List<SearchHit> expected;

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            expected = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));
        }

        assertEquals(2, expected.size());
        Files.delete(source);
        Files.delete(document);
        byte[] indexedBytes = Files.readAllBytes(database);
        List<Path> entriesBeforeQuery = cli.projectEntries();
        Path workingDirectory = Files.createDirectory(sandbox.resolve("elsewhere"));
        Path initializationLog = sandbox.resolve("query-initialization.log");

        Result identifier = cli.run(
                workingDirectory,
                List.of(OfflineSandbox.initializationLogArgument(initializationLog)),
                "query",
                "JWT_SECRET",
                "--root",
                project.toString());

        assertSuccess(identifier);
        assertEquals(
                expected.stream().map(hit -> "  BM25: " + hit.score().value()).toList(),
                identifier
                        .out()
                        .lines()
                        .filter(line -> line.startsWith("  BM25: "))
                        .toList());
        assertTrue(
                identifier.out().indexOf(portablePath(expected.getFirst().sourcePath()) + ":")
                        < identifier
                                .out()
                                .indexOf(portablePath(expected.getLast().sourcePath()) + ":"),
                identifier.out());
        assertTrue(identifier.out().contains("src/PaymentService.java:1-4\n  BM25: "), identifier.out());
        assertTrue(
                identifier
                        .out()
                        .contains("  class PaymentService {\n      String secret = \"JWT_SECRET\";\n"
                                + "      void charge() {}\n  }\n"),
                identifier.out());
        assertTrue(identifier.out().contains("docs/Ödeme.md:1-3\n  BM25: "), identifier.out());
        assertTrue(
                identifier
                        .out()
                        .contains("  heading: Ödeme rehberi\n  # Ödeme rehberi\n  \n"
                                + "  JWT_SECRET ödeme işlemini doğrular.\n"),
                identifier.out());
        assertTrue(identifier.out().endsWith("\n\n"), identifier.out());
        assertFalse(identifier.out().contains("\u001b"));
        assertTrue(initialized(initializationLog, "dev/eyuppastirmaci/pecia/Bootstrap"));
        assertFalse(initialized(initializationLog, "dev/eyuppastirmaci/pecia/tokenization/MiniLmTokenizer"));

        Result limited = cli.run(workingDirectory, "query", "JWT_SECRET", "--root", project.toString(), "--limit", "1");

        assertSuccess(limited);
        assertTrue(limited.out().startsWith(portablePath(expected.getFirst().sourcePath()) + ":"), limited.out());
        assertEquals(
                1,
                limited.out()
                        .lines()
                        .filter(line -> line.startsWith("  BM25: "))
                        .count());

        Result unicode = cli.run(project, "query", "ödeme");

        assertSuccess(unicode);
        assertTrue(unicode.out().startsWith("docs/Ödeme.md:1-3\n"), unicode.out());
        assertTrue(unicode.out().contains("  heading: Ödeme rehberi\n"), unicode.out());
        assertTrue(unicode.out().contains("ödeme işlemini doğrular."), unicode.out());

        Result missing = cli.run(workingDirectory, "query", "unfindablelexicalterm", "--root", project.toString());

        assertSuccess(missing);
        assertEquals("No results.\n", missing.out());
        assertEquals(entriesBeforeQuery, cli.projectEntries());
        assertArrayEquals(indexedBytes, Files.readAllBytes(database));

        try (Stream<Path> entries = Files.list(workingDirectory)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void missingIndexReportsAnIndexCommandWithoutCreatingFiles() throws Exception {
        Result result = cli.run(project, "query", "example");

        assertEquals(1, result.exitCode());
        assertEquals("", result.out());
        assertTrue(result.err().startsWith("pecia query: "), result.err());
        assertTrue(result.err().contains("java -jar "), result.err());
        assertTrue(result.err().contains(" index "), result.err());
        assertTrue(result.err().contains(project.toString()), result.err());
        assertNoStackTrace(result.err());
        assertTrue(cli.projectEntries().isEmpty());
    }

    private static String portablePath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }
}
