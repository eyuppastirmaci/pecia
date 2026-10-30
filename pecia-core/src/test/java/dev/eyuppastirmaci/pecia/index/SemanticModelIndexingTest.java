package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.semantic.DownloadListener;
import dev.eyuppastirmaci.pecia.semantic.ModelException;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** When an indexing run asks for the semantic model, and what a missing model leaves behind. */
class SemanticModelIndexingTest {

    private static final ModelOutcome CACHED = TinyModel.cached();

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final List<DownloadListener> requests = new ArrayList<>();
    private ModelException failure;
    private Path root;

    @BeforeEach
    void prepareProject() throws IOException {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nsemantic model\n");
    }

    @Test
    void anEnabledRunAsksForTheModelOnceAndReportsIt() throws Exception {
        IndexResult result = service().index(root, IndexOptions.semantic(true));

        assertEquals(IndexResult.Status.COMPLETE, result.status());
        assertSame(CACHED, result.semanticModel());
        assertEquals(1, requests.size());
    }

    @Test
    void theCallersListenerHearsTheDownloadTheProviderAnnounces() throws Exception {
        List<Path> heard = new ArrayList<>();
        DownloadListener listener = (model, file) -> heard.add(file);

        service().index(root, IndexOptions.semantic(true).withDownloadListener(listener));
        // The provider gets the run's own listener, which also reports the download's bytes as progress.
        requests.getFirst().downloading(SemanticModel.MINILM, root.resolve("model.onnx"));

        assertEquals(List.of(root.resolve("model.onnx")), heard);
    }

    @Test
    void semanticIndexingEnabledInTheFileAlsoAsksForTheModel() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[semantic]\nenabled = true\n");

        assertSame(CACHED, service().index(root).semanticModel());
        assertEquals(1, requests.size());
    }

    @Test
    void lexicalRunsAndPreviewsNeverAskForTheModel() throws Exception {
        assertNull(service().index(root).semanticModel());

        Files.writeString(root.resolve(".pecia.toml"), "[semantic]\nenabled = true\n");

        assertNull(service().index(root, IndexOptions.semantic(false)).semanticModel());
        service().preview(root);
        service().preview(root, IndexOptions.semantic(true));

        assertEquals(List.of(), requests);
    }

    @Test
    void anInvalidTokenBudgetFailsBeforeTheModelIsRequested() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[semantic]\nenabled = true\n\n[chunk]\nmax_tokens = 257\n");

        assertThrows(IllegalArgumentException.class, () -> service().index(root));

        assertEquals(List.of(), requests);
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void aPartialScanStillIndexesTheFilesItFoundSemantically() throws Exception {
        // A directory where a .gitignore file is expected makes discovery incomplete below it.
        Files.createDirectories(root.resolve("drafts/.gitignore"));

        IndexResult partial = service().index(root, IndexOptions.semantic(true));

        assertEquals(IndexResult.Status.PARTIAL, partial.status());
        assertEquals(1, partial.indexedFiles());
        assertEquals(partial.writtenChunks(), partial.embeddedChunks());
        assertSame(CACHED, partial.semanticModel());
        assertEquals(1, partial.scanIssues().size());
        assertEquals(1, requests.size());
    }

    @Test
    void anUnavailableModelAbortsBeforeAnIndexIsCreated() throws Exception {
        failure = new ModelException(ModelException.Reason.OFFLINE, "Could not reach huggingface.co", null);

        IndexException aborted =
                assertThrows(IndexException.class, () -> service().index(root, IndexOptions.semantic(true)));

        assertEquals(IndexResult.Status.MODEL_UNAVAILABLE, aborted.result().status());
        assertSame(failure, aborted.getCause());
        assertEquals(1, aborted.result().candidateCount());
        assertEquals(0, aborted.result().indexedFiles());
        assertNull(aborted.result().semanticModel());
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void anUnavailableModelLeavesAnExistingIndexUntouched() throws Exception {
        IndexResult lexical = service().index(root);
        Path database = lexical.context().databasePath();
        IndexDump before = IndexDump.read(database, root, List.of("semantic"));
        Files.writeString(root.resolve("guide.md"), "# Guide\n\nchanged before the semantic run\n");
        failure = new ModelException(ModelException.Reason.CHECKSUM_MISMATCH, "mismatch", null);

        assertThrows(IndexException.class, () -> service().index(root, IndexOptions.semantic(true)));

        assertEquals(before, IndexDump.read(database, root, List.of("semantic")));
    }

    @Test
    void aProviderIsRequired() {
        assertThrows(NullPointerException.class, () -> IndexService.withModelProvider(loader, null));
    }

    private IndexService service() {
        return IndexService.withModelProvider(loader, listener -> {
            requests.add(listener);

            if (failure != null) {
                throw failure;
            }

            return CACHED;
        });
    }
}
