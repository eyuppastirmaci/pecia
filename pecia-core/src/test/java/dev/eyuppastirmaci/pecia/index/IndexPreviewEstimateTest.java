package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A dry run's chunk counts are known for stored files and estimated otherwise; a semantic one also counts embeddings. */
class IndexPreviewEstimateTest {

    private static final ModelOutcome TINY = TinyModel.cached();
    private static final EmbeddingProfile CURRENT = EmbeddingProfile.minilm();
    // The same model under another pinned revision, so a semantic run discards the stored embeddings first.
    private static final EmbeddingProfile NEXT = new EmbeddingProfile(
            CURRENT.modelId(),
            "next-revision",
            CURRENT.modelSha256(),
            CURRENT.dimensions(),
            CURRENT.tokenizerAlgorithm(),
            CURRENT.vocabularySha256(),
            CURRENT.maxInputTokens(),
            CURRENT.pooling(),
            CURRENT.normalization(),
            CURRENT.input());

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private EmbeddingProfile profile = CURRENT;
    private Optional<ModelOutcome> cached = Optional.of(TINY);
    private ModelException lookupFailure;
    private int projects;

    @ParameterizedTest(name = "configuration [{0}]")
    @ValueSource(strings = {"", "[chunk]\nmax_tokens = 128\noverlap_tokens = 16\n"})
    void estimatesComeCloseToTheChunksARunWritesOnRealSources(String config) throws Exception {
        Path project = copyOfCoreSources(config);

        IndexPreview.Changes before = service().preview(project).changes();
        service().index(project);
        IndexPreview.Changes after = service().preview(project).changes();

        Map<Path, Long> estimates = new HashMap<>();
        before.newFiles()
                .forEach(file -> estimates.put(file.sourcePath(), file.chunks().value()));
        long estimated = 0;
        long actual = 0;
        int close = 0;

        for (IndexPreview.PlannedFile file : after.unchangedFiles()) {
            long chunks = file.chunks().value();
            long estimate = estimates.get(file.sourcePath());
            estimated += estimate;
            actual += chunks;
            close += Math.abs(estimate - chunks) <= Math.max(1, 0.25 * chunks) ? 1 : 0;
        }

        // Measured at about 2% in total and 97% of files on these sources; the bounds leave room as they change.
        assertTrue(before.chunksToWrite().estimated());
        assertEquals(estimated, before.chunksToWrite().value());
        assertTrue(Math.abs(estimated - actual) <= 0.15 * actual, estimated + " estimated, " + actual + " written");
        assertTrue(
                close >= 0.9 * after.unchangedFiles().size(),
                close + " of " + after.unchangedFiles().size());
    }

    @Test
    void storedFilesCarryTheirExactChunkCounts() throws Exception {
        Path project = corpus();
        service().index(project);
        Files.delete(project.resolve(LexicalTestCorpus.CONFIG));
        IndexDump dump = IndexDump.read(project.resolve(".pecia/index.db"), project, List.of());
        Map<String, Long> stored = new HashMap<>();
        dump.chunks().forEach(chunk -> stored.merge(chunk.sourcePath(), 1L, Long::sum));

        IndexPreview.Changes changes = service().preview(project).changes();

        for (IndexPreview.PlannedFile file : changes.unchangedFiles()) {
            assertFalse(file.chunks().estimated());
            assertEquals(
                    stored.getOrDefault(FileWalker.portablePath(file.sourcePath()), 0L),
                    file.chunks().value());
        }

        assertEquals(
                List.of(new IndexPreview.PlannedFile(
                        LexicalTestCorpus.CONFIG, IndexPreview.Count.known(stored.get("config.yaml")))),
                changes.removedFiles());
        assertFalse(changes.chunksKept().estimated());
        assertEquals(
                dump.chunks().size(),
                changes.chunksKept().value() + changes.chunksRemoved().value());
    }

    @Test
    void anEmptyFileHasNoChunksForCertain() throws Exception {
        Path project = corpus();

        IndexPreview.Changes changes = service().preview(project).changes();

        IndexPreview.PlannedFile empty = changes.newFiles().stream()
                .filter(file -> file.sourcePath().equals(LexicalTestCorpus.EMPTY))
                .findFirst()
                .orElseThrow();
        assertEquals(IndexPreview.Count.known(0), empty.chunks());
        assertTrue(changes.newFiles().stream()
                .filter(file -> file != empty)
                .allMatch(file -> file.chunks().estimated() && file.chunks().value() >= 1));
    }

    @Test
    void aLexicalPreviewHasNoSemanticPart() throws Exception {
        assertNull(service().preview(corpus()).changes().semantic());
    }

    @Test
    void aSemanticPreviewWithoutAnIndexEmbedsEveryChunkItWouldWrite() throws Exception {
        IndexPreview.Changes changes =
                service().preview(corpus(), IndexOptions.semantic(true)).changes();

        IndexPreview.SemanticChanges semantic = changes.semantic();
        assertEquals(SemanticModel.MINILM.name(), semantic.model());
        assertEquals(IndexPreview.ModelState.CACHED, semantic.modelState());
        assertEquals(0, semantic.downloadBytes());
        assertEquals(changes.chunksToWrite(), semantic.chunksToEmbed());
        assertEquals(0, semantic.embeddingsToDiscard());
    }

    @Test
    void storedChunksWithoutEmbeddingsAreCountedExactlyAsTheRunBackfillsThem() throws Exception {
        Path project = corpus();
        service().index(project);
        Files.writeString(project.resolve("added.md"), "# Added\n\nA new file about refunds.\n");

        IndexPreview.Changes changes =
                service().preview(project, IndexOptions.semantic(true)).changes();
        IndexResult result = service().index(project, IndexOptions.semantic(true));

        IndexPreview.Count toEmbed = changes.semantic().chunksToEmbed();
        assertTrue(toEmbed.estimated());
        assertEquals(
                result.backfilledChunks(),
                toEmbed.value() - changes.chunksToWrite().value());
        assertEquals(0, changes.semantic().embeddingsToDiscard());
    }

    @Test
    void embeddingsOfAnotherModelVersionAreCountedAsDiscardedAndMadeAgain() throws Exception {
        Path project = corpus();
        service().index(project, IndexOptions.semantic(true));
        profile = NEXT;

        IndexPreview.Changes changes =
                service().preview(project, IndexOptions.semantic(true)).changes();
        IndexResult result = service().index(project, IndexOptions.semantic(true));

        assertEquals(result.discardedEmbeddings(), changes.semantic().embeddingsToDiscard());
        assertTrue(changes.semantic().embeddingsToDiscard() > 0);
        assertEquals(
                IndexPreview.Count.known(result.backfilledChunks()),
                changes.semantic().chunksToEmbed());
    }

    @Test
    void aMissingModelWouldBeDownloadedWithItsSize() throws Exception {
        cached = Optional.empty();

        IndexPreview.SemanticChanges semantic = service()
                .preview(corpus(), IndexOptions.semantic(true))
                .changes()
                .semantic();

        assertEquals(IndexPreview.ModelState.DOWNLOAD, semantic.modelState());
        assertEquals(SemanticModel.MINILM.size(), semantic.downloadBytes());
    }

    @Test
    void anUnreadableModelCacheIsReported() throws Exception {
        lookupFailure = new ModelException(ModelException.Reason.CACHE_UNAVAILABLE, "unreadable", null);

        IndexPreview.SemanticChanges semantic = service()
                .preview(corpus(), IndexOptions.semantic(true))
                .changes()
                .semantic();

        assertEquals(IndexPreview.ModelState.UNAVAILABLE, semantic.modelState());
        assertEquals(0, semantic.downloadBytes());
    }

    @Test
    void estimatesGrowWithSizeAndShrinkingBudgets() {
        ChunkEstimate defaults = new ChunkEstimate(PeciaConfig.defaults());
        ChunkEstimate smaller = new ChunkEstimate(withBudget(128, 16));

        assertEquals(0, defaults.chunks(DocumentType.MARKDOWN, 0));
        assertEquals(1, defaults.chunks(DocumentType.MARKDOWN, 1));
        assertEquals(10, defaults.chunks(DocumentType.MARKDOWN, 3_870));
        assertEquals(10, defaults.chunks(DocumentType.PLAIN_TEXT, 8_450));
        assertEquals(10, defaults.chunks(DocumentType.SOURCE_CODE, 6_010));
        assertEquals(10, defaults.chunks(DocumentType.STRUCTURED_TEXT, 4_570));
        // 128 tokens leave 126 for content and advance 110 per chunk, against 190 by default.
        assertEquals(Math.round(10 * 190 / 110.0), smaller.chunks(DocumentType.MARKDOWN, 3_870));
    }

    @Test
    void countsAndPlannedFilesValidateTheirParts() {
        assertThrows(IllegalArgumentException.class, () -> IndexPreview.Count.known(-1));
        assertEquals(
                new IndexPreview.Count(5, true), IndexPreview.Count.known(2).plus(IndexPreview.Count.estimate(3)));
        assertEquals(IndexPreview.Count.known(5), IndexPreview.Count.known(2).plus(IndexPreview.Count.known(3)));
        assertThrows(NullPointerException.class, () -> new IndexPreview.PlannedFile(null, IndexPreview.Count.known(1)));
        assertThrows(NullPointerException.class, () -> new IndexPreview.PlannedFile(Path.of("a.md"), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexPreview.SemanticChanges(
                        "model", IndexPreview.ModelState.CACHED, 10, IndexPreview.Count.known(0), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexPreview.SemanticChanges(
                        "model", IndexPreview.ModelState.DOWNLOAD, 10, IndexPreview.Count.known(0), -1));
    }

    private static PeciaConfig withBudget(int maxTokens, int overlapTokens) {
        return new PeciaConfigParser()
                .parse("[chunk]\nmax_tokens = " + maxTokens + "\noverlap_tokens = " + overlapTokens + "\n");
    }

    /** Copies the core module's main sources and Pecia's README into a fresh project with the given configuration. */
    private Path copyOfCoreSources(String config) throws IOException {
        Path project = Files.createDirectories(sandbox.resolve("sources-" + projects++))
                .toRealPath();
        Path source = Path.of("src/main/java").toRealPath();

        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path target = project.resolve(source.relativize(path).toString());
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }

        Files.copy(Path.of("../README.md"), project.resolve("README.md"));
        Files.writeString(project.resolve(".pecia.toml"), config);

        return project;
    }

    private Path corpus() throws IOException {
        Path project = Files.createDirectories(sandbox.resolve("project-" + projects++))
                .toRealPath();
        LexicalTestCorpus.write(project);
        return project;
    }

    private IndexService service() {
        return new IndexService(
                loader,
                IndexPreviewEstimateTest::openStorage,
                listener -> TINY,
                TextEmbedder::open,
                () -> profile,
                () -> {
                    if (lookupFailure != null) {
                        throw lookupFailure;
                    }

                    return cached;
                });
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }
}
