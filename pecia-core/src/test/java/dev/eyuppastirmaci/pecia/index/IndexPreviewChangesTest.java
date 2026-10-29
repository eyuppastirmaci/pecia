package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.testing.LegacyIndexFixture;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A dry run says what the following run does with the index and every candidate, and writes nothing. */
class IndexPreviewChangesTest {

    @TempDir
    Path sandbox;

    private final IndexService service = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
    private int projects;

    @Test
    void eachCandidateLandsInTheCategoryOfWhatTheRunDoes() throws Exception {
        Path project = corpus();
        service.index(project);
        Files.writeString(project.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");
        Files.writeString(project.resolve("added.md"), "# Added\n\nA new file.\n");
        Files.delete(project.resolve(LexicalTestCorpus.CONFIG));

        IndexPreview.Changes changes = assertAgreesWithTheRun(project);

        assertEquals(IndexPreview.IndexState.CURRENT, changes.index());
        assertEquals(List.of(Path.of("added.md")), paths(changes.newFiles()));
        assertEquals(List.of(LexicalTestCorpus.NOTES), paths(changes.changedFiles()));
        assertEquals(List.of(), changes.rechunkedFiles());
        assertEquals(5, changes.unchangedFiles().size());
        assertEquals(List.of(), changes.rejectedFiles());
        assertEquals(List.of(LexicalTestCorpus.CONFIG), paths(changes.removedFiles()));
        assertNull(changes.storageFailure());
    }

    @Test
    void withoutAnIndexEveryReadableCandidateIsNewAndNothingIsCreated() throws Exception {
        Path project = corpus();
        Files.write(project.resolve("binary.txt"), new byte[] {0, 1, 2, 0});

        IndexPreview.Changes changes = assertAgreesWithTheRun(project);

        assertEquals(IndexPreview.IndexState.NONE, changes.index());
        assertEquals(7, changes.newFiles().size());
        assertEquals(
                List.of(ExtractionException.Reason.BINARY_CONTENT),
                changes.rejectedFiles().stream()
                        .map(IndexResult.FileIssue::reason)
                        .toList());
    }

    @Test
    void otherChunkSettingsRechunkEveryStoredFile() throws Exception {
        Path project = corpus();
        service.index(project);
        Files.writeString(project.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\noverlap_tokens = 16\n");

        IndexPreview.Changes changes = assertAgreesWithTheRun(project);

        assertEquals(7, changes.rechunkedFiles().size());
        assertEquals(List.of(), changes.unchangedFiles());
    }

    @Test
    void aRejectedFileKeepsDeletedFilesInTheIndexAsTheRunDoes() throws Exception {
        Path project = corpus();
        service.index(project);
        Files.delete(project.resolve(LexicalTestCorpus.CONFIG));
        Files.write(project.resolve(LexicalTestCorpus.NOTES), new byte[] {(byte) 0xc3, 0x28});

        IndexPreview.Changes changes = assertAgreesWithTheRun(project);

        assertEquals(1, changes.rejectedFiles().size());
        assertEquals(List.of(), changes.removedFiles());
    }

    @Test
    void anIncompleteScanRemovesNothingAsTheRunDoes() throws Exception {
        Path project = corpus();
        service.index(project);
        Files.delete(project.resolve(LexicalTestCorpus.CONFIG));
        Files.createDirectories(project.resolve("drafts/.gitignore"));

        IndexPreview preview = service.preview(project);

        assertFalse(preview.walkResult().complete());
        assertEquals(List.of(), preview.changes().removedFiles());
        assertEquals(0, service.index(project).deletedFiles());
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 2026, 90210})
    void randomEditsArePreviewedExactlyStepByStep(long seed) throws Exception {
        Path project = corpus();
        Random random = new Random(seed);
        service.index(project);

        for (int step = 0; step < 6; step++) {
            mutate(project, random, step);
            assertAgreesWithTheRun(project);
        }
    }

    @ParameterizedTest(name = "schema v{0}")
    @ValueSource(ints = {1, 4})
    void anIndexOfAnOlderFormatIsPreviewedAfterAnUpgradeOfACopy(int version) throws Exception {
        Path project =
                Files.createDirectories(sandbox.resolve("legacy-" + version)).toRealPath();
        LegacyIndexFixture.install(version, project);
        Files.writeString(project.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");
        List<Path> before = previewDirectories();

        IndexPreview.Changes changes = assertAgreesWithTheRun(project);

        assertEquals(IndexPreview.IndexState.UPGRADE, changes.index());
        assertTrue(paths(changes.changedFiles()).contains(LexicalTestCorpus.NOTES), changes.toString());
        assertEquals(before, previewDirectories(), "The upgraded copy is removed");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "TRUNCATED, CORRUPT",
        "FOREIGN_PROJECT, WRONG_PROJECT",
        "FUTURE_SCHEMA, INCOMPATIBLE",
        "FORMAT_MISMATCH, INCOMPATIBLE"
    })
    void anUnreadableIndexIsReportedWithItsReasonAndLeftAlone(LegacyIndexFixture.Damage damage, StorageFailure reason)
            throws Exception {
        Path project = Files.createDirectories(sandbox.resolve("damaged")).toRealPath();
        LegacyIndexFixture.installDamaged(damage, project);
        Map<String, String> before = snapshot(project);

        IndexPreview.Changes changes = service.preview(project).changes();

        assertEquals(IndexPreview.IndexState.UNREADABLE, changes.index());
        assertEquals(reason, changes.storageFailure());
        assertFalse(changes.storageMessage().isBlank());
        assertEquals(List.of(), changes.newFiles());
        assertEquals(before, snapshot(project));
    }

    @Test
    void changesValidateTheirStorageFailure() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexPreview.Changes(
                        IndexPreview.IndexState.UNREADABLE,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexPreview.Changes(
                        IndexPreview.IndexState.CURRENT,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        StorageFailure.CORRUPT,
                        "damaged"));
        IndexPreview.PlannedFile file = new IndexPreview.PlannedFile(Path.of("a.md"), IndexPreview.Count.estimate(2));
        List<IndexPreview.PlannedFile> added = new ArrayList<>(List.of(file));
        IndexPreview.Changes changes = new IndexPreview.Changes(
                IndexPreview.IndexState.NONE,
                added,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
        added.clear();
        assertEquals(List.of(file), changes.newFiles());
    }

    /**
     * Previews the project, checks that nothing was written, then indexes it and checks that every count the run
     * reports is what the preview said.
     */
    private IndexPreview.Changes assertAgreesWithTheRun(Path project) throws Exception {
        Map<String, String> before = snapshot(project);

        IndexPreview.Changes changes = service.preview(project).changes();

        assertEquals(before, snapshot(project), "The preview writes nothing");
        IndexResult result;

        try {
            result = service.index(project);
        } catch (IndexException failure) {
            result = failure.result();
        }

        assertEquals(
                changes.newFiles().size()
                        + changes.changedFiles().size()
                        + changes.rechunkedFiles().size(),
                result.indexedFiles(),
                changes.toString());
        assertEquals(changes.rechunkedFiles().size(), result.rechunkedFiles());
        assertEquals(changes.unchangedFiles().size(), result.unchangedFiles());
        assertEquals(changes.removedFiles().size(), result.deletedFiles());
        assertEquals(changes.rejectedFiles(), result.issues());

        return changes;
    }

    private static List<Path> paths(List<IndexPreview.PlannedFile> files) {
        return files.stream().map(IndexPreview.PlannedFile::sourcePath).toList();
    }

    /** Applies one seeded edit of the kinds a project sees between runs. */
    private static void mutate(Path project, Random random, int step) throws IOException {
        List<Path> files;

        try (Stream<Path> paths = Files.walk(project)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> !project.relativize(path).startsWith(".pecia"))
                    .filter(path -> !path.getFileName().toString().equals(".pecia.toml"))
                    .sorted()
                    .toList();
        }

        Path file = files.isEmpty() ? project.resolve("first.md") : files.get(random.nextInt(files.size()));

        switch (random.nextInt(7)) {
            case 0 -> Files.writeString(file, "Edited in step " + step + " about refunds and tokens.\n");
            case 1 -> Files.writeString(project.resolve("added-" + step + ".md"), "# Step " + step + "\n\nNew.\n");
            case 2 -> Files.deleteIfExists(file);
            case 3 -> Files.write(project.resolve("blob-" + step + ".txt"), new byte[] {0, 7, 0, 7});
            case 4 -> Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L * (step + 1)));
            case 5 ->
                Files.writeString(
                        project.resolve(".pecia.toml"),
                        "[chunk]\nmax_tokens = " + (96 + 32 * random.nextInt(4)) + "\noverlap_tokens = 8\n");
            default -> Files.move(file, file.resolveSibling("renamed-" + step + "-" + file.getFileName()));
        }
    }

    /** Maps every entry below the project, index included, to its content hash. */
    private static Map<String, String> snapshot(Path project) throws IOException {
        Map<String, String> entries = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(project)) {
            for (Path path : paths.toList()) {
                entries.put(
                        project.relativize(path).toString(),
                        Files.isDirectory(path)
                                ? "directory"
                                : ContentHash.sha256(Files.readAllBytes(path)).value());
            }
        }

        return entries;
    }

    private static List<Path> previewDirectories() throws IOException {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("pecia-preview-"))
                    .sorted()
                    .toList();
        }
    }

    private Path corpus() throws IOException {
        Path project = Files.createDirectories(sandbox.resolve("project-" + projects++))
                .toRealPath();
        LexicalTestCorpus.write(project);
        return project;
    }
}
