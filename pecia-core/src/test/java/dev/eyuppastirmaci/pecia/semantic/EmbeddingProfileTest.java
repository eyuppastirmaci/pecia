package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class EmbeddingProfileTest {

    private static final EmbeddingProfile BASE = new EmbeddingProfile(
            "test/model", "rev", "b".repeat(64), 4, "wordpiece", "c".repeat(64), 256, "mean", "l2", "chunk-content");

    @Test
    void theMiniLmProfileDescribesThePinnedModelTokenizerAndPooling() {
        EmbeddingProfile profile = EmbeddingProfile.minilm();
        SemanticModel model = SemanticModel.MINILM;
        TokenizerIdentity tokenizer = MiniLmTokenizer.bundled().identity();

        assertEquals(
                new EmbeddingProfile(
                        model.repository(),
                        model.revision(),
                        model.sha256(),
                        384,
                        tokenizer.algorithm(),
                        tokenizer.vocabularySha256(),
                        256,
                        "mean",
                        "l2",
                        "chunk-content"),
                profile);
    }

    @Test
    void theFingerprintIsPinnedForTheMiniLmProfile() {
        // Computed independently from the documented format; changing it would orphan every stored vector.
        assertEquals(
                "2274f3cc351575f7c78b4b45da24075c272b1957ec0a969a94f414e909cc49f9",
                EmbeddingProfile.minilm().fingerprint());
    }

    @Test
    void everyComponentChangesTheFingerprint() {
        List<UnaryOperator<EmbeddingProfile>> changes = List.of(
                p -> copy(p, 0, "other/model"),
                p -> copy(p, 1, "rev2"),
                p -> copy(p, 2, "d".repeat(64)),
                p -> copy(p, 3, "8"),
                p -> copy(p, 4, "wordpiece-v3"),
                p -> copy(p, 5, "e".repeat(64)),
                p -> copy(p, 6, "128"),
                p -> copy(p, 7, "cls"),
                p -> copy(p, 8, "none"),
                p -> copy(p, 9, "heading-and-content"));

        for (UnaryOperator<EmbeddingProfile> change : changes) {
            assertNotEquals(
                    BASE.fingerprint(),
                    change.apply(BASE).fingerprint(),
                    change.apply(BASE).toString());
        }

        assertEquals(BASE.fingerprint(), copy(BASE, 0, "test/model").fingerprint());
    }

    @Test
    void textBoundariesAreUnambiguous() {
        // Length prefixes keep "ab" + "c" distinct from "a" + "bc".
        EmbeddingProfile first = copy(copy(BASE, 7, "ab"), 8, "c");
        EmbeddingProfile second = copy(copy(BASE, 7, "a"), 8, "bc");

        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void invalidComponentsAreRejected() {
        for (int component : new int[] {0, 1, 4, 7, 8, 9}) {
            assertThrows(IllegalArgumentException.class, () -> copy(BASE, component, " "), "component " + component);
        }

        for (int component : new int[] {2, 5}) {
            assertThrows(IllegalArgumentException.class, () -> copy(BASE, component, "B".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> copy(BASE, component, "b".repeat(63)));
        }

        for (String dimensions : new String[] {"0", "-1", "65537"}) {
            assertThrows(IllegalArgumentException.class, () -> copy(BASE, 3, dimensions));
        }

        assertEquals(65_536, copy(BASE, 3, "65536").dimensions());
        assertThrows(IllegalArgumentException.class, () -> copy(BASE, 6, "0"));
        assertThrows(NullPointerException.class, () -> copy(BASE, 0, null));
    }

    /** Returns the profile with one component, by position, replaced by the given value. */
    private static EmbeddingProfile copy(EmbeddingProfile p, int component, String value) {
        return new EmbeddingProfile(
                component == 0 ? value : p.modelId(),
                component == 1 ? value : p.modelRevision(),
                component == 2 ? value : p.modelSha256(),
                component == 3 ? Integer.parseInt(value) : p.dimensions(),
                component == 4 ? value : p.tokenizerAlgorithm(),
                component == 5 ? value : p.vocabularySha256(),
                component == 6 ? Integer.parseInt(value) : p.maxInputTokens(),
                component == 7 ? value : p.pooling(),
                component == 8 ? value : p.normalization(),
                component == 9 ? value : p.input());
    }
}
