package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs report files whose unchanged content was chunked again because the chunking settings changed. */
class RechunkedFilesTest {

    @TempDir
    Path sandbox;

    private final IndexService indexService = IndexService.withModelProvider(
            new PeciaConfigLoader(new PeciaConfigParser()), listener -> TinyModel.cached());
    private Path root;
    private int files;

    @BeforeEach
    void prepareProject() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        LexicalTestCorpus.write(root);
        files = LexicalTestCorpus.files().size();
    }

    @Test
    void newChunkSettingsRechunkEveryFileWithoutAContentChange() throws Exception {
        assertEquals(0, indexService.index(root).rechunkedFiles());
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 64\n");

        IndexResult rebuilt = indexService.index(root);

        assertEquals(files, rebuilt.indexedFiles());
        assertEquals(files, rebuilt.rechunkedFiles());
        assertEquals(0, indexService.index(root).rechunkedFiles());
    }

    @Test
    void aSemanticRunEmbedsRechunkedFilesAndKeepsNoOldEmbedding() throws Exception {
        indexService.index(root, IndexOptions.semantic(true));
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 64\n");

        IndexResult rebuilt = indexService.index(root, IndexOptions.semantic(true));
        IndexDump dump = IndexDump.read(rebuilt.context().databasePath(), root, List.of());

        assertEquals(files, rebuilt.rechunkedFiles());
        assertEquals(rebuilt.writtenChunks(), rebuilt.embeddedChunks());
        assertEquals(0, rebuilt.backfilledChunks());
        assertEquals(0, rebuilt.discardedEmbeddings());
        assertEquals(new EmbeddingCoverage(rebuilt.writtenChunks(), 0, 0), rebuilt.embeddingCoverage());
        assertEquals(dump.chunks().size(), dump.embeddings().size());
    }

    @Test
    void aContentChangeIsNotARechunk() throws Exception {
        indexService.index(root);
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\n");

        IndexResult changed = indexService.index(root);

        assertEquals(1, changed.indexedFiles());
        assertEquals(0, changed.rechunkedFiles());
    }

    @Test
    void rechunkedFilesMustBeIndexedFiles() throws Exception {
        IndexResult result = indexService.index(root);

        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(
                        result.context(),
                        IndexResult.Status.COMPLETE,
                        files,
                        1,
                        0,
                        0,
                        1,
                        List.of(),
                        null,
                        0,
                        0,
                        0,
                        null,
                        2));
    }
}
