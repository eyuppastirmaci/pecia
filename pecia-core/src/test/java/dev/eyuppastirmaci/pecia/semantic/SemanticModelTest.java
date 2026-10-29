package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticModelTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final String SHA_256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void miniLmIsPinnedToTheRepositoryAndRevisionOfTheBundledTokenizer() {
        TokenizerIdentity tokenizer = MiniLmTokenizer.bundled().identity();

        assertEquals(tokenizer.modelId(), SemanticModel.MINILM.repository());
        assertEquals(tokenizer.revision(), SemanticModel.MINILM.revision());
        assertEquals("all-MiniLM-L6-v2", SemanticModel.MINILM.name());
        assertEquals("onnx/model.onnx", SemanticModel.MINILM.file());
    }

    @Test
    void downloadUriPointsAtThePinnedFile() {
        assertEquals(
                URI.create("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/"
                        + "1110a243fdf4706b3f48f1d95db1a4f5529b4d41/onnx/model.onnx"),
                SemanticModel.MINILM.downloadUri());
    }

    @Test
    void cachedFileLivesUnderTheModelNameAndRevision() {
        Path root = Path.of("cache-root").toAbsolutePath();

        assertEquals(
                root.resolve("models")
                        .resolve("all-MiniLM-L6-v2")
                        .resolve("1110a243fdf4706b3f48f1d95db1a4f5529b4d41")
                        .resolve("model.onnx"),
                SemanticModel.MINILM.cachedFile(root));
    }

    @Test
    void acceptsAWellFormedTestDescriptor() {
        SemanticModel model = model(URI.create("http://127.0.0.1:8080/"), "test/model", "dir/model.bin", 1, SHA_256);

        assertEquals(
                URI.create("http://127.0.0.1:8080/test/model/resolve/" + REVISION + "/dir/model.bin"),
                model.downloadUri());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0123",
                "0123456789ABCDEF0123456789ABCDEF01234567",
                "g123456789abcdef0123456789abcdef01234567"
            })
    void rejectsMalformedRevisions(String revision) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SemanticModel(
                        "test", URI.create("https://example.test/"), "test/model", revision, "model.bin", 1, SHA_256));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0123", "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"})
    void rejectsMalformedChecksums(String sha256) {
        assertThrows(
                IllegalArgumentException.class,
                () -> model(URI.create("https://example.test/"), "test/model", "model.bin", 1, sha256));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/model.bin", "../model.bin", "dir/./model.bin", "dir//model.bin", "dir/", "a b"})
    void rejectsFilesThatAreNotPlainRelativePaths(String file) {
        assertThrows(
                IllegalArgumentException.class,
                () -> model(URI.create("https://example.test/"), "test/model", file, 1, SHA_256));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/test/model", "test/../model"})
    void rejectsRepositoriesThatAreNotPlainRelativePaths(String repository) {
        assertThrows(
                IllegalArgumentException.class,
                () -> model(URI.create("https://example.test/"), repository, "model.bin", 1, SHA_256));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/", "https://example.test/base"})
    void rejectsHostsThatAreNotAbsoluteDirectories(String host) {
        assertThrows(
                IllegalArgumentException.class, () -> model(URI.create(host), "test/model", "model.bin", 1, SHA_256));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveSizes(long size) {
        assertThrows(
                IllegalArgumentException.class,
                () -> model(URI.create("https://example.test/"), "test/model", "model.bin", size, SHA_256));
    }

    @Test
    void rejectsNamesThatCannotBeDirectoryNames() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SemanticModel(
                        "a/b", URI.create("https://example.test/"), "test/model", REVISION, "model.bin", 1, SHA_256));
    }

    private static SemanticModel model(URI host, String repository, String file, long size, String sha256) {
        return new SemanticModel("test", host, repository, REVISION, file, size, sha256);
    }
}
