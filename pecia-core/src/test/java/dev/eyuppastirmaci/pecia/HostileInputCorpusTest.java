package dev.eyuppastirmaci.pecia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Runs the hostile-input corpus described in {@code hostile/README.md}: every case in {@code hostile/cases.toml} must
 * produce its recorded typed outcome end to end, and a rejected input must leave earlier records untouched.
 */
class HostileInputCorpusTest {

    private static final String RESOURCE_ROOT = "/hostile/";
    private static final String CONTENT_DIRECTORY = "content";
    private static final String PREVIOUS_VERSION = "payment validation before the hostile change\n";
    private static final int SIZE_LIMIT = 64;

    @TempDir
    Path sandbox;

    private final IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

    static Stream<ContentCase> contentCases() throws IOException {
        return readContentCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentCases")
    void contentInputIsIndexedOrRejectedAsRecorded(ContentCase contentCase) throws Exception {
        Path root = project();
        Path file = root.resolve(contentCase.fileName());

        if (contentCase.rejected()) {
            // A rejected input must not replace the records of the file's last accepted version.
            Files.writeString(file, PREVIOUS_VERSION);
            IndexResult accepted = indexService.index(root);
            assertEquals(IndexResult.Status.COMPLETE, accepted.status());
            IndexDump before = dump(accepted, root);

            Files.write(file, contentCase.bytes());
            IndexResult result = indexService.index(root);

            assertEquals(IndexResult.Status.PARTIAL, result.status());
            assertEquals(0, result.indexedFiles());
            assertEquals(1, result.issues().size(), () -> result.issues().toString());
            IndexResult.FileIssue issue = result.issues().get(0);
            assertEquals(Path.of(contentCase.fileName()), issue.sourcePath());
            assertEquals(contentCase.reason(), issue.reason(), issue.message());
            assertEquals(before, dump(result, root), "A rejected input must retain the earlier records");
        } else {
            Files.write(file, contentCase.bytes());
            IndexResult result = indexService.index(root);

            assertEquals(
                    IndexResult.Status.COMPLETE,
                    result.status(),
                    () -> result.issues().toString());
            assertEquals(1, result.indexedFiles());
            assertEquals(contentCase.chunks(), result.writtenChunks());
            List<IndexDump.ChunkEntry> chunks = dump(result, root).chunks();
            assertEquals(contentCase.chunks(), chunks.size());
            for (IndexDump.ChunkEntry chunk : chunks) {
                assertFalse(chunk.content().contains("﻿"), "Stored content must not keep a byte order mark");
            }
        }
    }

    @ParameterizedTest(name = "{0} bytes -> {1}")
    @CsvSource({"64, COMPLETE", "65, PARTIAL"})
    void sizeLimitAcceptsTheExactLimitAndRejectsOneByteMore(int size, IndexResult.Status status) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Files.writeString(root.resolve(PeciaConfigFile.FILE_NAME), "[index]\nmax_file_bytes = " + SIZE_LIMIT + "\n");
        Files.writeString(root.resolve("sized.txt"), "x".repeat(size));

        IndexResult result = indexService.index(root);

        assertEquals(status, result.status(), () -> result.issues().toString());
        if (status == IndexResult.Status.COMPLETE) {
            assertEquals(1, result.indexedFiles());
        } else {
            assertEquals(0, result.indexedFiles());
            assertEquals(1, result.issues().size());
            assertEquals(
                    ExtractionException.Reason.TOO_LARGE, result.issues().get(0).reason());
        }
    }

    @Test
    void manifestAndInputFilesMatchOneToOne() throws Exception {
        List<ContentCase> cases = readContentCases();
        Set<String> ids = new HashSet<>();
        Set<String> listed = new TreeSet<>();

        for (ContentCase contentCase : cases) {
            assertTrue(ids.add(contentCase.id()), "Duplicate case id: " + contentCase.id());
            listed.add(contentCase.input());
        }

        Set<String> committed = new TreeSet<>();
        try (Stream<Path> files = Files.list(resourceDirectory().resolve(CONTENT_DIRECTORY))) {
            files.forEach(path -> committed.add(CONTENT_DIRECTORY + "/" + path.getFileName()));
        }

        assertEquals(committed, listed, "Every input needs exactly one manifest entry");
    }

    @Test
    void inputsKeepTheirRecordedBytes() throws Exception {
        for (ContentCase contentCase : readContentCases()) {
            assertEquals(contentCase.sha256(), sha256(contentCase.bytes()), contentCase.input());
        }
    }

    private Path project() throws IOException {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        // A repository marker pins the project root without a configuration file, so defaults apply.
        Files.createDirectory(root.resolve(".git"));

        return root;
    }

    private static IndexDump dump(IndexResult result, Path root) throws Exception {
        return IndexDump.read(result.context().databasePath(), root, List.of("payment"));
    }

    private static List<ContentCase> readContentCases() throws IOException {
        TomlParseResult manifest = Toml.parse(new String(readResource("cases.toml"), StandardCharsets.UTF_8));
        assertFalse(manifest.hasErrors(), () -> "Invalid hostile manifest: " + manifest.errors());
        TomlArray rows = manifest.getArray(CONTENT_DIRECTORY);
        assertNotNull(rows, "Missing content cases");

        List<ContentCase> cases = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            cases.add(ContentCase.from(rows.getTable(index)));
        }

        return cases;
    }

    private static byte[] readResource(String name) throws IOException {
        try (var input = HostileInputCorpusTest.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            assertNotNull(input, "Missing hostile resource: " + name);

            return input.readAllBytes();
        }
    }

    private static Path resourceDirectory() throws URISyntaxException {
        var url = HostileInputCorpusTest.class.getResource(RESOURCE_ROOT);
        assertNotNull(url, "Missing hostile resource directory");

        return Path.of(url.toURI());
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * One content case from the manifest.
     *
     * @param reason the expected rejection reason, or null for an indexed case
     * @param chunks the expected chunk count of an indexed case
     */
    record ContentCase(
            String id, String input, String sha256, ExtractionException.Reason reason, long chunks, byte[] bytes) {

        static ContentCase from(TomlTable row) throws IOException {
            String id = required(row, "id");
            String input = required(row, "input");
            String outcome = required(row, "outcome");
            byte[] bytes = readResource(input);

            return switch (outcome) {
                case "indexed" -> {
                    assertNull(row.get("reason"), id + ": an indexed case has no reason");
                    Long chunks = row.getLong("chunks");
                    assertNotNull(chunks, id + ": an indexed case needs a chunk count");
                    yield new ContentCase(id, input, required(row, "sha256"), null, chunks, bytes);
                }
                case "rejected" -> {
                    assertNull(row.get("chunks"), id + ": a rejected case has no chunk count");
                    var reason = ExtractionException.Reason.valueOf(required(row, "reason"));
                    yield new ContentCase(id, input, required(row, "sha256"), reason, 0, bytes);
                }
                default -> throw new AssertionError(id + ": unknown outcome " + outcome);
            };
        }

        private static String required(TomlTable row, String key) {
            String value = row.getString(key);
            assertNotNull(value, () -> "Content case is missing " + key + ": " + row.toJson());

            return value;
        }

        boolean rejected() {
            return reason != null;
        }

        String fileName() {
            return Path.of(input).getFileName().toString();
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
