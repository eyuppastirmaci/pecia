package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.semantic.DownloadListener;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IndexOptionsTest {

    @TempDir
    Path sandbox;

    // Semantic runs get the tiny test model from a stub, so these tests never download the real one.
    private static final ModelOutcome CACHED_MODEL = TinyModel.cached();

    private final IndexService indexService =
            IndexService.withModelProvider(new PeciaConfigLoader(new PeciaConfigParser()), listener -> CACHED_MODEL);
    private Path root;

    @BeforeEach
    void prepareProject() throws IOException {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nsemantic choices\n");
    }

    @Test
    void factoriesLeaveTheChoiceToTheConfigurationOrMakeItExplicitly() {
        assertNull(IndexOptions.configured().semanticEnabled());
        assertEquals(Boolean.TRUE, IndexOptions.semantic(true).semanticEnabled());
        assertEquals(Boolean.FALSE, IndexOptions.semantic(false).semanticEnabled());
    }

    @ParameterizedTest(name = "file {0}, option {1} -> {2}")
    @CsvSource(
            nullValues = "none",
            value = {
                "none, none, false",
                "none, true, true",
                "none, false, false",
                "true, none, true",
                "true, true, true",
                "true, false, false",
                "false, none, false",
                "false, true, true",
                "false, false, false"
            })
    void anExplicitChoiceOverridesTheFileWhichOverridesTheDefault(Boolean file, Boolean option, boolean expected)
            throws IOException {
        if (file != null) {
            Files.writeString(root.resolve(".pecia.toml"), "[semantic]\nenabled = " + file + "\n");
        }

        IndexOptions options = option == null ? IndexOptions.configured() : IndexOptions.semantic(option);

        assertEquals(expected, indexService.preview(root, options).semanticEnabled());

        if (option == null) {
            assertEquals(expected, indexService.preview(root).semanticEnabled());
        }
    }

    @Test
    void choosingSemanticIndexingAddsEmbeddingsWithoutChangingTheLexicalIndex() throws Exception {
        Path disabled = Files.createDirectories(sandbox.resolve("disabled")).toRealPath();
        Path enabled = Files.createDirectories(sandbox.resolve("enabled")).toRealPath();
        LexicalTestCorpus.write(disabled);
        LexicalTestCorpus.write(enabled);

        IndexDump withoutSemantic = indexAndDump(disabled, IndexOptions.semantic(false));
        IndexDump withSemantic = indexAndDump(enabled, IndexOptions.semantic(true));

        assertFalse(withoutSemantic.chunks().isEmpty());
        assertEquals(List.of(), withoutSemantic.embeddings());
        assertEquals(withSemantic.chunks().size(), withSemantic.embeddings().size());
        assertEquals(withoutSemantic, withSemantic.withoutEmbeddings());
    }

    @Test
    void eachWitherKeepsTheOtherChoices() {
        Cancellation cancellation = new Cancellation();
        DownloadListener downloads = (model, file) -> {};
        IndexProgressListener progress = update -> {};

        IndexOptions options = IndexOptions.semantic(true)
                .withCancellation(cancellation)
                .withDownloadListener(downloads)
                .withProgressListener(progress);

        assertEquals(new IndexOptions(true, downloads, progress, cancellation), options);
        assertEquals(options, options.withCancellation(cancellation));
        assertNull(IndexOptions.configured().cancellation());
        assertThrows(NullPointerException.class, () -> options.withCancellation(null));
    }

    @Test
    void cancellingIsPermanent() {
        Cancellation cancellation = new Cancellation();

        assertFalse(cancellation.isCancelled());
        cancellation.cancel();
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());
    }

    @Test
    void nullOptionsAreRejectedBeforeTheTargetIsResolved() {
        // A missing target would fail with an IOException if the target were resolved first.
        Path missing = sandbox.resolve("missing");

        assertThrows(NullPointerException.class, () -> indexService.index(missing, null));
        assertThrows(NullPointerException.class, () -> indexService.preview(missing, null));
        assertFalse(Files.exists(missing));
    }

    private IndexDump indexAndDump(Path project, IndexOptions options) throws Exception {
        IndexResult result = indexService.index(project, options);
        assertEquals(IndexResult.Status.COMPLETE, result.status());

        return IndexDump.read(result.context().databasePath(), project, LexicalTestCorpus.QUERIES);
    }
}
