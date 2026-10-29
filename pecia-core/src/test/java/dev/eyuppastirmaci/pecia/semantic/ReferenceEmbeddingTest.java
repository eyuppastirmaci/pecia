package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Compares the real model's embeddings with those sentence-transformers produced. Only the {@code referenceTest} task
 * runs it, after the model has been placed in the cache that {@code PECIA_CACHE_DIR} names; it never downloads.
 */
@Tag("reference")
class ReferenceEmbeddingTest {

    private static final String REFERENCE = "/semantic/reference/minilm-embeddings.toml";
    private static final double MIN_COSINE = 0.9999;
    private static final double MAX_COMPONENT_DIFFERENCE = 1e-4;

    private static Embedder embedder;

    @BeforeAll
    static void openTheCachedModel() throws Exception {
        SemanticModel model = SemanticModel.MINILM;
        Path file = model.cachedFile(CacheDirectory.resolve());

        assertTrue(
                Files.isRegularFile(file),
                "The reference check needs " + model.name() + " at " + file
                        + "; set PECIA_CACHE_DIR to a cache that holds it");
        assertEquals(model.size(), Files.size(file), "Cached model size");
        assertEquals(model.sha256(), sha256(file), "Cached model SHA-256");

        embedder = Embedder.open(new ModelOutcome(ModelOutcome.Status.CACHED, model, file));
    }

    @AfterAll
    static void closeTheModel() {
        if (embedder != null) {
            embedder.close();
        }
    }

    static List<ReferenceCase> referenceCases() throws IOException {
        TomlArray rows = reference().getArrayOrEmpty("cases");
        List<ReferenceCase> cases = new ArrayList<>();

        for (int index = 0; index < rows.size(); index++) {
            TomlTable row = rows.getTable(index);
            TomlArray values = row.getArrayOrEmpty("vector");
            float[] vector = new float[values.size()];

            for (int component = 0; component < vector.length; component++) {
                vector[component] = (float) values.getDouble(component);
            }

            cases.add(new ReferenceCase(row.getString("name"), row.getString("text"), vector));
        }

        return cases;
    }

    @Test
    void referenceDescribesThePinnedModel() throws IOException {
        TomlParseResult reference = reference();

        assertEquals(SemanticModel.MINILM.repository(), reference.getString("repository"));
        assertEquals(SemanticModel.MINILM.revision(), reference.getString("revision"));
        assertFalse(reference.getArrayOrEmpty("cases").isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    void eachTextMatchesItsReference(ReferenceCase reference) throws Exception {
        assertMatches(reference, embedder.embed(List.of(reference.text()))[0]);
    }

    @Test
    void allTextsInOneBatchMatchTheirReferences() throws Exception {
        // The real model attends across positions, so this checks that padding is masked for it too.
        List<ReferenceCase> cases = referenceCases();
        float[][] batch = embedder.embed(cases.stream().map(ReferenceCase::text).toList());

        for (int index = 0; index < cases.size(); index++) {
            assertMatches(cases.get(index), batch[index]);
        }
    }

    private static void assertMatches(ReferenceCase reference, float[] actual) {
        float[] expected = reference.vector();
        double dot = 0;
        double expectedSquares = 0;
        double actualSquares = 0;
        double largestDifference = 0;

        assertEquals(Embedder.DIMENSIONS, expected.length, reference.name());
        assertEquals(expected.length, actual.length, reference.name());

        for (int component = 0; component < expected.length; component++) {
            dot += (double) expected[component] * actual[component];
            expectedSquares += (double) expected[component] * expected[component];
            actualSquares += (double) actual[component] * actual[component];
            largestDifference = Math.max(largestDifference, Math.abs(expected[component] - actual[component]));
        }

        double cosine = dot / Math.sqrt(expectedSquares * actualSquares);

        assertTrue(cosine >= MIN_COSINE, reference.name() + ": cosine similarity " + cosine);
        assertTrue(
                largestDifference <= MAX_COMPONENT_DIFFERENCE,
                reference.name() + ": largest component difference " + largestDifference);
    }

    private static TomlParseResult reference() throws IOException {
        try (InputStream input = ReferenceEmbeddingTest.class.getResourceAsStream(REFERENCE)) {
            TomlParseResult parsed = Toml.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(parsed.hasErrors(), () -> parsed.errors().toString());

            return parsed;
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];

            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    record ReferenceCase(String name, String text, float[] vector) {
        @Override
        public String toString() {
            return name;
        }
    }
}
